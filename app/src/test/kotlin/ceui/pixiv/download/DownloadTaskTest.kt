package ceui.pixiv.download

import ceui.pixiv.store.DownloadTaskRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DownloadTaskTest {
    @Test
    fun `progress is null without total and clamped to valid range`() {
        assertNull(task(bytesDownloaded = 5L, totalBytes = 0L).progress)
        assertEquals(0f, task(bytesDownloaded = -5L, totalBytes = 100L).progress)
        assertEquals(0.5f, task(bytesDownloaded = 50L, totalBytes = 100L).progress)
        assertEquals(1f, task(bytesDownloaded = 150L, totalBytes = 100L).progress)
    }

    @Test
    fun `display page describes image and ugoira tasks`() {
        assertEquals("单页作品", task().displayPage)
        assertEquals("第 2 / 3 页", task(pageIndex = 1, pageCount = 3).displayPage)
        assertEquals("动图 GIF", task(kind = DownloadTaskKind.UGOIRA, pageCount = 9).displayPage)
    }

    @Test
    fun `from maps records and falls back for unknown enum values`() {
        val mapped = DownloadTask.from(record(kind = "UGOIRA", status = "PAUSED"))
        assertEquals(DownloadTaskKind.UGOIRA, mapped.kind)
        assertEquals(DownloadStatus.PAUSED, mapped.status)

        val fallback = DownloadTask.from(record(kind = "UNKNOWN", status = "BROKEN"))
        assertEquals(DownloadTaskKind.IMAGE, fallback.kind)
        assertEquals(DownloadStatus.FAILED, fallback.status)
    }

    private fun task(
        pageIndex: Int = 0,
        pageCount: Int = 1,
        kind: DownloadTaskKind = DownloadTaskKind.IMAGE,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = 0L,
    ) = DownloadTask(
        id = "task",
        illustId = 1L,
        pageIndex = pageIndex,
        pageCount = pageCount,
        kind = kind,
        title = "title",
        authorName = "author",
        sourceUrl = "source",
        outputPath = "output",
        tempPath = "temp",
        status = DownloadStatus.QUEUED,
        bytesDownloaded = bytesDownloaded,
        totalBytes = totalBytes,
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun record(kind: String, status: String) = DownloadTaskRecord(
        id = "record",
        illustId = 2L,
        pageIndex = 1L,
        pageCount = 2L,
        kind = kind,
        title = "title",
        authorName = "author",
        sourceUrl = "source",
        metadataJson = null,
        outputPath = "output",
        tempPath = "temp",
        status = status,
        bytesDownloaded = 10L,
        totalBytes = 20L,
        errorMessage = null,
        createdAt = 1L,
        updatedAt = 2L,
    )
}
