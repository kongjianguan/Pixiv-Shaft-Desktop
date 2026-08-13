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
    /** 已完成任务再次入队时置 1：runTask 跳过「输出已存在即完成」短路，执行真正的重下载 */
    val reDownload: Long = 0L,
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
            reDownload = task.reDownload,
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
            reDownload = row.reDownload,
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

    fun markReDownload(id: String, updatedAt: Long) {
        queries.markReDownload(updatedAt, id)
    }

    /** 重新入队时刷新标题/作者/源 URL/元数据，避免复用已过期的 CDN 下载地址。 */
    fun updateMetadata(
        id: String,
        title: String,
        authorName: String,
        sourceUrl: String,
        metadataJson: String?,
        updatedAt: Long,
    ) {
        queries.updateMetadata(title, authorName, sourceUrl, metadataJson, updatedAt, id)
    }

    /** 重新入队时刷新输出路径（文件名模板渲染结果随标题/作者变化） */
    fun updateOutputPaths(id: String, outputPath: String, tempPath: String, updatedAt: Long) {
        queries.updateOutputPaths(outputPath, tempPath, updatedAt, id)
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
