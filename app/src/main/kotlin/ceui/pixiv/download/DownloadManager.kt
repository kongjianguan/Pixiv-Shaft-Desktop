package ceui.pixiv.download

import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesResp
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.net.api.API
import ceui.pixiv.net.imagehost.ImageHostManager
import ceui.pixiv.store.DownloadQueueStore
import ceui.pixiv.store.DownloadTaskRecord
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.ui.novel.NovelWebParser
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Response
import okhttp3.Request
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipInputStream
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Desktop-native download coordinator（下载协调器）.
 *
 * Each page is an independent task. The queue is stored in SQLDelight, while
 * bytes are first written to a sibling `.part` file and atomically moved into
 * place after a successful response. This gives us restart recovery without
 * making the UI responsible for file bookkeeping.
 */
class DownloadManager(
    private val client: OkHttpClient,
    private val queue: DownloadQueueStore,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    private val appApi: API,
    private val settingsStore: SettingsStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = mutableMapOf<String, Job>()
    private val runningLock = Any()
    /** 正在执行的 HTTP Call 的 cancel()；pause/cancel 时立即调用中断阻塞读（图片走 okhttp，小说走 retrofit） */
    private val runningCalls = mutableMapOf<String, () -> Unit>()
    private val runningCallsLock = Any()
    // 入队是纯本地 DB 操作（ms 级），同步执行让返回值精确反映「是否新增」，
    // 供 UI 判断「已加入下载队列」提示；ReentrantLock 而非协程 Mutex：入队可能
    // 从任意线程（UI onClick / 协程）调用
    private val enqueueLock = ReentrantLock()
    private val gson = Gson()
    private val downloadClient = client.newBuilder()
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val coordinator: Job = scope.launch {
        queue.resetDownloading(now())
        refresh()
        while (isActive) {
            launchQueuedTasks()
            kotlinx.coroutines.withTimeoutOrNull(COORDINATOR_WAIT_MS) {
                wake.receive()
            }
        }
    }

    fun enqueueIllust(illust: Illust): Int {
        if (illust.isGif()) return 0

        val pageCount = max(illust.page_count, 1)
        val urls = (0 until pageCount).mapNotNull { pageIndex ->
            pageUrl(illust, pageIndex)?.let { pageIndex to it }
        }
        if (urls.isEmpty()) return 0

        // 返回值 = 本次新增入队的任务数；已存在的页（仅刷新元数据）不计入，
        // 供 UI 判断「已加入下载队列」提示（重复入队不该再提示）
        return enqueueLock.withLock {
            val existing = queue.all()
            val existingByPage = existing
                .filter { it.illustId == illust.id && it.kind == DownloadTaskKind.IMAGE.name }
                .associateBy { it.pageIndex.toInt() }

            var inserted = 0
            urls.forEach { (pageIndex, sourceUrl) ->
                val current = existingByPage[pageIndex]
                if (current != null) {
                    // 与小说一致：任意状态重入队都刷新标题/作者/路径（作品可能已改名）。
                    // PAUSED / FAILED / CANCELED 重置为 QUEUED 恢复下载；COMPLETED 额外
                    // 标记重下载（旧文件保留到新文件写入成功后被原子替换）。状态重置放
                    // 最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置 QUEUED
                    if (current.status == DownloadStatus.COMPLETED.name) {
                        // 已下载过的作品再次入队 = 重新下载（文件可能已被用户删除）。
                        // 不在入队时删旧文件：旧文件保留到新文件写入成功后被原子替换，
                        // 重下载失败（断网/404/退出）时旧文件仍然可用
                        queue.markReDownload(current.id, now())
                    }
                    queue.updateMetadata(
                        current.id,
                        title = illust.title.orEmpty().ifBlank { "illust_${illust.id}" },
                        authorName = illust.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                        sourceUrl = sourceUrl,
                        metadataJson = null,
                        updatedAt = now(),
                    )
                    refreshOutputPath(
                        current,
                        outputPath(illust, pageIndex, pageCount, sourceUrl),
                        existing,
                    )
                    // 状态重置放最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置
                    // QUEUED，任务启动时才能读到最新快照
                    if (current.status != DownloadStatus.QUEUED.name &&
                        current.status != DownloadStatus.DOWNLOADING.name
                    ) {
                        queue.updateState(current.id, DownloadStatus.QUEUED.name, null, now())
                    }
                    return@forEach
                }

                val output = uniqueOutputPath(
                    outputPath(illust, pageIndex, pageCount, sourceUrl),
                    existing,
                )
                val taskId = UUID.randomUUID().toString()
                queue.insert(
                    DownloadTaskRecord(
                        id = taskId,
                        illustId = illust.id,
                        pageIndex = pageIndex.toLong(),
                        pageCount = pageCount.toLong(),
                        kind = DownloadTaskKind.IMAGE.name,
                        title = illust.title.orEmpty().ifBlank { "illust_${illust.id}" },
                        authorName = illust.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                        sourceUrl = sourceUrl,
                        metadataJson = null,
                        outputPath = output.toString(),
                        tempPath = "$output.part",
                        status = DownloadStatus.QUEUED.name,
                        bytesDownloaded = 0L,
                        totalBytes = 0L,
                        errorMessage = null,
                        reDownload = 0L,
                        createdAt = now(),
                        updatedAt = now(),
                    ),
                )
                inserted++
            }
            refresh()
            wake.trySend(Unit)
            inserted
        }
    }

    fun enqueueUgoira(illust: Illust, metadata: UgoiraMetaData): Int {
        if (!illust.isGif()) return 0
        val zipUrl = metadata.zip_urls?.medium?.takeIf { it.isNotBlank() } ?: return 0
        if (metadata.frames.isNullOrEmpty()) return 0

        // 返回值 = 本次是否新增任务（已有任务仅刷新元数据，返回 0）
        return enqueueLock.withLock {
            val all = queue.all()
            val current = all.firstOrNull {
                it.illustId == illust.id && it.kind == DownloadTaskKind.UGOIRA.name
            }
            if (current != null) {
                // 与图片一致：任意状态重入队都刷新标题/作者/帧数据（作品可能已改名
                // 或重新渲染）；PAUSED / FAILED / CANCELED 重置为 QUEUED 恢复下载；
                // COMPLETED 额外标记重下载。状态重置放最后：协调器只启动 QUEUED 任务
                if (current.status == DownloadStatus.COMPLETED.name) {
                    // 已下载过的作品再次入队 = 重新下载（文件可能已被用户删除）。
                    // 不在入队时删旧文件：旧文件保留到新文件写入成功后被原子替换，
                    // 重下载失败（断网/404/退出）时旧文件仍然可用
                    queue.markReDownload(current.id, now())
                }
                queue.updateMetadata(
                    current.id,
                    title = illust.title.orEmpty().ifBlank { "illust_${illust.id}" },
                    authorName = illust.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                    sourceUrl = zipUrl,
                    metadataJson = gson.toJson(metadata),
                    updatedAt = now(),
                )
                refreshOutputPath(current, ugoiraOutputPath(illust), all)
                // 状态重置放最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置 QUEUED
                if (current.status != DownloadStatus.QUEUED.name &&
                    current.status != DownloadStatus.DOWNLOADING.name
                ) {
                    queue.updateState(current.id, DownloadStatus.QUEUED.name, null, now())
                }
                refresh()
                wake.trySend(Unit)
                0
            } else {
                val output = uniqueOutputPath(ugoiraOutputPath(illust), all)
                val taskId = UUID.randomUUID().toString()
                queue.insert(
                    DownloadTaskRecord(
                        id = taskId,
                        illustId = illust.id,
                        pageIndex = 0L,
                        pageCount = 1L,
                        kind = DownloadTaskKind.UGOIRA.name,
                        title = illust.title.orEmpty().ifBlank { "illust_${illust.id}" },
                        authorName = illust.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                        sourceUrl = zipUrl,
                        metadataJson = gson.toJson(metadata),
                        outputPath = output.toString(),
                        tempPath = "$output.part",
                        status = DownloadStatus.QUEUED.name,
                        bytesDownloaded = 0L,
                        totalBytes = 0L,
                        errorMessage = null,
                        reDownload = 0L,
                        createdAt = now(),
                        updatedAt = now(),
                    ),
                )
                refresh()
                wake.trySend(Unit)
                1
            }
        }
    }

    fun enqueueNovel(novel: Novel): Int = enqueueNovelTask(novel, null)

    /**
     * 系列章节单篇入队：metadataJson 携带系列上下文（seriesId / 标题 / 序号 / 总篇数），
     * 路径模板与文件头按系列信息渲染。
     */
    fun enqueueNovelSeriesChapter(
        novel: Novel,
        seriesId: Long,
        seriesTitle: String,
        seriesOrder: Int,
        seriesTotal: Int,
    ): Int = enqueueNovelTask(
        novel,
        SeriesChapterMeta(seriesId, seriesTitle, seriesOrder, seriesTotal),
    )

    /** 单篇 / 系列章节共用入队逻辑，metadataJson 由 meta 决定（null = 普通单篇） */
    private fun enqueueNovelTask(novel: Novel, meta: SeriesChapterMeta?): Int {
        if (novel.id <= 0L) return 0
        val metaJson = meta?.let { gson.toJson(it) }
        // 返回值 = 本次是否新增任务（已有任务仅刷新元数据，返回 0）。
        // 新任务同步插入（本地 DB 写，ms 级），返回值精确供 UI 判断提示
        return enqueueLock.withLock {
            val all = queue.all()
            val current = all.firstOrNull {
                it.illustId == novel.id &&
                    it.kind == DownloadTaskKind.NOVEL.name &&
                    novelChapterMatches(it.metadataJson, metaJson)
            }
            if (current != null) {
                refreshExistingNovelTask(current, novel, meta)
                0
            } else {
                val output = uniqueOutputPath(novelOutputPath(novel, meta), all)
                val taskId = UUID.randomUUID().toString()
                queue.insert(
                    DownloadTaskRecord(
                        id = taskId,
                        illustId = novel.id,
                        pageIndex = 0L,
                        pageCount = 1L,
                        kind = DownloadTaskKind.NOVEL.name,
                        title = novel.title.orEmpty().ifBlank { "novel_${novel.id}" },
                        authorName = novel.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                        sourceUrl = "",
                        metadataJson = metaJson,
                        outputPath = output.toString(),
                        tempPath = "$output.part",
                        status = DownloadStatus.QUEUED.name,
                        bytesDownloaded = 0L,
                        totalBytes = 0L,
                        errorMessage = null,
                        reDownload = 0L,
                        createdAt = now(),
                        updatedAt = now(),
                    ),
                )
                refresh()
                wake.trySend(Unit)
                1
            }
        }
    }

    /**
     * 已有小说任务再次入队：刷新标题/作者/系列元数据与路径（作者可能已改名或新增
     * 章节，否则文件头仍写旧系列上下文）。QUEUED 只刷新元数据、不动状态；其余状态
     * 重置为 QUEUED（已完成任务额外标记重下载，旧文件保留到新文件写入成功后被原子
     * 替换）。状态重置放最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置
     * QUEUED，重启的任务才能读到最新快照。
     *
     * 运行中的任务无法同步处理：任务持有入队快照，只刷新 DB 不会生效（文件头/
     * 输出路径仍按旧系列信息渲染），必须先取消并等待退出（join 是挂起操作），
     * 因此走后台协程；取消期间状态可能被 runTask 改写，锁内重新读取为准。
     */
    private fun refreshExistingNovelTask(
        current: DownloadTaskRecord,
        novel: Novel,
        meta: SeriesChapterMeta?,
    ) {
        if (current.status == DownloadStatus.DOWNLOADING.name) {
            scope.launch {
                cancelRunningJob(current.id)
                // 取消瞬间任务可能恰好完成（COMPLETED）：标记重下载，重排后
                // 跳过「文件已存在即完成」短路，真正用最新元数据重写
                if (queue.all().firstOrNull { it.id == current.id }?.status ==
                    DownloadStatus.COMPLETED.name
                ) {
                    queue.markReDownload(current.id, now())
                }
                enqueueLock.withLock {
                    val fresh = queue.all().firstOrNull { it.id == current.id } ?: return@withLock
                    if (fresh.status == DownloadStatus.COMPLETED.name) {
                        queue.markReDownload(fresh.id, now())
                    }
                    queue.updateMetadata(
                        fresh.id,
                        title = novel.title.orEmpty().ifBlank { "novel_${novel.id}" },
                        authorName = novel.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                        sourceUrl = fresh.sourceUrl,
                        metadataJson = meta?.let { gson.toJson(it) },
                        updatedAt = now(),
                    )
                    refreshOutputPath(fresh, novelOutputPath(novel, meta), queue.all())
                    // 状态重置放最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置 QUEUED
                    if (fresh.status != DownloadStatus.QUEUED.name) {
                        queue.updateState(fresh.id, DownloadStatus.QUEUED.name, null, now())
                    }
                    refresh()
                    wake.trySend(Unit)
                }
            }
        } else {
            if (current.status == DownloadStatus.COMPLETED.name) {
                queue.markReDownload(current.id, now())
            }
            queue.updateMetadata(
                current.id,
                title = novel.title.orEmpty().ifBlank { "novel_${novel.id}" },
                authorName = novel.user?.name.orEmpty().ifBlank { "Unknown Artist" },
                sourceUrl = current.sourceUrl,
                metadataJson = meta?.let { gson.toJson(it) },
                updatedAt = now(),
            )
            refreshOutputPath(current, novelOutputPath(novel, meta), queue.all())
            // 状态重置放最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置 QUEUED
            if (current.status != DownloadStatus.QUEUED.name) {
                queue.updateState(current.id, DownloadStatus.QUEUED.name, null, now())
            }
            refresh()
            wake.trySend(Unit)
        }
    }

    /**
     * 系列章节去重只看系列身份：seriesOrder / seriesTotal 会随系列更新变化
     * （作者新增章节后总篇数变大），放进比较键会导致旧章节生成重复任务。
     * 单篇（metaJson = null）只与单篇任务去重。
     */
    private fun novelChapterMatches(existingMetadata: String?, targetMetadata: String?): Boolean {
        if (targetMetadata == null) return existingMetadata == null
        val existingMeta = existingMetadata?.let {
            runCatching { gson.fromJson(it, SeriesChapterMeta::class.java) }.getOrNull()
        }
        val targetMeta = runCatching { gson.fromJson(targetMetadata, SeriesChapterMeta::class.java) }
            .getOrNull()
        return existingMeta != null && targetMeta != null && existingMeta.seriesId == targetMeta.seriesId
    }

    /**
     * 系列合并导出入队：kind = NOVEL_SERIES，metadataJson 存导出格式；
     * 同系列同格式去重，TXT / MD 视为不同任务。
     */
    fun enqueueNovelMerge(seriesId: Long, seriesTitle: String, format: NovelMergeFormat): Int {
        if (seriesId <= 0L) return 0
        val metaJson = gson.toJson(MergeMeta(format.name))
        // 返回值 = 本次是否新增任务（已有任务仅刷新元数据，返回 0）
        return enqueueLock.withLock {
            val all = queue.all()
            val current = all.firstOrNull {
                it.illustId == seriesId &&
                    it.kind == DownloadTaskKind.NOVEL_SERIES.name &&
                    it.metadataJson == metaJson
            }
            if (current != null) {
                // 与图片/小说一致：任意状态重入队都刷新系列标题；
                // PAUSED / FAILED / CANCELED 重置为 QUEUED 恢复下载；
                // COMPLETED 额外标记重下载。状态重置放最后：协调器只启动 QUEUED 任务
                if (current.status == DownloadStatus.COMPLETED.name) {
                    // 已下载过的作品再次入队 = 重新下载（文件可能已被用户删除）。
                    // 不在入队时删旧文件：旧文件保留到新文件写入成功后被原子替换，
                    // 重下载失败（断网/404/退出）时旧文件仍然可用
                    queue.markReDownload(current.id, now())
                }
                queue.updateMetadata(
                    current.id,
                    title = seriesTitle.ifBlank { "series_$seriesId" },
                    authorName = "",
                    sourceUrl = current.sourceUrl,
                    metadataJson = metaJson,
                    updatedAt = now(),
                )
                refreshOutputPath(
                    current,
                    novelMergeOutputPath(seriesId, seriesTitle, format),
                    all,
                )
                // 状态重置放最后：协调器只启动 QUEUED 任务，刷新完元数据/路径再置 QUEUED
                if (current.status != DownloadStatus.QUEUED.name &&
                    current.status != DownloadStatus.DOWNLOADING.name
                ) {
                    queue.updateState(current.id, DownloadStatus.QUEUED.name, null, now())
                }
                refresh()
                wake.trySend(Unit)
                0
            } else {
                val output = uniqueOutputPath(
                    novelMergeOutputPath(seriesId, seriesTitle, format),
                    all,
                )
                val taskId = UUID.randomUUID().toString()
                queue.insert(
                    DownloadTaskRecord(
                        id = taskId,
                        illustId = seriesId,
                        pageIndex = 0L,
                        pageCount = 1L,
                        kind = DownloadTaskKind.NOVEL_SERIES.name,
                        title = seriesTitle.ifBlank { "series_$seriesId" },
                        authorName = "",
                        sourceUrl = "",
                        metadataJson = metaJson,
                        outputPath = output.toString(),
                        tempPath = "$output.part",
                        status = DownloadStatus.QUEUED.name,
                        bytesDownloaded = 0L,
                        totalBytes = 0L,
                        errorMessage = null,
                        reDownload = 0L,
                        createdAt = now(),
                        updatedAt = now(),
                    ),
                )
                refresh()
                wake.trySend(Unit)
                1
            }
        }
    }

    fun pause(id: String) {
        scope.launch {
            cancelRunningJob(id)
            // 竞态保护：任务协程可能刚写了 FAILED（网络错误）或 COMPLETED（恰好下完），
            // 不再覆盖成 PAUSED
            if (canOverwriteStatus(id)) {
                queue.updateState(id, DownloadStatus.PAUSED.name, null, now())
            }
            // 协调器可能在上一轮取消之前刚启动该任务（claimQueued 已成功）：
            // 补一轮取消，避免「点了暂停却仍在下载」
            cancelRunningJob(id)
            if (canOverwriteStatus(id)) {
                queue.updateState(id, DownloadStatus.PAUSED.name, null, now())
            }
            refresh()
            wake.trySend(Unit)
        }
    }

    fun resume(id: String) {
        scope.launch {
            queue.updateState(id, DownloadStatus.QUEUED.name, null, now())
            refresh()
            wake.trySend(Unit)
        }
    }

    fun cancel(id: String) {
        scope.launch {
            cancelRunningJob(id)
            deleteTempIfExists(id)
            // 竞态保护：任务协程可能刚写了 FAILED（网络错误）或 COMPLETED（恰好下完），
            // 不再覆盖成 CANCELED
            if (canOverwriteStatus(id)) {
                queue.updateState(id, DownloadStatus.CANCELED.name, null, now())
            }
            // 与 pause() 同理：协调器可能在第一轮取消之前刚启动该任务（claimQueued 已成功），
            // 补一轮取消，避免「点了取消却仍在下载」
            cancelRunningJob(id)
            deleteTempIfExists(id)
            if (canOverwriteStatus(id)) {
                queue.updateState(id, DownloadStatus.CANCELED.name, null, now())
            }
            refresh()
            wake.trySend(Unit)
        }
    }

    fun delete(id: String) {
        scope.launch {
            cancelRunningJob(id)
            // 与 pause()/cancel() 同理：协调器可能在第一轮取消之前刚启动该任务，
            // 补一轮取消，避免删除后任务仍在下载写出孤儿文件
            cancelRunningJob(id)
            val task = queue.all().firstOrNull { it.id == id }
            if (task != null) {
                deleteTempIfExists(id)
                if (task.status == DownloadStatus.COMPLETED.name) {
                    Files.deleteIfExists(Path.of(task.outputPath))
                }
            }
            queue.delete(id)
            refresh()
            wake.trySend(Unit)
        }
    }

    fun clearCompleted() {
        scope.launch {
            queue.all()
                .filter { it.status == DownloadStatus.COMPLETED.name }
                .forEach { Files.deleteIfExists(Path.of(it.tempPath)) }
            queue.clearCompleted()
            refresh()
        }
    }

    fun close() {
        synchronized(runningCallsLock) { runningCalls.values.forEach { it.invoke() } }
        synchronized(runningLock) {
            running.values.forEach { it.cancel() }
            running.clear()
        }
        coordinator.cancel()
        scope.cancel()
    }

    private suspend fun launchQueuedTasks() {
        val available = (maxConcurrent.coerceIn(1, 5) - synchronized(runningLock) { running.size })
            .coerceAtLeast(0)
        if (available == 0) return

        val queued = queue.all()
            .filter { it.status == DownloadStatus.QUEUED.name }
            .take(available)

        queued.forEach { record ->
            val job = synchronized(runningLock) {
                if (running.containsKey(record.id)) {
                    null
                } else {
                    scope.launch { runTask(record) }.also { running[record.id] = it }
                }
            } ?: return@forEach

            job.invokeOnCompletion {
                synchronized(runningLock) { running.remove(record.id) }
                wake.trySend(Unit)
            }
        }
    }

    private suspend fun runTask(record: DownloadTaskRecord) {
        // 协调器的入队快照可能滞后于并发的重新入队（路径/元数据刚被刷新）：
        // 以数据库当前行为准，用最新快照执行；已不是 QUEUED（被暂停/取消）则
        // 直接退出，避免「点了暂停却仍在下载」
        val fresh = queue.all().firstOrNull { it.id == record.id } ?: return
        if (fresh.status != DownloadStatus.QUEUED.name) return
        try {
            queue.updateState(fresh.id, DownloadStatus.DOWNLOADING.name, null, now())
            refresh()

            val output = Path.of(fresh.outputPath)
            val temp = Path.of(fresh.tempPath)
            Files.createDirectories(output.parent)

            // reDownload 任务（已完成作品再次入队）跳过此短路：必须真正重新下载，
            // 旧文件保留到新文件写入成功后被 moveIntoPlace 原子替换。
            // 标志完成后不清除：任务若被暂停/恢复，仍按「重新下载」语义执行，
            // 与入队时一致（UI 不会对已完成任务提供暂停入口，无实际影响）。
            if (fresh.reDownload == 0L &&
                Files.isRegularFile(output) && Files.size(output) > 0L
            ) {
                Files.deleteIfExists(temp)
                queue.updateProgress(fresh.id, Files.size(output), Files.size(output), now())
                queue.updateState(fresh.id, DownloadStatus.COMPLETED.name, null, now())
                refresh()
                return
            }

            val kind = taskKind(fresh)
            when (kind) {
                DownloadTaskKind.NOVEL -> downloadNovelText(fresh, temp, output)
                DownloadTaskKind.NOVEL_SERIES -> downloadNovelMerge(fresh, temp, output)
                DownloadTaskKind.UGOIRA -> {
                    downloadToTemp(fresh, temp)
                    convertUgoira(fresh, temp, output)
                }
                DownloadTaskKind.IMAGE -> {
                    downloadToTemp(fresh, temp)
                    moveIntoPlace(temp, output)
                }
            }
            val size = Files.size(output)
            Files.deleteIfExists(temp)
            // 系列合并的进度以「章」为单位（buildMergedContent 内推进到 total/total），
            // 完成时保持章数进度，不用文件字节覆盖，避免 100% 时数字从章节数突变成字节数
            if (kind != DownloadTaskKind.NOVEL_SERIES) {
                queue.updateProgress(fresh.id, size, size, now())
            }
            // pause 竞态兜底：下载完成瞬间用户可能已暂停，此时不再把状态改回 COMPLETED
            //（文件已落位，暂停任务恢复时会走 completed 短路直接完成）
            if (queue.all().firstOrNull { it.id == fresh.id }?.status ==
                DownloadStatus.DOWNLOADING.name
            ) {
                queue.updateState(fresh.id, DownloadStatus.COMPLETED.name, null, now())
                refresh()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            queue.updateState(
                fresh.id,
                DownloadStatus.FAILED.name,
                e.message ?: e.javaClass.simpleName,
                now(),
            )
            refresh()
        }
    }

    private suspend fun downloadToTemp(record: DownloadTaskRecord, temp: Path) {
        while (true) {
            val offset = if (Files.isRegularFile(temp)) Files.size(temp) else 0L
            val request = Request.Builder()
                .url(ImageHostManager.rewrite(record.sourceUrl))
                .apply {
                    if (offset > 0L) header("Range", "bytes=$offset-")
                }
                .build()

            val call = downloadClient.newCall(request)
            synchronized(runningCallsLock) { runningCalls[record.id] = call::cancel }
            // 取消可能发生在注册之前（job 已 cancel 但 call 还没建好）：注册后立即
            // 检查，已取消则马上掐掉，让阻塞的 execute() 快速失败；否则 pause/cancel
            // 的 join 会一直等到当前请求结束（最长 read timeout）
            if (!currentCoroutineContext().isActive) call.cancel()
            try {
                val shouldRestart = try {
                    call.execute().use { response ->
                        if (offset > 0L && response.code != 206) {
                            true
                        } else {
                            if (!response.isSuccessful) {
                                throw IOException("HTTP ${response.code}")
                            }
                            val body = response.body ?: throw IOException("Empty response body")
                            val contentLength = body.contentLength().coerceAtLeast(0L)
                            val total = if (contentLength > 0L) offset + contentLength else 0L
                            queue.updateProgress(record.id, offset, total, now())

                            val openOptions = buildList {
                                add(StandardOpenOption.CREATE)
                                add(StandardOpenOption.WRITE)
                                add(if (offset > 0L) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING)
                            }
                            Files.newOutputStream(temp, *openOptions.toTypedArray()).use { output ->
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var downloaded = offset
                                    var lastUpdate = 0L
                                    while (true) {
                                        currentCoroutineContext().ensureActive() // 不在阻塞读时也响应取消，快速退出
                                        val length = input.read(buffer)
                                        if (length < 0) break
                                        output.write(buffer, 0, length)
                                        downloaded += length
                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdate >= PROGRESS_INTERVAL_MS) {
                                            queue.updateProgress(record.id, downloaded, total, now())
                                            lastUpdate = now
                                            refresh()
                                        }
                                    }
                                    queue.updateProgress(record.id, downloaded, total, now())
                                }
                            }
                            false
                        }
                    }
                } catch (e: IOException) {
                    // pause/cancel 已调用 call.cancel()：把中断转成协程取消，
                    // 让 runTask 走 CancellationException 路径而不是标 FAILED
                    if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
                    throw e
                }

                if (!shouldRestart) return
                // 服务器忽略 Range 返回全量：丢弃半截临时文件，从 0 重新拉取。
                // 重启后 offset 必为 0（不再发 Range），因此无需二次检测。
                Files.deleteIfExists(temp)
            } finally {
                synchronized(runningCallsLock) { runningCalls.remove(record.id) }
            }
        }
    }

    /** 小说正文下载：拉取 HTML → 解析 → 清洗 → 加信息头 → 写临时文件后原子落位 */
    private suspend fun downloadNovelText(record: DownloadTaskRecord, temp: Path, output: Path) {
        val html = fetchNovelText(record.id, record.illustId)
        val webNovel = NovelWebParser.parse(html)
            ?: throw IOException("Unable to parse novel content")
        val meta = record.metadataJson?.let {
            runCatching { gson.fromJson(it, SeriesChapterMeta::class.java) }.getOrNull()
        }
        val content = novelHeader(record, meta) + cleanNovelText(webNovel.text.orEmpty()) + "\n"
        Files.writeString(
            temp,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        moveIntoPlace(temp, output)
    }

    private fun novelHeader(record: DownloadTaskRecord, meta: SeriesChapterMeta? = null): String {
        val time = LocalDateTime.now().format(DATE_TIME_FORMATTER)
        return buildString {
            append("标题：").append(record.title).append('\n')
            append("作者：").append(record.authorName).append('\n')
            append("作品ID：").append(record.illustId).append('\n')
            if (meta != null && meta.seriesTitle.isNotBlank()) {
                append("系列：").append(meta.seriesTitle)
                    .append("（第 ").append(meta.seriesOrder)
                    .append(" / ").append(meta.seriesTotal).append(" 篇）").append('\n')
            }
            append("下载时间：").append(time).append('\n')
            append('\n')
        }
    }

    /**
     * 系列合并下载：分页拉全章节 → 逐章抓正文（单章失败不中断，间隔防 429）
     * → 按格式拼装后写临时文件原子落位。
     */
    private suspend fun downloadNovelMerge(record: DownloadTaskRecord, temp: Path, output: Path) {
        val fetched = fetchAllSeriesChapters(record)
        val chapters = fetched.chapters
        if (chapters.isEmpty()) throw IOException("系列没有可下载的章节")
        val format = mergeFormat(record)
        val author = fetched.detail?.user?.name
            ?: chapters.firstOrNull()?.user?.name.orEmpty()
        val seriesTitle = fetched.detail?.title.orEmpty()
            .ifBlank { record.title }
        val expectedCount = fetched.detail?.content_count ?: 0
        val content = buildMergedContent(
            record, seriesTitle, author, chapters, format, expectedCount,
        )
        Files.writeString(
            temp,
            content,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        moveIntoPlace(temp, output)
    }

    /** 分页拉全系列章节；实现见 [fetchSeriesChapters]（与系列页共用，保证行为一致） */
    private suspend fun fetchAllSeriesChapters(record: DownloadTaskRecord): FetchedSeries =
        fetchSeriesChapters(
            appApi = appApi,
            seriesId = record.illustId,
            pageDelayMs = SERIES_PAGE_DELAY_MS,
            executePage = { lastOrder -> fetchSeriesPage(record.id, record.illustId, lastOrder) },
        )

    /** 系列列表单页请求：与 fetchNovelText 同款可中断实现（runningCalls + runInterruptible） */
    private suspend fun fetchSeriesPage(taskId: String, seriesId: Long, lastOrder: Int?): NovelSeriesResp {
        val call = appApi.getNovelSeriesCall(seriesId, lastOrder)
        return executeCancellableCall(taskId, call) { it.body() ?: throw IOException("Empty response body") }
    }

    private suspend fun buildMergedContent(
        record: DownloadTaskRecord,
        seriesTitle: String,
        author: String,
        chapters: List<Novel>,
        format: NovelMergeFormat,
        expectedCount: Int,
    ): String {
        val time = LocalDateTime.now().format(DATE_TIME_FORMATTER)
        val incomplete = expectedCount > 0 && chapters.size < expectedCount
        val sb = StringBuilder()
        if (format == NovelMergeFormat.MD) {
            sb.append("# ").append(seriesTitle).append("\n\n")
            sb.append("- 系列ID：").append(record.illustId).append('\n')
            if (author.isNotBlank()) sb.append("- 作者：").append(author).append('\n')
            sb.append("- 总篇数：").append(chapters.size)
            if (incomplete) {
                sb.append("（系列共 ").append(expectedCount).append(" 篇，本次仅获取 ").append(chapters.size).append(" 篇，文件不完整）")
            }
            sb.append('\n')
            sb.append("- 下载时间：").append(time).append("\n\n")
        } else {
            sb.append("系列：").append(seriesTitle).append('\n')
            sb.append("系列ID：").append(record.illustId).append('\n')
            if (author.isNotBlank()) sb.append("作者：").append(author).append('\n')
            sb.append("总篇数：").append(chapters.size)
            if (incomplete) {
                sb.append("（系列共 ").append(expectedCount).append(" 篇，本次仅获取 ").append(chapters.size).append(" 篇，文件不完整）")
            }
            sb.append('\n')
            sb.append("下载时间：").append(time).append("\n\n")
        }

        var failed = 0
        val failedChapters = mutableListOf<Pair<Long, String>>()
        val total = chapters.size
        for ((index, novel) in chapters.withIndex()) {
            val body = try {
                fetchChapterText(record.id, novel.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed++
                failedChapters += novel.id to novel.title.orEmpty()
                null
            }
            // 按章节推进进度，避免长系列合并看起来像卡死（totalBytes 以「章」为单位）；
            // 同时刷新任务列表，否则下载页进度停留在 0% 直到任务结束
            queue.updateProgress(record.id, (index + 1).toLong(), total.toLong(), now())
            refresh()
            if (body != null) {
                if (format == NovelMergeFormat.MD) {
                    sb.append("## ").append("第 ").append(index + 1).append(" 篇：")
                        .append(novel.title.orEmpty()).append("\n\n")
                    sb.append(body).append("\n\n")
                } else {
                    sb.append("================ 第 ").append(index + 1).append(" 篇：")
                        .append(novel.title.orEmpty()).append(" ================\n")
                    sb.append(body).append("\n\n")
                }
            }
            if (index < total - 1) delay(CHAPTER_DELAY_MS)
        }
        if (failed == total) throw IOException("全部 $total 章抓取失败，无法生成合并文件")
        if (failed > 0) {
            sb.append("\n[以下 ").append(failed).append(" 章下载失败，正文缺失]\n")
            failedChapters.forEach { (id, title) ->
                sb.append("- ").append(title.ifBlank { "untitled" }).append(" (id=").append(id).append(")\n")
            }
        }
        return sb.toString()
    }

    private suspend fun fetchChapterText(taskId: String, novelId: Long): String {
        val html = fetchNovelText(taskId, novelId)
        val webNovel = NovelWebParser.parse(html)
            ?: throw IOException("Unable to parse novel content (id=$novelId)")
        return cleanNovelText(webNovel.text.orEmpty())
    }

    /**
     * 小说正文抓取：与图片路径同款可中断实现。
     * - 把 Retrofit Call 注册进 runningCalls，pause/cancel 时 call.cancel() 立即可中断（非 QUIC 路径）
     * - runInterruptible：QUIC 拦截器内部阻塞在 CompletableFuture.get，只有线程中断能打断它，
     *   协程取消时 runInterruptible 会 interrupt 当前线程让阻塞读立刻抛异常
     * - IOException 且协程已被取消 → 转 CancellationException，让 runTask 走取消路径而不是标 FAILED
     */
    private suspend fun fetchNovelText(taskId: String, novelId: Long): String {
        val call = appApi.getNovelTextCall(novelId)
        return executeCancellableCall(taskId, call) { it.body()?.string() ?: throw IOException("Empty response body") }
    }

    private suspend fun <T, R> executeCancellableCall(
        taskId: String,
        call: Call<T>,
        extract: (Response<T>) -> R,
    ): R {
        synchronized(runningCallsLock) { runningCalls[taskId] = call::cancel }
        if (!currentCoroutineContext().isActive) call.cancel()
        try {
            return runInterruptible {
                val response = call.execute()
                if (!response.isSuccessful) throw IOException("HTTP ${response.code()}")
                extract(response)
            }
        } catch (e: IOException) {
            if (!currentCoroutineContext().isActive) throw CancellationException("cancelled")
            throw e
        } finally {
            synchronized(runningCallsLock) { runningCalls.remove(taskId) }
        }
    }

    private fun mergeFormat(record: DownloadTaskRecord): NovelMergeFormat {
        val name = record.metadataJson
            ?.let { runCatching { gson.fromJson(it, MergeMeta::class.java) }.getOrNull() }
            ?.format
        return runCatching { NovelMergeFormat.valueOf(name ?: "TXT") }
            .getOrDefault(NovelMergeFormat.TXT)
    }

    /** <br> 转 \n 并移除其余 HTML 标签（参照原 Shaft DownloadNovelTask.replaceBrWithNewLine） */
    private fun cleanNovelText(input: String): String = input
        .replace(BR_TAG_REGEX, "\n")
        .replace(HTML_TAG_REGEX, "")

    private fun convertUgoira(record: DownloadTaskRecord, zipPath: Path, output: Path) {
        val metadataJson = record.metadataJson ?: throw IOException("Ugoira metadata is missing")
        val metadata = runCatching { gson.fromJson(metadataJson, UgoiraMetaData::class.java) }
            .getOrNull()
            ?: throw IOException("Ugoira metadata is invalid")
        val frames = metadata.frames.orEmpty()
        if (frames.isEmpty()) throw IOException("Ugoira contains no frame metadata")

        val extractionDirectory = zipPath.resolveSibling("${zipPath.fileName}.frames")
        val encodedPath = output.resolveSibling("${output.fileName}.encode.part")
        try {
            deleteRecursively(extractionDirectory)
            Files.createDirectories(extractionDirectory)
            extractUgoiraFrames(zipPath, extractionDirectory, frames.mapNotNull { it.file })
            Files.deleteIfExists(encodedPath)
            UgoiraGifEncoder.encode(extractionDirectory, frames, encodedPath)
            moveIntoPlace(encodedPath, output)
        } finally {
            deleteRecursively(extractionDirectory)
            Files.deleteIfExists(encodedPath)
        }
    }

    private fun extractUgoiraFrames(zipPath: Path, outputDirectory: Path, frameNames: List<String>) {
        val expectedNames = frameNames
            .map { it.replace('\\', '/') }
            .associateBy { it.substringAfterLast('/') }
        ZipInputStream(Files.newInputStream(zipPath)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val entryName = entry.name.replace('\\', '/')
                val entryPath = outputDirectory.resolve(entryName).normalize()
                require(entryPath.startsWith(outputDirectory)) {
                    "Unsafe ugoira zip entry: ${entry.name}"
                }
                if (!entry.isDirectory) {
                    val fileName = entryName.substringAfterLast('/')
                    val targetName = expectedNames[fileName] ?: expectedNames[entryName]
                    if (targetName != null) {
                        val targetPath = outputDirectory.resolve(targetName).normalize()
                        require(targetPath.startsWith(outputDirectory)) {
                            "Unsafe ugoira frame path: $targetName"
                        }
                        Files.createDirectories(targetPath.parent)
                        Files.copy(input, targetPath, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                input.closeEntry()
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun moveIntoPlace(temp: Path, output: Path) {
        try {
            Files.move(
                temp,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, output, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun refresh() {
        _tasks.value = queue.all().map(DownloadTask::from)
    }

    /** 取消正在执行的任务协程并等待其退出（无任务时什么都不做） */
    private suspend fun cancelRunningJob(id: String) {
        val job = synchronized(runningLock) { running[id] } ?: return
        job.cancel() // 先取消协程（isActive 立即变 false），再掐 socket；join 后写状态，避免 runTask 的 FAILED 覆盖 PAUSED
        synchronized(runningCallsLock) { runningCalls[id]?.invoke() }
        job.join()
    }

    private fun deleteTempIfExists(id: String) {
        val task = queue.all().firstOrNull { it.id == id }
        if (task != null) Files.deleteIfExists(Path.of(task.tempPath))
    }

    /** pause/cancel 写状态前的终态保护：任务已 FAILED / COMPLETED 时不再覆盖 */
    private fun canOverwriteStatus(id: String): Boolean {
        val status = queue.all().firstOrNull { it.id == id }?.status
        return status != DownloadStatus.FAILED.name && status != DownloadStatus.COMPLETED.name
    }

    private fun pageUrl(illust: Illust, pageIndex: Int): String? {
        if (illust.page_count <= 1) {
            return illust.meta_single_page?.original_image_url ?: illust.fallbackUrl()
        }
        val pages = illust.meta_pages
        // 老作品 meta_pages 整体缺失（page_count 仍 > 1）时维持封面回退；
        // 否则缺失的页返回 null 由入队逻辑丢弃，不得用封面顶替（会下载出重复封面文件）
        if (pages.isNullOrEmpty()) {
            return illust.fallbackUrl()
        }
        return pages.getOrNull(pageIndex)?.image_urls?.original
            ?: pages.getOrNull(pageIndex)?.image_urls?.large
    }

    private fun Illust.fallbackUrl(): String? =
        image_urls?.original ?: image_urls?.large ?: image_urls?.medium

    private fun taskKind(record: DownloadTaskRecord): DownloadTaskKind = runCatching {
        DownloadTaskKind.valueOf(record.kind)
    }.getOrDefault(DownloadTaskKind.IMAGE)

    private fun outputPath(
        illust: Illust,
        pageIndex: Int,
        pageCount: Int,
        sourceUrl: String,
    ): Path {
        val ext = extensionOf(sourceUrl)
        val values = DownloadTemplateValues(
            title = illust.title.orEmpty().ifBlank { "illust_${illust.id}" },
            id = illust.id,
            author = illust.user?.name.orEmpty().ifBlank { "Unknown Artist" },
            authorId = illust.user?.id ?: 0L,
            page = if (pageCount > 1) " p${pageIndex + 1}" else "",
            ext = ".$ext",
            series = "",
            seriesOrder = "1",
            chapters = "1",
        )
        val relative = DownloadTemplate.renderPath(
            template = settingsStore.illustFileNameTemplate,
            values = values,
            autoPageSuffix = if (pageCount > 1) " p${pageIndex + 1}" else "",
            ext = ".$ext",
        )
        return defaultRoot().resolve(relative)
    }

    private fun ugoiraOutputPath(illust: Illust): Path {
        val values = DownloadTemplateValues(
            title = illust.title.orEmpty().ifBlank { "illust_${illust.id}" },
            id = illust.id,
            author = illust.user?.name.orEmpty().ifBlank { "Unknown Artist" },
            authorId = illust.user?.id ?: 0L,
            page = "",
            ext = ".gif",
            series = "",
            seriesOrder = "1",
            chapters = "1",
        )
        val relative = DownloadTemplate.renderPath(
            template = settingsStore.ugoiraFileNameTemplate,
            values = values,
            ext = ".gif",
        )
        return defaultRoot().resolve(relative)
    }

    private fun novelOutputPath(novel: Novel, meta: SeriesChapterMeta? = null): Path {
        val values = DownloadTemplateValues(
            title = novel.title.orEmpty().ifBlank { "novel_${novel.id}" },
            id = novel.id,
            author = novel.user?.name.orEmpty().ifBlank { "Unknown Artist" },
            authorId = novel.user?.id ?: 0L,
            page = "",
            ext = ".txt",
            // 单篇渲染空/1/1；系列章节由 meta 提供真实值，{series} 为空时模板中该目录段被移除
            series = meta?.seriesTitle.orEmpty(),
            seriesOrder = meta?.seriesOrder?.toString() ?: "1",
            chapters = meta?.seriesTotal?.toString() ?: "1",
        )
        val relative = DownloadTemplate.renderPath(
            template = settingsStore.novelFileNameTemplate,
            values = values,
            ext = ".txt",
        )
        return defaultRoot().resolve(relative)
    }

    /** 合并文件不套单篇模板，固定放 downloadRootPath/Novels/{seriesTitle}_{seriesId}.{ext} */
    private fun novelMergeOutputPath(
        seriesId: Long,
        seriesTitle: String,
        format: NovelMergeFormat,
    ): Path {
        val safeTitle = DownloadTemplate.sanitizeSegment(seriesTitle.ifBlank { "series_$seriesId" })
        val ext = if (format == NovelMergeFormat.MD) ".md" else ".txt"
        return defaultRoot().resolve("Novels").resolve("${safeTitle}_$seriesId$ext")
    }

    private fun defaultRoot(): Path = Path.of(settingsStore.downloadRootPath)

    /**
     * 输出路径消歧：模板去掉 {id} 等变量后，不同作品可能渲染出相同路径。
     * 与队列中已有任务或磁盘上已存在的文件冲突时追加 " (n)" 后缀，
     * 避免任务间互相覆盖 / 误判为已完成。
     */
    private fun uniqueOutputPath(
        desired: Path,
        existing: List<DownloadTaskRecord>,
    ): Path {
        val parent = desired.parent ?: defaultRoot()
        val name = desired.fileName?.toString() ?: desired.toString()
        val stem = name.substringBeforeLast('.', name)
        val ext = if (stem == name) "" else ".${name.substringAfterLast('.', "")}"
        var candidate = desired
        var counter = 2
        while (true) {
            if (existing.none { it.outputPath == candidate.toString() } &&
                !Files.isRegularFile(candidate)
            ) {
                return candidate
            }
            candidate = parent.resolve("$stem ($counter)$ext")
            counter++
        }
    }

    /**
     * 重新入队时按最新标题/模板重算输出路径，与元数据刷新保持同步：
     * 作品改名后重下载的文件名跟着新标题走（否则文件头是新标题、文件名还是旧标题）。
     * 正在运行的任务不动路径（运行中的任务持有入队快照，改了会写错位置）；
     * 判断依据是 running 表而不是状态字段：DOWNLOADING 任务重新入队时已被取消
     * （见 enqueueNovelTask），取消后允许刷新路径，重启的任务会读到新快照。
     *
     * 模板/标题没变时渲染路径与任务当前路径一致：保持原路径不变，不能把任务自己的路径
     * 交给 uniqueOutputPath 消歧（会被判为冲突而偏移成 " (2)"，旧文件永远不会被替换）。
     * 重下载任务（reDownload=1）在 runTask 里跳过「文件已存在即完成」短路，
     * moveIntoPlace 会原子替换原路径上的旧文件。
     * 路径变化（改名/模板变更）时只对「其他任务」消歧；原路径上的旧文件保留
     * （重下载失败时旧文件仍可用，与入队时的语义一致）。
     */
    private fun refreshOutputPath(
        current: DownloadTaskRecord,
        desired: Path,
        existing: List<DownloadTaskRecord>,
    ) {
        if (synchronized(runningLock) { running.containsKey(current.id) }) return
        if (desired.toString() == current.outputPath) return
        val others = existing.filterNot { it.id == current.id }
        val resolved = uniqueOutputPath(desired, others)
        if (resolved.toString() != current.outputPath) {
            moveOrDeleteTemp(current.tempPath, "$resolved.part")
            queue.updateOutputPaths(current.id, resolved.toString(), "$resolved.part", now())
        }
    }

    /**
     * 输出路径变化时处理旧 .part：优先移动到新临时路径，保留暂停/失败任务的续传进度；
     * 移动失败（跨卷/权限等）时删除旧文件，避免孤儿 .part 残留。
     */
    private fun moveOrDeleteTemp(oldTempPath: String, newTempPath: String) {
        val oldTemp = Path.of(oldTempPath)
        if (!Files.isRegularFile(oldTemp)) return
        try {
            val newTemp = Path.of(newTempPath)
            Files.createDirectories(newTemp.parent)
            Files.move(oldTemp, newTemp, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
            try {
                Files.deleteIfExists(oldTemp)
            } catch (_: IOException) {
                // 删除失败不阻塞入队；旧文件残留时后续 clearCompleted/cancel 仍可兜底
            }
        }
    }

    private fun extensionOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val candidate = clean.substringAfterLast('.', "jpg").lowercase()
        return candidate.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "jpg"
    }

    private fun now(): Long = System.currentTimeMillis()

    /** 系列章节下载上下文，序列化为任务的 metadataJson */
    private data class SeriesChapterMeta(
        val seriesId: Long,
        val seriesTitle: String,
        val seriesOrder: Int,
        val seriesTotal: Int,
    )

    /** 合并导出任务的 metadataJson：记录输出格式 */
    private data class MergeMeta(val format: String? = null)

    companion object {
        private const val DEFAULT_MAX_CONCURRENT = 3
        private const val BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val COORDINATOR_WAIT_MS = 500L
        private const val CHAPTER_DELAY_MS = 800L
        private const val SERIES_PAGE_DELAY_MS = 1000L
        private val BR_TAG_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
        private val HTML_TAG_REGEX = Regex("<[^>]*>")
        private val DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
