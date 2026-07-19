package ceui.pixiv.download

import ceui.loxia.Illust
import ceui.loxia.UgoiraMetaData
import ceui.pixiv.net.imagehost.ImageHostManager
import ceui.pixiv.store.DownloadQueueStore
import ceui.pixiv.store.DownloadTaskRecord
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val running = mutableMapOf<String, Job>()
    private val runningLock = Any()
    private val enqueueLock = Mutex()
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

        scope.launch {
            enqueueLock.withLock {
                val existing = queue.all()
                val existingByPage = existing
                    .filter { it.illustId == illust.id && it.kind == DownloadTaskKind.IMAGE.name }
                    .associateBy { it.pageIndex.toInt() }

                urls.forEach { (pageIndex, sourceUrl) ->
                    val current = existingByPage[pageIndex]
                    if (current != null) {
                        if (current.status == DownloadStatus.FAILED.name ||
                            current.status == DownloadStatus.CANCELED.name
                        ) {
                            queue.updateState(current.id, DownloadStatus.QUEUED.name, null, now())
                        }
                        return@forEach
                    }

                    val output = outputPath(illust, pageIndex, pageCount, sourceUrl)
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
                            createdAt = now(),
                            updatedAt = now(),
                        ),
                    )
                }
                refresh()
                wake.trySend(Unit)
            }
        }
        return urls.size
    }

    fun enqueueUgoira(illust: Illust, metadata: UgoiraMetaData): Int {
        if (!illust.isGif()) return 0
        val zipUrl = metadata.zip_urls?.medium?.takeIf { it.isNotBlank() } ?: return 0
        if (metadata.frames.isNullOrEmpty()) return 0

        scope.launch {
            enqueueLock.withLock {
                val current = queue.all().firstOrNull {
                    it.illustId == illust.id && it.kind == DownloadTaskKind.UGOIRA.name
                }
                if (current != null) {
                    if (current.status == DownloadStatus.FAILED.name ||
                        current.status == DownloadStatus.CANCELED.name
                    ) {
                        queue.updateState(current.id, DownloadStatus.QUEUED.name, null, now())
                    }
                } else {
                    val output = ugoiraOutputPath(illust)
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
                            createdAt = now(),
                            updatedAt = now(),
                        ),
                    )
                }
                refresh()
                wake.trySend(Unit)
            }
        }
        return 1
    }

    fun pause(id: String) {
        scope.launch {
            queue.updateState(id, DownloadStatus.PAUSED.name, null, now())
            runningJob(id)?.cancelAndJoinSafely()
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

    fun retry(id: String) {
        resume(id)
    }

    fun cancel(id: String) {
        scope.launch {
            runningJob(id)?.cancelAndJoinSafely()
            val task = queue.all().firstOrNull { it.id == id }
            if (task != null) Files.deleteIfExists(Path.of(task.tempPath))
            queue.updateState(id, DownloadStatus.CANCELED.name, null, now())
            refresh()
            wake.trySend(Unit)
        }
    }

    fun delete(id: String) {
        scope.launch {
            runningJob(id)?.cancelAndJoinSafely()
            val task = queue.all().firstOrNull { it.id == id }
            if (task != null) {
                Files.deleteIfExists(Path.of(task.tempPath))
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
        try {
            queue.updateState(record.id, DownloadStatus.DOWNLOADING.name, null, now())
            refresh()

            val output = Path.of(record.outputPath)
            val temp = Path.of(record.tempPath)
            Files.createDirectories(output.parent)

            if (Files.isRegularFile(output) && Files.size(output) > 0L) {
                Files.deleteIfExists(temp)
                queue.updateProgress(record.id, Files.size(output), Files.size(output), now())
                queue.updateState(record.id, DownloadStatus.COMPLETED.name, null, now())
                refresh()
                return
            }

            downloadToTemp(record, temp)
            if (taskKind(record) == DownloadTaskKind.UGOIRA) {
                convertUgoira(record, temp, output)
            } else {
                moveIntoPlace(temp, output)
            }
            val size = Files.size(output)
            Files.deleteIfExists(temp)
            queue.updateProgress(record.id, size, size, now())
            queue.updateState(record.id, DownloadStatus.COMPLETED.name, null, now())
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            queue.updateState(
                record.id,
                DownloadStatus.FAILED.name,
                e.message ?: e.javaClass.simpleName,
                now(),
            )
            refresh()
        }
    }

    private suspend fun downloadToTemp(record: DownloadTaskRecord, temp: Path) {
        var restarted = false
        while (true) {
            val offset = if (Files.isRegularFile(temp)) Files.size(temp) else 0L
            val request = Request.Builder()
                .url(ImageHostManager.rewrite(record.sourceUrl))
                .apply {
                    if (offset > 0L) header("Range", "bytes=$offset-")
                }
                .build()

            val shouldRestart = downloadClient.newCall(request).execute().use { response ->
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

            if (!shouldRestart) return
            if (restarted) throw IOException("Server refused resume request")
            Files.deleteIfExists(temp)
            restarted = true
        }
    }

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

    private suspend fun refresh() {
        _tasks.value = queue.all().map(DownloadTask::from)
    }

    private fun runningJob(id: String): Job? = synchronized(runningLock) { running[id] }

    private suspend fun Job.cancelAndJoinSafely() {
        cancel()
        join()
    }

    private fun pageUrl(illust: Illust, pageIndex: Int): String? {
        if (illust.page_count <= 1) {
            return illust.meta_single_page?.original_image_url
                ?: illust.image_urls?.original
                ?: illust.image_urls?.large
                ?: illust.image_urls?.medium
        }
        return illust.meta_pages?.getOrNull(pageIndex)?.image_urls?.original
            ?: illust.meta_pages?.getOrNull(pageIndex)?.image_urls?.large
            ?: illust.image_urls?.original
            ?: illust.image_urls?.large
            ?: illust.image_urls?.medium
    }

    private fun taskKind(record: DownloadTaskRecord): DownloadTaskKind = runCatching {
        DownloadTaskKind.valueOf(record.kind)
    }.getOrDefault(DownloadTaskKind.IMAGE)

    private fun outputPath(
        illust: Illust,
        pageIndex: Int,
        pageCount: Int,
        sourceUrl: String,
    ): Path {
        val author = sanitizeSegment(illust.user?.name.orEmpty().ifBlank { "Unknown Artist" })
        val title = sanitizeSegment(illust.title.orEmpty().ifBlank { "illust_${illust.id}" })
        val extension = extensionOf(sourceUrl)
        val pageSuffix = if (pageCount > 1) " p${pageIndex + 1}" else ""
        return defaultRoot()
            .resolve("Illusts")
            .resolve(author)
            .resolve("$title ${illust.id}$pageSuffix.$extension")
    }

    private fun ugoiraOutputPath(illust: Illust): Path {
        val author = sanitizeSegment(illust.user?.name.orEmpty().ifBlank { "Unknown Artist" })
        val title = sanitizeSegment(illust.title.orEmpty().ifBlank { "illust_${illust.id}" })
        return defaultRoot()
            .resolve("Ugoira")
            .resolve(author)
            .resolve("$title ${illust.id}.gif")
    }

    private fun defaultRoot(): Path = Path.of(
        System.getProperty("user.home"),
        "Pictures",
        "PixivShaft",
    )

    private fun sanitizeSegment(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()
        .ifBlank { "untitled" }
        .take(120)

    private fun extensionOf(url: String): String {
        val clean = url.substringBefore('?').substringBefore('#')
        val candidate = clean.substringAfterLast('.', "jpg").lowercase()
        return candidate.takeIf { it.matches(Regex("[a-z0-9]{2,5}")) } ?: "jpg"
    }

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private const val DEFAULT_MAX_CONCURRENT = 3
        private const val BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val COORDINATOR_WAIT_MS = 500L
    }
}
