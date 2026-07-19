package ceui.pixiv.store

data class BrowseHistoryItem(
    val contentType: String,
    val targetId: Long,
    val payloadJson: String,
    val viewedAt: Long,
)

class BrowseHistoryStore(
    private val queries: BrowseHistoryQueries,
) {

    init {
        queries.migrateLegacyIllustHistory()
    }

    fun upsert(
        contentType: String,
        targetId: Long,
        payloadJson: String,
        viewedAt: Long = System.currentTimeMillis(),
    ) {
        if (targetId <= 0L || payloadJson.isBlank()) return
        queries.upsertBrowseHistory(contentType, targetId, payloadJson, viewedAt)
    }

    fun list(
        contentType: String,
        query: String? = null,
        limit: Long = DEFAULT_PAGE_SIZE,
        offset: Long = 0L,
    ): List<BrowseHistoryItem> {
        val normalized = query?.trim().orEmpty()
        val rows = if (normalized.isEmpty()) {
            queries.selectBrowseHistoryByType(contentType, limit, offset).executeAsList()
        } else {
            queries.searchBrowseHistoryByType(contentType, normalized, limit, offset).executeAsList()
        }
        return rows.map { row ->
            BrowseHistoryItem(
                contentType = row.contentType,
                targetId = row.targetId,
                payloadJson = row.payloadJson,
                viewedAt = row.viewedAt,
            )
        }
    }

    fun all(): List<BrowseHistoryItem> = queries.selectAllBrowseHistory()
        .executeAsList()
        .map { row ->
            BrowseHistoryItem(
                contentType = row.contentType,
                targetId = row.targetId,
                payloadJson = row.payloadJson,
                viewedAt = row.viewedAt,
            )
        }

    fun delete(contentType: String, targetId: Long) {
        queries.deleteBrowseHistory(contentType, targetId)
    }

    fun deleteType(contentType: String) {
        queries.deleteBrowseHistoryByType(contentType)
    }

    fun clear() {
        queries.clearBrowseHistory()
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 30L
    }
}
