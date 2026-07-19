package ceui.pixiv.download

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import ceui.pixiv.store.Database
import ceui.pixiv.store.DownloadQueueStore
import ceui.pixiv.store.DownloadTaskRecord
import ceui.pixiv.store.ShaftDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

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

    private fun createFixture(directory: Path, client: OkHttpClient): Fixture {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("queue.db")}")
        ShaftDatabase.Schema.create(driver)
        val queue = DownloadQueueStore(Database(driver).queries.downloadQueueQueries)
        val output = directory.resolve("download.jpg")
        val temp = directory.resolve("download.jpg.part")
        return Fixture(
            queue = queue,
            manager = DownloadManager(client = client, queue = queue, maxConcurrent = 1),
            output = output,
            temp = temp,
        )
    }

    private fun record(output: Path, temp: Path) = DownloadTaskRecord(
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
        status = DownloadStatus.QUEUED.name,
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

    private data class Fixture(
        val queue: DownloadQueueStore,
        val manager: DownloadManager,
        val output: Path,
        val temp: Path,
    )
}
