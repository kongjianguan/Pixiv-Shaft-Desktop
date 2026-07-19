package ceui.pixiv.download

import ceui.pixiv.store.DownloadTaskRecord

enum class DownloadTaskKind {
    IMAGE,
    UGOIRA,
}

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED,
}

data class DownloadTask(
    val id: String,
    val illustId: Long,
    val pageIndex: Int,
    val pageCount: Int,
    val kind: DownloadTaskKind,
    val title: String,
    val authorName: String,
    val sourceUrl: String,
    val outputPath: String,
    val tempPath: String,
    val status: DownloadStatus,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val progress: Float?
        get() = totalBytes.takeIf { it > 0L }?.let {
            (bytesDownloaded.toFloat() / it.toFloat()).coerceIn(0f, 1f)
        }

    val displayPage: String
        get() = when {
            kind == DownloadTaskKind.UGOIRA -> "动图 GIF"
            pageCount > 1 -> "第 ${pageIndex + 1} / $pageCount 页"
            else -> "单页作品"
        }

    companion object {
        fun from(record: DownloadTaskRecord): DownloadTask = DownloadTask(
            id = record.id,
            illustId = record.illustId,
            pageIndex = record.pageIndex.toInt(),
            pageCount = record.pageCount.toInt(),
            kind = runCatching { DownloadTaskKind.valueOf(record.kind) }
                .getOrDefault(DownloadTaskKind.IMAGE),
            title = record.title,
            authorName = record.authorName,
            sourceUrl = record.sourceUrl,
            outputPath = record.outputPath,
            tempPath = record.tempPath,
            status = runCatching { DownloadStatus.valueOf(record.status) }
                .getOrDefault(DownloadStatus.FAILED),
            bytesDownloaded = record.bytesDownloaded,
            totalBytes = record.totalBytes,
            errorMessage = record.errorMessage,
            createdAt = record.createdAt,
            updatedAt = record.updatedAt,
        )
    }
}
