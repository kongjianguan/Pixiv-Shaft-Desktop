package ceui.pixiv.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.NovelSeriesDetail
import ceui.loxia.NovelSeriesResp
import ceui.loxia.User
import ceui.pixiv.net.api.API
import ceui.pixiv.store.Database
import ceui.pixiv.store.DownloadQueueStore
import ceui.pixiv.store.InMemoryKvStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.ShaftDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.IOException
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloadManagerNovelTest {

    @Test
    fun `novel task downloads cleaned text to template path`(@TempDir directory: Path) = runBlocking {
        val html = """
            <script>
            Object.defineProperty(window, 'pixiv', { value: {
              "novel": {"id":"42","title":"Demo","text":"line1<br/>line2"}
            }});
            </script>
        """.trimIndent()
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        settingsStore.setNovelFileNameTemplate("Novels/{author}/{title}_{id}")

        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi(html),
            settingsStore = settingsStore,
        )

        try {
            assertEquals(
                1,
                manager.enqueueNovel(
                    Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist")),
                ),
            )

            var task: DownloadTask? = null
            withTimeout(5_000L) {
                while (task == null) {
                    val current = manager.tasks.value.singleOrNull()
                    when (current?.status) {
                        DownloadStatus.COMPLETED -> task = current
                        DownloadStatus.FAILED -> error(current.errorMessage ?: "novel task failed")
                        else -> delay(20L)
                    }
                }
            }
            val completed = requireNotNull(task)

            assertEquals(DownloadTaskKind.NOVEL, completed.kind)
            assertEquals(
                directory.resolve("Novels/Artist/Demo_42.txt").toString(),
                completed.outputPath,
            )
            assertTrue(Files.isRegularFile(Path.of(completed.outputPath)))
            val content = Files.readString(Path.of(completed.outputPath))
            assertTrue(content.contains("标题：Demo"))
            assertTrue(content.contains("作者：Artist"))
            assertTrue(content.contains("作品ID：42"))
            assertTrue(content.contains("line1\nline2"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `re-enqueue same novel id keeps a single task and returns zero`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi("irrelevant"),
            settingsStore = settingsStore,
        )

        try {
            val novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist"))
            // 单篇重复入队不新增任务（仅刷新元数据），返回 0
            assertEquals(1, manager.enqueueNovel(novel))
            assertEquals(0, manager.enqueueNovel(novel))
            // 系列章节重复入队同样不新增任务
            assertEquals(1, manager.enqueueNovelSeriesChapter(novel, 9L, "S", 1, 5))
            assertEquals(0, manager.enqueueNovelSeriesChapter(novel, 9L, "S", 1, 5))

            withTimeout(2_000L) {
                while (true) {
                    val tasks = manager.tasks.value
                    // 等到两条任务都到终态再 close，避免任务协程还在写 DB 时 JUnit 删临时目录
                    if (tasks.size >= 2 && tasks.all {
                            it.status == DownloadStatus.FAILED || it.status == DownloadStatus.COMPLETED
                        }
                    ) {
                        break
                    }
                    delay(20L)
                }
            }
            // 单篇与系列章节是两条不同任务，各自只有一条
            assertEquals(2, manager.tasks.value.size)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `series chapter task uses series context in path and header`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        settingsStore.setNovelFileNameTemplate("Novels/{series}/{series_order}_{title}_{id}")

        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi(novelHtml(42L, "series chapter<br/>body")),
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelSeriesChapter(
                novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist")),
                seriesId = 9L,
                seriesTitle = "My Series",
                seriesOrder = 2,
                seriesTotal = 12,
            )
            val task = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, task.status)
            assertEquals(
                directory.resolve("Novels/My Series/2_Demo_42.txt").toString(),
                task.outputPath,
            )
            val content = Files.readString(Path.of(task.outputPath))
            assertTrue(content.contains("系列：My Series（第 2 / 12 篇）"))
            assertTrue(content.contains("series chapter\nbody"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `re-enqueue series chapter after series updated keeps a single task`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi("irrelevant"),
            settingsStore = settingsStore,
        )

        try {
            val novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist"))
            manager.enqueueNovelSeriesChapter(novel, 9L, "S", 1, 5)
            withTimeout(5_000L) {
                while (manager.tasks.value.singleOrNull()?.status != DownloadStatus.FAILED) {
                    delay(20L)
                }
            }
            val taskId = manager.tasks.value.single().id
            val updatedAt = manager.tasks.value.single().updatedAt

            // 作者新增章节：seriesTotal 5 → 6，去重只看系列身份，必须命中已有任务
            manager.enqueueNovelSeriesChapter(novel, 9L, "S", 1, 6)
            withTimeout(5_000L) {
                while (manager.tasks.value.singleOrNull()?.let {
                        it.status == DownloadStatus.FAILED && it.updatedAt != updatedAt
                    } != true
                ) {
                    delay(20L)
                }
            }
            val tasks = manager.tasks.value
            assertEquals(1, tasks.size)
            assertEquals(taskId, tasks.single().id)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `re-enqueue completed series chapter refreshes stale series metadata`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        settingsStore.setNovelFileNameTemplate("Novels/{series}/{series_order}_{title}_{id}")

        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi(novelHtml(42L, "body<br/>text")),
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelSeriesChapter(
                novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist")),
                seriesId = 9L,
                seriesTitle = "My Series",
                seriesOrder = 2,
                seriesTotal = 5,
            )
            val first = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, first.status)
            assertTrue(Files.readString(Path.of(first.outputPath)).contains("系列：My Series（第 2 / 5 篇）"))

            // 作者更新系列：改名 + 新增章节（5 → 6）。重新入队必须刷新旧任务的元数据，
            // 否则重下载的文件头仍写旧系列上下文
            manager.enqueueNovelSeriesChapter(
                novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist")),
                seriesId = 9L,
                seriesTitle = "My Series 2",
                seriesOrder = 2,
                seriesTotal = 6,
            )
            val second = withTimeout(10_000L) {
                while (true) {
                    val current = manager.tasks.value.singleOrNull()
                    if (current != null &&
                        current.updatedAt > first.updatedAt &&
                        current.status == DownloadStatus.COMPLETED
                    ) {
                        return@withTimeout current
                    }
                    delay(20L)
                }
                error("unreachable")
            }

            // 去重：仍是同一条任务；系列改名后输出路径跟随新标题重新渲染
            // （文件名模板含 {series}，旧文件保留在原地，不删除）
            assertEquals(first.id, second.id)
            assertNotEquals(first.outputPath, second.outputPath)
            assertTrue(second.outputPath.endsWith("Novels/My Series 2/2_Demo_42.txt"))
            assertTrue(Files.isRegularFile(Path.of(first.outputPath)))
            val content = Files.readString(Path.of(second.outputPath))
            assertTrue(content.contains("系列：My Series 2（第 2 / 6 篇）"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `re-enqueue downloading series chapter refreshes stale series metadata`(@TempDir directory: Path) = runBlocking {
        val fetchStarted = CountDownLatch(1)
        val releaseFetch = CountDownLatch(1)
        val blockingCall = object : Call<ResponseBody> {
            override fun execute(): Response<ResponseBody> {
                fetchStarted.countDown()
                assertTrue(releaseFetch.await(5, TimeUnit.SECONDS))
                return Response.success(novelHtml(42L, "body<br/>text").toResponseBody("text/html".toMediaType()))
            }
            override fun enqueue(callback: Callback<ResponseBody>) {
                try {
                    callback.onResponse(this, execute())
                } catch (e: IOException) {
                    callback.onFailure(this, e)
                }
            }
            override fun clone(): Call<ResponseBody> = this
            override fun cancel() {}
            override fun isExecuted() = true
            override fun isCanceled() = false
            override fun request(): Request =
                Request.Builder().url("https://example.invalid/novel").build()
            override fun timeout(): Timeout = Timeout()
        }
        val loader = API::class.java.classLoader
        val api = Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            if (method.name == "getNovelTextCall") {
                blockingCall
            } else {
                throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        settingsStore.setNovelFileNameTemplate("Novels/{series}/{series_order}_{title}_{id}")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = queue,
            maxConcurrent = 1,
            appApi = api,
            settingsStore = settingsStore,
        )

        try {
            val novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist"))
            manager.enqueueNovelSeriesChapter(novel, 9L, "My Series", 2, 5)
            withTimeout(5_000L) {
                while (manager.tasks.value.singleOrNull()?.status != DownloadStatus.DOWNLOADING) {
                    delay(20L)
                }
            }
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            val taskId = manager.tasks.value.single().id

            // 下载进行中作者改名 + 新增章节（5 → 6）：重新入队必须刷新旧任务的系列元数据
            manager.enqueueNovelSeriesChapter(novel, 9L, "My Series 2", 2, 6)
            withTimeout(5_000L) {
                while (!queue.all().single().metadataJson.orEmpty().contains("My Series 2")) {
                    delay(20L)
                }
            }

            // 去重：仍是同一条任务；元数据已刷新
            assertEquals(1, manager.tasks.value.size)
            assertEquals(taskId, manager.tasks.value.single().id)

            releaseFetch.countDown()
            val completed = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, completed.status)
            // 正在下载的任务被取消后重排：重启的任务必须用最新系列元数据写文件头/路径，
            // 不能沿用入队快照的旧系列标题/顺序
            assertEquals(
                directory.resolve("Novels/My Series 2/2_Demo_42.txt").toString(),
                completed.outputPath,
            )
            val content = Files.readString(Path.of(completed.outputPath))
            assertTrue(content.contains("系列：My Series 2（第 2 / 6 篇）"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `merge series downloads full series as txt`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val chapters = listOf(
            Novel(id = 101L, title = "Ch One", user = User(id = 7L, name = "Artist")),
            Novel(id = 102L, title = "Ch Two", user = User(id = 7L, name = "Artist")),
        )
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = seriesFakeApi(chapters),
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.TXT)
            val task = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, task.status)
            assertEquals(DownloadTaskKind.NOVEL_SERIES, task.kind)
            assertEquals(directory.resolve("Novels/My Series_9.txt").toString(), task.outputPath)
            // 合并进度以「章」为单位：完成后保持 2/2，而不是被文件字节数覆盖
            assertEquals(2L, task.bytesDownloaded)
            assertEquals(2L, task.totalBytes)
            val content = Files.readString(Path.of(task.outputPath))
            assertTrue(content.contains("系列：My Series"))
            assertTrue(content.contains("总篇数：2"))
            assertTrue(content.contains("第 1 篇：Ch One"))
            assertTrue(content.contains("第 2 篇：Ch Two"))
            assertTrue(content.contains("first chapter"))
            assertTrue(content.contains("second chapter"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `merge series exports markdown`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val chapters = listOf(
            Novel(id = 101L, title = "Ch One", user = User(id = 7L, name = "Artist")),
            Novel(id = 102L, title = "Ch Two", user = User(id = 7L, name = "Artist")),
        )
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = seriesFakeApi(chapters),
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.MD)
            val task = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, task.status)
            assertEquals(directory.resolve("Novels/My Series_9.md").toString(), task.outputPath)
            val content = Files.readString(Path.of(task.outputPath))
            assertTrue(content.contains("# My Series"))
            assertTrue(content.contains("## 第 1 篇：Ch One"))
            assertTrue(content.contains("## 第 2 篇：Ch Two"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `pause interrupts in-flight novel fetch and keeps task paused`(@TempDir directory: Path) = runBlocking {
        val fetchStarted = CountDownLatch(1)
        val blockedWaitingCancel = CountDownLatch(1)
        val interruptedByCancel = AtomicBoolean(false)
        val blockingCall = object : Call<ResponseBody> {
            @Volatile
            private var cancelled = false
            override fun execute(): Response<ResponseBody> {
                fetchStarted.countDown()
                blockedWaitingCancel.countDown()
                while (!cancelled) {
                    try {
                        Thread.sleep(10L)
                    } catch (_: InterruptedException) {
                        // runInterruptible 在协程取消时 interrupt 当前线程；继续等到 cancel()
                    }
                }
                interruptedByCancel.set(true)
                throw IOException("cancelled")
            }
            override fun enqueue(callback: Callback<ResponseBody>) {
                try {
                    callback.onResponse(this, execute())
                } catch (e: IOException) {
                    callback.onFailure(this, e)
                }
            }
            override fun clone(): Call<ResponseBody> = this
            override fun cancel() { cancelled = true }
            override fun isExecuted() = true
            override fun isCanceled() = cancelled
            override fun request(): Request =
                Request.Builder().url("https://example.invalid/novel").build()
            override fun timeout(): Timeout = Timeout()
        }
        val loader = API::class.java.classLoader
        val api = Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            if (method.name == "getNovelTextCall") {
                blockingCall
            } else {
                throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = api,
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovel(
                Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist")),
            )
            val taskId = withTimeout(5_000L) {
                while (true) {
                    val task = manager.tasks.value.singleOrNull()
                    if (task != null && task.status == DownloadStatus.DOWNLOADING) {
                        return@withTimeout task.id
                    }
                    delay(20L)
                }
                error("unreachable")
            }
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))
            assertTrue(blockedWaitingCancel.await(5, TimeUnit.SECONDS))

            manager.pause(taskId)

            val paused = withTimeout(5_000L) {
                while (true) {
                    val task = manager.tasks.value.singleOrNull()
                    if (task != null && task.status == DownloadStatus.PAUSED) {
                        return@withTimeout task
                    }
                    delay(20L)
                }
                error("unreachable")
            }
            assertEquals(taskId, paused.id)
            assertTrue(interruptedByCancel.get())
            assertTrue(!Files.exists(Path.of(paused.outputPath)))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `pause interrupts in-flight merge series list fetch and keeps task paused`(@TempDir directory: Path) = runBlocking {
        val fetchStarted = CountDownLatch(1)
        val interruptedByCancel = AtomicBoolean(false)
        val blockingSeriesCall = object : Call<NovelSeriesResp> {
            @Volatile
            private var cancelled = false
            override fun execute(): Response<NovelSeriesResp> {
                fetchStarted.countDown()
                while (!cancelled) {
                    try {
                        Thread.sleep(10L)
                    } catch (_: InterruptedException) {
                        // runInterruptible 在协程取消时 interrupt 当前线程；继续等到 cancel()
                    }
                }
                interruptedByCancel.set(true)
                throw IOException("cancelled")
            }
            override fun enqueue(callback: Callback<NovelSeriesResp>) {
                try {
                    callback.onResponse(this, execute())
                } catch (e: IOException) {
                    callback.onFailure(this, e)
                }
            }
            override fun clone(): Call<NovelSeriesResp> = this
            override fun cancel() { cancelled = true }
            override fun isExecuted() = true
            override fun isCanceled() = cancelled
            override fun request(): Request =
                Request.Builder().url("https://example.invalid/series").build()
            override fun timeout(): Timeout = Timeout()
        }
        val loader = API::class.java.classLoader
        val api = Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            if (method.name == "getNovelSeriesCall") {
                blockingSeriesCall
            } else {
                throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = api,
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.TXT)
            val taskId = withTimeout(5_000L) {
                while (true) {
                    val task = manager.tasks.value.singleOrNull()
                    if (task != null && task.status == DownloadStatus.DOWNLOADING) {
                        return@withTimeout task.id
                    }
                    delay(20L)
                }
                error("unreachable")
            }
            assertTrue(fetchStarted.await(5, TimeUnit.SECONDS))

            // 系列列表拉取阶段也必须可中断：runningCalls + runInterruptible 双管齐下
            manager.pause(taskId)

            val paused = withTimeout(5_000L) {
                while (true) {
                    val task = manager.tasks.value.singleOrNull()
                    if (task != null && task.status == DownloadStatus.PAUSED) {
                        return@withTimeout task
                    }
                    delay(20L)
                }
                error("unreachable")
            }
            assertEquals(taskId, paused.id)
            assertTrue(interruptedByCancel.get())
        } finally {
            manager.close()
        }
    }

    @Test
    fun `merge series txt and md are independent tasks`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val chapters = listOf(
            Novel(id = 101L, title = "Ch One", user = User(id = 7L, name = "Artist")),
        )
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = seriesFakeApi(chapters),
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.TXT)
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.MD)
            var count = 0
            withTimeout(2_000L) {
                while (count < 2) {
                    count = manager.tasks.value.size
                    delay(20L)
                }
            }
            val outputs = manager.tasks.value.map { it.outputPath }.toSet()
            assertEquals(2, outputs.size)
            assertTrue(outputs.any { it.endsWith("_9.txt") })
            assertTrue(outputs.any { it.endsWith("_9.md") })
        } finally {
            manager.close()
        }
    }

    @Test
    fun `id-less template keeps same author title illusts on distinct paths`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        settingsStore.setIllustFileNameTemplate("Illusts/{author}/{title}{ext}")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi("irrelevant"),
            settingsStore = settingsStore,
        )

        try {
            val first = Illust(
                id = 1L,
                title = "Untitled",
                user = User(id = 7L, name = "Artist"),
                page_count = 1,
                type = "illust",
                meta_single_page = ceui.loxia.MetaSinglePage("https://example.invalid/1.jpg"),
            )
            val second = first.copy(id = 2L, meta_single_page = ceui.loxia.MetaSinglePage("https://example.invalid/2.jpg"))
            manager.enqueueIllust(first)
            manager.enqueueIllust(second)

            var paths = emptyList<String>()
            withTimeout(2_000L) {
                while (paths.size < 2) {
                    paths = manager.tasks.value.map { it.outputPath }
                    delay(20L)
                }
            }
            assertEquals(2, paths.size)
            assertEquals(2, paths.toSet().size)
            assertTrue(paths.any { it.endsWith("Untitled.jpg") })
            assertTrue(paths.any { it.endsWith("Untitled (2).jpg") })
        } finally {
            manager.close()
        }
    }

    @Test
    fun `plain novel and series chapter of same id are separate tasks`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        settingsStore.setNovelFileNameTemplate("Novels/{series}/{series_order}_{title}_{id}")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = fakeApi("irrelevant"),
            settingsStore = settingsStore,
        )

        try {
            val novel = Novel(id = 42L, title = "Demo", user = User(id = 7L, name = "Artist"))
            manager.enqueueNovel(novel)
            manager.enqueueNovelSeriesChapter(novel, seriesId = 9L, seriesTitle = "My Series", seriesOrder = 2, seriesTotal = 12)

            var tasks = emptyList<DownloadTask>()
            withTimeout(2_000L) {
                while (true) {
                    tasks = manager.tasks.value
                    if (tasks.size >= 2 && tasks.all {
                            it.status == DownloadStatus.FAILED || it.status == DownloadStatus.COMPLETED
                        }
                    ) {
                        break
                    }
                    delay(20L)
                }
            }
            val paths = tasks.map { it.outputPath }
            assertEquals(2, paths.toSet().size)
            assertTrue(paths.any { it.endsWith("1_Demo_42.txt") })
            assertTrue(paths.any { it.endsWith("Novels/My Series/2_Demo_42.txt") })
        } finally {
            manager.close()
        }
    }

    @Test
    fun `merge series fetches all pages through call path`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val chapter1 = Novel(id = 101L, title = "Ch One", user = User(id = 7L, name = "Artist"))
        val chapter2 = Novel(id = 102L, title = "Ch Two", user = User(id = 7L, name = "Artist"))
        val loader = API::class.java.classLoader
        val api = Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, args ->
            when (method.name) {
                "getNovelSeriesCall" -> {
                    val lastOrder = args.getOrNull(1) as? Int ?: 0
                    novelSeriesCall(
                        if (lastOrder == 0) {
                            NovelSeriesResp(
                                novel_series_detail = NovelSeriesDetail(
                                    id = 9L,
                                    title = "My Series",
                                    content_count = 2,
                                    user = User(id = 7L, name = "Artist"),
                                ),
                                novels = listOf(chapter1),
                                next_url = "https://example.invalid/series/page2",
                            )
                        } else {
                            NovelSeriesResp(
                                novels = listOf(chapter2),
                                next_url = null,
                            )
                        },
                    )
                }
                "getNovelTextCall" -> novelCall {
                    val id = args[0] as Long
                    novelHtml(id, if (id == 101L) "first chapter body" else "second chapter body")
                        .toResponseBody("text/html".toMediaType())
                }
                else -> throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = api,
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.TXT)
            val task = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, task.status)
            val content = Files.readString(Path.of(task.outputPath))
            assertTrue(content.contains("总篇数：2"))
            assertTrue(content.contains("第 1 篇：Ch One"))
            assertTrue(content.contains("第 2 篇：Ch Two"))
            assertTrue(content.contains("first chapter body"))
            assertTrue(content.contains("second chapter body"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `merge series annotates failed chapters in footer`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val chapters = listOf(
            Novel(id = 101L, title = "Ch One", user = User(id = 7L, name = "Artist")),
            Novel(id = 102L, title = "Ch Two", user = User(id = 7L, name = "Artist")),
        )
        val loader = API::class.java.classLoader
        val api = Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, args ->
            when (method.name) {
                "getNovelSeries" -> NovelSeriesResp(
                    novel_series_detail = NovelSeriesDetail(
                        id = 9L,
                        title = "My Series",
                        content_count = chapters.size,
                        user = User(id = 7L, name = "Artist"),
                    ),
                    novels = chapters,
                    next_url = null,
                )
                "getNovelSeriesCall" -> novelSeriesCall(
                    NovelSeriesResp(
                        novel_series_detail = NovelSeriesDetail(
                            id = 9L,
                            title = "My Series",
                            content_count = chapters.size,
                            user = User(id = 7L, name = "Artist"),
                        ),
                        novels = chapters,
                        next_url = null,
                    ),
                )
                "getNovelTextCall" -> novelCall {
                    val id = args[0] as Long
                    if (id == 101L) throw java.io.IOException("boom")
                    novelHtml(id, "second chapter body").toResponseBody("text/html".toMediaType())
                }
                else -> throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = api,
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.TXT)
            val task = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, task.status)
            val content = Files.readString(Path.of(task.outputPath))
            assertTrue(content.contains("[以下 1 章下载失败，正文缺失]"))
            assertTrue(content.contains("Ch One (id=101)"))
            assertTrue(content.contains("第 2 篇：Ch Two"))
        } finally {
            manager.close()
        }
    }

    @Test
    fun `merge series warns when fetched chapters fewer than content count`(@TempDir directory: Path) = runBlocking {
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        val chapters = listOf(
            Novel(id = 101L, title = "Ch One", user = User(id = 7L, name = "Artist")),
        )
        val loader = API::class.java.classLoader
        val api = Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            when (method.name) {
                "getNovelSeries" -> NovelSeriesResp(
                    novel_series_detail = NovelSeriesDetail(
                        id = 9L,
                        title = "My Series",
                        content_count = 2,
                        user = User(id = 7L, name = "Artist"),
                    ),
                    novels = chapters,
                    next_url = null,
                )
                "getNovelSeriesCall" -> novelSeriesCall(
                    NovelSeriesResp(
                        novel_series_detail = NovelSeriesDetail(
                            id = 9L,
                            title = "My Series",
                            content_count = 2,
                            user = User(id = 7L, name = "Artist"),
                        ),
                        novels = chapters,
                        next_url = null,
                    ),
                )
                "getNovelTextCall" -> novelCall {
                    novelHtml(101L, "first chapter body").toResponseBody("text/html".toMediaType())
                }
                else -> throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val manager = DownloadManager(
            client = OkHttpClient(),
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = api,
            settingsStore = settingsStore,
        )

        try {
            manager.enqueueNovelMerge(9L, "My Series", NovelMergeFormat.TXT)
            val task = manager.awaitTerminal()
            assertEquals(DownloadStatus.COMPLETED, task.status)
            val content = Files.readString(Path.of(task.outputPath))
            assertTrue(content.contains("总篇数：1（系列共 2 篇，本次仅获取 1 篇，文件不完整）"))
        } finally {
            manager.close()
        }
    }

    /** getNovelTextCall 返回固定 HTML 的假 Call，其余方法调用即抛 */
    private fun fakeApi(html: String): API {
        val loader = API::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            if (method.name == "getNovelTextCall") {
                novelCall { html.toResponseBody("text/html".toMediaType()) }
            } else {
                throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
    }

    /** 系列合并测试：getNovelSeriesCall 返回固定章节列表（无分页），getNovelTextCall 按 id 返回正文 */
    private fun seriesFakeApi(chapters: List<Novel>): API {
        val loader = API::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, args ->
            when (method.name) {
                "getNovelSeries" -> seriesResp(chapters)
                "getNovelSeriesCall" -> novelSeriesCall(seriesResp(chapters))
                "getNovelTextCall" -> novelCall {
                    val id = args[0] as Long
                    val text = if (id == chapters.first().id) "first chapter<br/>body" else "second chapter body"
                    novelHtml(id, text).toResponseBody("text/html".toMediaType())
                }
                else -> throw UnsupportedOperationException("unexpected api call: ${method.name}")
            }
        } as API
    }

    private fun seriesResp(chapters: List<Novel>) = NovelSeriesResp(
        novel_series_detail = NovelSeriesDetail(
            id = 9L,
            title = "My Series",
            content_count = chapters.size,
            user = User(id = 7L, name = "Artist"),
        ),
        novels = chapters,
        next_url = null,
    )

    /** 系列列表假 Call：与 novelCall 同款结构 */
    private fun novelSeriesCall(resp: NovelSeriesResp): Call<NovelSeriesResp> = object : Call<NovelSeriesResp> {
        override fun execute(): Response<NovelSeriesResp> = Response.success(resp)
        override fun enqueue(callback: Callback<NovelSeriesResp>) {
            try {
                callback.onResponse(this, execute())
            } catch (e: IOException) {
                callback.onFailure(this, e)
            }
        }
        override fun clone(): Call<NovelSeriesResp> = this
        override fun cancel() {}
        override fun isExecuted() = true
        override fun isCanceled() = false
        override fun request(): Request =
            Request.Builder().url("https://example.invalid/series").build()
        override fun timeout(): Timeout = Timeout()
    }

    /** 构造假 Call：execute() 时生成 ResponseBody（惰性，可抛异常模拟失败章节） */
    private fun novelCall(body: () -> ResponseBody): Call<ResponseBody> = object : Call<ResponseBody> {
        override fun execute(): Response<ResponseBody> = Response.success(body())
        override fun enqueue(callback: Callback<ResponseBody>) {
            try {
                callback.onResponse(this, execute())
            } catch (e: IOException) {
                callback.onFailure(this, e)
            }
        }
        override fun clone(): Call<ResponseBody> = this
        override fun cancel() {}
        override fun isExecuted() = true
        override fun isCanceled() = false
        override fun request(): Request =
            Request.Builder().url("https://example.invalid/novel").build()
        override fun timeout(): Timeout = Timeout()
    }

    private fun novelHtml(id: Long, text: String): String = """
        <script>
        Object.defineProperty(window, 'pixiv', { value: {
          "novel": {"id":"$id","title":"Demo","text":"$text"}
        }});
        </script>
    """.trimIndent()

    /** 等待队列出现终止态（COMPLETED / FAILED）任务并返回 */
    private suspend fun DownloadManager.awaitTerminal(): DownloadTask =
        withTimeout(10_000L) {
            while (true) {
                val task = tasks.value.singleOrNull()
                if (task != null &&
                    (task.status == DownloadStatus.COMPLETED || task.status == DownloadStatus.FAILED)
                ) {
                    return@withTimeout task
                }
                delay(20L)
            }
            error("unreachable")
        }
}
