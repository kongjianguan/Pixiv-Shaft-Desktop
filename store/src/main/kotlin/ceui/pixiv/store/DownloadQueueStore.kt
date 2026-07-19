package ceui.pixiv.store

data class DownloadTaskRecord(
    val id: String,
    val illustId: Long,
    val pageIndex: Long,
    val pageCount: Long,
    val kind: String,
    val title: String,
    val authorName: String,
    val sourceUrl: String,
    val metadataJson: String?,
    val outputPath: String,
    val tempPath: String,
    val status: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

class DownloadQueueStore(
    private val queries: DownloadQueueQueries,
) {
    fun insert(task: DownloadTaskRecord) {
        queries.insertTask(
            id = task.id,
            illustId = task.illustId,
            pageIndex = task.pageIndex,
            pageCount = task.pageCount,
            kind = task.kind,
            title = task.title,
            authorName = task.authorName,
            sourceUrl = task.sourceUrl,
            metadataJson = task.metadataJson,
            outputPath = task.outputPath,
            tempPath = task.tempPath,
            status = task.status,
            bytesDownloaded = task.bytesDownloaded,
            totalBytes = task.totalBytes,
            errorMessage = task.errorMessage,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt,
        )
    }

    fun all(): List<DownloadTaskRecord> = queries.selectAll().executeAsList().map { row ->
        DownloadTaskRecord(
            id = row.id,
            illustId = row.illustId,
            pageIndex = row.pageIndex,
            pageCount = row.pageCount,
            kind = row.kind,
            title = row.title,
            authorName = row.authorName,
            sourceUrl = row.sourceUrl,
            metadataJson = row.metadataJson,
            outputPath = row.outputPath,
            tempPath = row.tempPath,
            status = row.status,
            bytesDownloaded = row.bytesDownloaded,
            totalBytes = row.totalBytes,
            errorMessage = row.errorMessage,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
    }

    fun updateProgress(id: String, bytesDownloaded: Long, totalBytes: Long, updatedAt: Long) {
        queries.updateProgress(bytesDownloaded, totalBytes, updatedAt, id)
    }

    fun updateState(id: String, status: String, errorMessage: String?, updatedAt: Long) {
        queries.updateState(status, errorMessage, updatedAt, id)
    }

    fun resetDownloading(updatedAt: Long) {
        queries.resetDownloading(updatedAt)
    }

    fun delete(id: String) {
        queries.deleteTask(id)
    }

    fun clearCompleted() {
        queries.clearCompleted()
    }
}
