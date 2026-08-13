package ceui.pixiv.download

import ceui.loxia.GifFrame
import ceui.loxia.Illust
import ceui.loxia.ObjectType
import ceui.loxia.UgoiraMetaData
import ceui.loxia.User
import ceui.loxia.ZipUrl
import ceui.pixiv.net.api.API
import ceui.pixiv.store.Database
import ceui.pixiv.store.DownloadQueueStore
import ceui.pixiv.store.InMemoryKvStore
import ceui.pixiv.store.SettingsStore
import ceui.pixiv.store.ShaftDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

class DownloadManagerUgoiraTest {
    @Test
    fun `ugoira task downloads zip and finishes as gif`(@TempDir directory: Path) = runBlocking {
        val originalHome = System.getProperty("user.home")
        val zipBytes = createZip()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(zipBytes.toResponseBody("application/zip".toMediaType()))
                    .build()
            }
            .build()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        System.setProperty("user.home", directory.toString())
        val manager = DownloadManager(
            client = client,
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = inertApi(),
            settingsStore = SettingsStore(InMemoryKvStore()),
        )

        try {
            val illust = Illust(
                id = 42L,
                title = "Demo Ugoira",
                type = ObjectType.GIF,
                user = User(id = 7L, name = "Artist"),
            )
            val metadata = UgoiraMetaData(
                zip_urls = ZipUrl(medium = "https://example.invalid/ugoira.zip"),
                frames = listOf(
                    GifFrame(file = "000000.jpg", delay = 120),
                    GifFrame(file = "000001.jpg", delay = 80),
                ),
            )

            assertEquals(1, manager.enqueueUgoira(illust, metadata))
            var completed: DownloadTask? = null
            withTimeout(5_000L) {
                while (completed == null) {
                    val task = manager.tasks.value.singleOrNull()
                    when (task?.status) {
                        DownloadStatus.COMPLETED -> completed = task
                        DownloadStatus.FAILED -> error(task.errorMessage ?: "Ugoira task failed")
                        else -> delay(20L)
                    }
                }
            }
            val completedTask = requireNotNull(completed)

            assertTrue(Files.isRegularFile(Path.of(completedTask.outputPath)))
            val reader = ImageIO.getImageReadersBySuffix("gif").asSequence().first()
            ImageIO.createImageInputStream(Path.of(completedTask.outputPath).toFile()).use { input ->
                reader.input = input
                assertEquals(2, reader.getNumImages(true))
            }
            reader.dispose()
        } finally {
            manager.close()
            if (originalHome == null) System.clearProperty("user.home")
            else System.setProperty("user.home", originalHome)
        }
    }

    @Test
    fun `re-enqueue completed ugoira re-downloads and replaces gif`(@TempDir directory: Path) = runBlocking {
        val originalHome = System.getProperty("user.home")
        val requestCount = AtomicInteger(0)
        val requestedUrls = ConcurrentLinkedQueue<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestedUrls.add(chain.request().url.toString())
                val zip = if (requestCount.getAndIncrement() == 0) {
                    createZip(listOf(Color.RED, Color.BLUE))
                } else {
                    // 同帧名、不同颜色：只有真正重新下载并转码，输出字节才会变化
                    createZip(listOf(Color.GREEN, Color.YELLOW))
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(zip.toResponseBody("application/zip".toMediaType()))
                    .build()
            }
            .build()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        System.setProperty("user.home", directory.toString())
        val manager = DownloadManager(
            client = client,
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
            appApi = inertApi(),
            settingsStore = SettingsStore(InMemoryKvStore()),
        )

        try {
            val illust = Illust(
                id = 42L,
                title = "Demo Ugoira",
                type = ObjectType.GIF,
                user = User(id = 7L, name = "Artist"),
            )
            val firstZipUrl = "https://example.invalid/ugoira-first.zip"
            val secondZipUrl = "https://example.invalid/ugoira-second.zip"
            val metadata = UgoiraMetaData(
                zip_urls = ZipUrl(medium = firstZipUrl),
                frames = listOf(
                    GifFrame(file = "000000.jpg", delay = 120),
                    GifFrame(file = "000001.jpg", delay = 80),
                ),
            )

            assertEquals(1, manager.enqueueUgoira(illust, metadata))
            val first = awaitCompleted(manager)
            val outputPath = Path.of(first.outputPath)
            val firstBytes = Files.readAllBytes(outputPath)

            // 已完成任务再次入队：不新增任务，但必须真正重新下载并原地替换最新 ZIP URL。
            val refreshedMetadata = metadata.copy(zip_urls = ZipUrl(medium = secondZipUrl))
            assertEquals(0, manager.enqueueUgoira(illust, refreshedMetadata))
            val second = withTimeout(5_000L) {
                while (true) {
                    val task = manager.tasks.value.singleOrNull()
                    if (task != null &&
                        task.status == DownloadStatus.COMPLETED &&
                        Files.isRegularFile(Path.of(task.outputPath)) &&
                        !Files.readAllBytes(Path.of(task.outputPath)).contentEquals(firstBytes)
                    ) {
                        return@withTimeout task
                    }
                    delay(20L)
                }
                error("unreachable")
            }

            assertEquals(first.id, second.id)
            assertEquals(first.outputPath, second.outputPath)
            assertEquals(secondZipUrl, second.sourceUrl)
            assertEquals(secondZipUrl, requestedUrls.last())
        } finally {
            manager.close()
            if (originalHome == null) System.clearProperty("user.home")
            else System.setProperty("user.home", originalHome)
        }
    }

    private fun createZip(): ByteArray = createZip(listOf(Color.RED, Color.BLUE))

    private fun createZip(frames: List<Color>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            frames.forEachIndexed { index, color ->
                zip.putNextEntry(ZipEntry("%06d.jpg".format(index)))
                zip.write(createFrame(color))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private suspend fun awaitCompleted(manager: DownloadManager): DownloadTask =
        withTimeout(5_000L) {
            while (true) {
                val task = manager.tasks.value.singleOrNull()
                when (task?.status) {
                    DownloadStatus.COMPLETED -> return@withTimeout task
                    DownloadStatus.FAILED -> error(task.errorMessage ?: "Ugoira task failed")
                    else -> delay(20L)
                }
            }
            error("unreachable")
        }

    private fun createFrame(color: Color): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                image.setRGB(x, y, color.rgb)
            }
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(image, "jpg", output)
            output.toByteArray()
        }
    }

    /** 测试不触碰 appApi；用动态代理提供一个调用即抛的惰性实现 */
    private fun inertApi(): API {
        val loader = API::class.java.classLoader
        return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, _ ->
            throw UnsupportedOperationException("appApi not used in this test: ${method.name}")
        } as API
    }
}
