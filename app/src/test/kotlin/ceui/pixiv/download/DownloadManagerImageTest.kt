package ceui.pixiv.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ceui.loxia.Illust
import ceui.loxia.MetaSinglePage
import ceui.loxia.User
import ceui.pixiv.net.api.API
import ceui.pixiv.store.Database
import ceui.pixiv.store.DownloadQueueStore
import ceui.pixiv.store.DownloadTaskRecord
import ceui.pixiv.store.InMemoryKvStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.ShaftDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okio.BufferedSource
import okio.buffer
import okio.source

class DownloadManagerImageTest {
    @Test
    fun `partial download restarts when server ignores range request`(@TempDir directory: Path) = runBlocking {
        val content = "complete-image".toByteArray()
        val rangeSeen = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (chain.request().header("Range") == "bytes=7-") {
                    rangeSeen.set(true)
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(content.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        Files.write(fixture.temp, "partial".toByteArray())
        fixture.queue.insert(record(fixture.output, fixture.temp))

        try {
            val completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)

            assertTrue(rangeSeen.get())
            assertArrayEquals(content, Files.readAllBytes(fixture.output))
            assertFalse(Files.exists(fixture.temp))
            assertEquals(content.size.toLong(), completed.bytesDownloaded)
            assertEquals(content.size.toLong(), completed.totalBytes)
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `http failure marks task failed and keeps error message`(@TempDir directory: Path) = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        fixture.queue.insert(record(fixture.output, fixture.temp))

        try {
            val failed = fixture.manager.awaitStatus(DownloadStatus.FAILED)

            assertEquals("HTTP 503", failed.errorMessage)
            assertFalse(Files.exists(fixture.output))
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `pause interrupts in-flight transfer and keeps task paused`(@TempDir directory: Path) = runBlocking {
        val firstByteRead = CountDownLatch(1)
        val blockedWaitingCancel = CountDownLatch(1)
        val interruptedByCancel = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val call = chain.call()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(object : ResponseBody() {
                        override fun contentType(): MediaType? = "image/jpeg".toMediaType()
                        override fun contentLength(): Long = -1L
                        override fun source(): BufferedSource {
                            val stream = object : InputStream() {
                                private var remaining = 1
                                override fun read(): Int {
                                    if (remaining > 0) {
                                        remaining--
                                        firstByteRead.countDown()
                                        return 'x'.code
                                    }
                                    blockedWaitingCancel.countDown()
                                    while (!call.isCanceled()) {
                                        Thread.sleep(10L)
                                    }
                                    interruptedByCancel.set(true)
                                    throw IOException("connection closed by cancel")
                                }
                            }
                            return stream.source().buffer()
                        }
                    })
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        fixture.queue.insert(record(fixture.output, fixture.temp))

        try {
            assertTrue(fixture.manager.awaitStatus(DownloadStatus.DOWNLOADING).id == "image-task")
            assertTrue(firstByteRead.await(5, TimeUnit.SECONDS))
            assertTrue(blockedWaitingCancel.await(5, TimeUnit.SECONDS))

            fixture.manager.pause("image-task")

            val paused = fixture.manager.awaitStatus(DownloadStatus.PAUSED)
            assertEquals("image-task", paused.id)
            assertTrue(interruptedByCancel.get())
            assertFalse(Files.exists(fixture.output))
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `pause and cancel after concurrent failure keep failed status`(@TempDir directory: Path) = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Unavailable")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        fixture.queue.insert(record(fixture.output, fixture.temp))

        try {
            val failed = fixture.manager.awaitStatus(DownloadStatus.FAILED)
            assertEquals("HTTP 503", failed.errorMessage)

            // 网络错误与用户点击暂停/取消竞态：任务协程已写 FAILED 后再操作，
            // 不得把失败状态覆盖成 PAUSED / CANCELED
            fixture.manager.pause("image-task")
            fixture.manager.cancel("image-task")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (System.nanoTime() < deadline) {
                assertEquals(DownloadStatus.FAILED, fixture.manager.tasks.value.single().status)
                delay(20L)
            }
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `re-enqueue completed task keeps old file until new download replaces it`(@TempDir directory: Path) = runBlocking {
        val requestCount = AtomicInteger(0)
        val secondRequestStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = if (requestCount.getAndIncrement() == 1) {
                    secondRequestStarted.countDown()
                    assertTrue(releaseSecond.await(5, TimeUnit.SECONDS))
                    "new-content"
                } else {
                    "old-content"
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)

        try {
            assertEquals(1, fixture.manager.enqueueIllust(sampleIllust()))
            var completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            val outputPath = Path.of(completed.outputPath)
            assertEquals("old-content", Files.readString(outputPath))

            // 重复入队不新增任务（仅刷新元数据并触发重下载），返回 0
            assertEquals(0, fixture.manager.enqueueIllust(sampleIllust()))
            assertTrue(secondRequestStarted.await(5, TimeUnit.SECONDS))
            // 重下载进行中：旧文件必须仍然存在
            assertEquals("old-content", Files.readString(outputPath))

            releaseSecond.countDown()
            completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            assertEquals("new-content", Files.readString(Path.of(completed.outputPath)))
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `re-enqueue image refreshes persisted source URL`(@TempDir directory: Path) = runBlocking {
        val requestedUrls = ConcurrentLinkedQueue<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestUrl = chain.request().url.toString()
                requestedUrls.add(requestUrl)
                val body = if (requestUrl.endsWith("/new.jpg")) "new-content" else "old-content"
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        val oldSourceUrl = "https://example.invalid/old.jpg"
        val newSourceUrl = "https://example.invalid/new.jpg"

        try {
            assertEquals(1, fixture.manager.enqueueIllust(sampleIllust(sourceUrl = oldSourceUrl)))
            val first = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            assertEquals(oldSourceUrl, first.sourceUrl)
            assertEquals("old-content", Files.readString(Path.of(first.outputPath)))

            assertEquals(0, fixture.manager.enqueueIllust(sampleIllust(sourceUrl = newSourceUrl)))
            val second = fixture.manager.awaitContent(Path.of(first.outputPath), "new-content")

            assertEquals(newSourceUrl, second.sourceUrl)
            assertEquals(newSourceUrl, fixture.queue.all().single().sourceUrl)
            assertEquals(newSourceUrl, requestedUrls.last())
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `re-enqueue completed task keeps old file when re-download fails`(@TempDir directory: Path) = runBlocking {
        val requestCount = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (requestCount.getAndIncrement() == 0) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("old-content".toResponseBody("image/jpeg".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                }
            }
            .build()
        val fixture = createFixture(directory, client)

        try {
            assertEquals(1, fixture.manager.enqueueIllust(sampleIllust()))
            val completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            val outputPath = Path.of(completed.outputPath)
            assertEquals("old-content", Files.readString(outputPath))

            // 重复入队不新增任务（仅刷新元数据并触发重下载），返回 0
            assertEquals(0, fixture.manager.enqueueIllust(sampleIllust()))
            val failed = fixture.manager.awaitStatus(DownloadStatus.FAILED)
            assertEquals("HTTP 503", failed.errorMessage)
            // 重下载失败：旧文件原封不动
            assertTrue(Files.isRegularFile(outputPath))
            assertEquals("old-content", Files.readString(outputPath))
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `re-enqueue completed task after file deletion restores the same path`(@TempDir directory: Path) = runBlocking {
        val counter = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("data-${counter.incrementAndGet()}".toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)

        try {
            assertEquals(1, fixture.manager.enqueueIllust(sampleIllust()))
            val first = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            val firstPath = Path.of(first.outputPath)
            assertEquals("data-1", Files.readString(firstPath))

            // 旧文件被用户删除：重下载必须写回原路径，不产生 "(2)" 副本
            Files.delete(firstPath)
            assertEquals(0, fixture.manager.enqueueIllust(sampleIllust()))
            val restored = fixture.manager.awaitContent(firstPath, "data-2")

            assertEquals(first.outputPath, restored.outputPath)
            assertEquals("data-2", Files.readString(firstPath))
            val jpgs = regularFilesUnder(directory).filter { it.fileName.toString().endsWith(".jpg") }
            assertEquals(listOf(firstPath), jpgs)
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `re-enqueue after title change moves partial part file to new path`(@TempDir directory: Path) = runBlocking {
        val rangeSeen = AtomicBoolean(false)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val range = chain.request().header("Range")
                if (range == "bytes=7-") rangeSeen.set(true)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (range == "bytes=7-") 206 else 200)
                    .message("OK")
                    .body("rest".toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        Files.write(fixture.temp, "partial".toByteArray())
        fixture.queue.insert(record(fixture.output, fixture.temp, status = DownloadStatus.PAUSED))

        try {
            // 暂停中的任务因作者改名重新入队：输出路径变化，
            // 旧 .part 必须迁移到新路径并继续从 offset 7 续传
            assertEquals(0, fixture.manager.enqueueIllust(sampleIllust(title = "Renamed")))
            val completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)

            // 带 Range 续传请求出现 = .part 被迁移且进度被保留；旧 .part 不应残留
            assertTrue(rangeSeen.get())
            assertFalse(Files.exists(fixture.temp))
            assertTrue(Files.isRegularFile(Path.of(completed.outputPath)))
            assertEquals("partialrest", Files.readString(Path.of(completed.outputPath)))
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `enqueue illust with sparse meta pages drops missing pages instead of duplicating cover`(@TempDir directory: Path) = runBlocking {
        val client = OkHttpClient()
        val fixture = createFixture(directory, client)

        try {
            val illust = Illust(
                id = 42L,
                title = "Sparse",
                user = User(id = 7L, name = "Artist"),
                page_count = 3,
                type = "illust",
                meta_pages = listOf(
                    ceui.loxia.MetaPage(
                        ceui.loxia.ImageUrls(original = "https://example.invalid/p0.jpg"),
                    ),
                ),
                image_urls = ceui.loxia.ImageUrls(original = "https://example.invalid/cover.jpg"),
            )
            // 只入队真实存在的页；缺失的页不得用封面 image_urls 顶替（会产生重复封面文件）
            assertEquals(1, fixture.manager.enqueueIllust(illust))
            assertEquals(1, fixture.manager.tasks.value.size)
            assertEquals(
                "https://example.invalid/p0.jpg",
                fixture.manager.tasks.value.single().sourceUrl,
            )
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `enqueue illust with empty meta pages still downloads all pages via cover fallback`(@TempDir directory: Path) = runBlocking {
        val client = OkHttpClient()
        val fixture = createFixture(directory, client)

        try {
            val illust = Illust(
                id = 42L,
                title = "OldManga",
                user = User(id = 7L, name = "Artist"),
                page_count = 2,
                type = "illust",
                // 老作品 meta_pages 整体缺失（page_count 仍 > 1）：维持封面回退行为
                meta_pages = null,
                image_urls = ceui.loxia.ImageUrls(original = "https://example.invalid/cover.jpg"),
            )
            assertEquals(2, fixture.manager.enqueueIllust(illust))
            val tasks = fixture.manager.tasks.value
            assertEquals(2, tasks.size)
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `resume paused task downloads and completes`(@TempDir directory: Path) = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("paused-content".toResponseBody("image/jpeg".toMediaType()))
                    .build()
            }
            .build()
        val fixture = createFixture(directory, client)
        fixture.queue.insert(record(fixture.output, fixture.temp, status = DownloadStatus.PAUSED))

        try {
            fixture.manager.resume("image-task")

            val completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            assertEquals("paused-content", Files.readString(Path.of(completed.outputPath)))
            assertEquals(null, completed.errorMessage)
            assertFalse(Files.exists(fixture.temp))
        } finally {
            fixture.manager.close()
        }
    }

    @Test
    fun `retry failed task re-queues and clears error message`(@TempDir directory: Path) = runBlocking {
        val counter = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (counter.getAndIncrement() == 0) {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(503)
                        .message("Unavailable")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                } else {
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("retried-content".toResponseBody("image/jpeg".toMediaType()))
                        .build()
                }
            }
            .build()
        val fixture = createFixture(directory, client)
        fixture.queue.insert(record(fixture.output, fixture.temp))

        try {
            val failed = fixture.manager.awaitStatus(DownloadStatus.FAILED)
            assertEquals("HTTP 503", failed.errorMessage)

            fixture.manager.resume(failed.id)

            val completed = fixture.manager.awaitStatus(DownloadStatus.COMPLETED)
            assertEquals(null, completed.errorMessage)
            assertEquals("retried-content", Files.readString(Path.of(completed.outputPath)))
            assertFalse(Files.exists(fixture.temp))
        } finally {
            fixture.manager.close()
        }
    }

    private fun sampleIllust(
        title: String = "Untitled",
        sourceUrl: String = "https://example.invalid/1.jpg",
    ) = Illust(
        id = 42L,
        title = title,
        user = User(id = 7L, name = "Artist"),
        page_count = 1,
        type = "illust",
        meta_single_page = MetaSinglePage(sourceUrl),
    )

    private fun createFixture(directory: Path, client: OkHttpClient): Fixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries)
        val output = directory.resolve("download.jpg")
        val temp = directory.resolve("download.jpg.part")
        val settingsStore = SettingsStore(InMemoryKvStore())
        settingsStore.setDownloadRootPath(directory.toString())
        return Fixture(
            queue = queue,
            manager = DownloadManager(
                client = client,
                queue = queue,
                maxConcurrent = 1,
                appApi = inertApi(),
                settingsStore = settingsStore,
            ),
            output = output,
            temp = temp,
        )
    }

    /** 测试不触碰 appApi；用动态代理提供一个调用即抛的惰性实现 */
    private fun inertApi(): API {
        val loader = API::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            throw UnsupportedOperationException("appApi not used in this test: ${method.name}")
        } as API
    }

    private fun record(output: Path, temp: Path, status: DownloadStatus = DownloadStatus.QUEUED) = DownloadTaskRecord(
        id = "image-task",
        illustId = 42L,
        pageIndex = 0L,
        pageCount = 1L,
        kind = DownloadTaskKind.IMAGE.name,
        title = "title",
        authorName = "author",
        sourceUrl = "https://example.invalid/image.jpg",
        metadataJson = null,
        outputPath = output.toString(),
        tempPath = temp.toString(),
        status = status.name,
        bytesDownloaded = 0L,
        totalBytes = 0L,
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private suspend fun DownloadManager.awaitStatus(status: DownloadStatus): DownloadTask =
        withTimeout(5_000L) {
            while (true) {
                tasks.value.singleOrNull()?.let { task ->
                    if (task.status == status) return@withTimeout task
                }
                delay(20L)
            }
            error("unreachable")
        }

    /** 等到任务完成且磁盘文件内容变为 expectedContent（区分首次完成与重下载完成） */
    private suspend fun DownloadManager.awaitContent(path: Path, expectedContent: String): DownloadTask =
        withTimeout(5_000L) {
            while (true) {
                val task = tasks.value.singleOrNull()
                if (task != null &&
                    task.status == DownloadStatus.COMPLETED &&
                    Path.of(task.outputPath) == path &&
                    Files.isRegularFile(path) &&
                    Files.readString(path) == expectedContent
                ) {
                    return@withTimeout task
                }
                delay(20L)
            }
            error("unreachable")
        }

    private fun regularFilesUnder(root: Path): List<Path> =
        Files.walk(root).use { paths -> paths.filter { Files.isRegularFile(it) }.sorted().toList() }

    private data class Fixture(
        val queue: DownloadQueueStore,
        val manager: DownloadManager,
        val output: Path,
        val temp: Path,
    )
}
