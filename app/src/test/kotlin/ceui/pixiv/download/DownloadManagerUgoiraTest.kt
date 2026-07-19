package ceui.pixiv.download

import ceui.loxia.GifFrame
import ceui.loxia.Illust
import ceui.loxia.ObjectType
import ceui.loxia.UgoiraMetaData
import ceui.loxia.User
import ceui.loxia.ZipUrl
import ceui.pixiv.store.Database
import ceui.pixiv.store.DownloadQueueStore
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
import java.nio.file.Files
import java.nio.file.Path
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
        val manager = DownloadManager(
            client = client,
            queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries),
            maxConcurrent = 1,
        )

        try {
            System.setProperty("user.home", directory.toString())
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

    private fun createZip(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("000000.jpg"))
            zip.write(createFrame(Color.RED))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("000001.jpg"))
            zip.write(createFrame(Color.BLUE))
            zip.closeEntry()
        }
        return output.toByteArray()
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
}
