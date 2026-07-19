package ceui.pixiv.ui.history

import com.google.gson.Gson
import ceui.loxia.Illust
import ceui.loxia.Novel
import ceui.loxia.User
import ceui.pixiv.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object BrowseHistoryRecorder {

    private val gson = Gson()

    suspend fun recordIllust(illust: Illust) {
        record("illust", illust.id, illust)
    }

    suspend fun recordNovel(novel: Novel) {
        record("novel", novel.id, novel)
    }

    suspend fun recordUser(user: User) {
        val normalized = if (user.id > 0L) user else user.copy(id = user.user_id)
        record("user", normalized.id, normalized)
    }

    private suspend fun record(contentType: String, targetId: Long, payload: Any) {
        if (!AppContainer.settingsStore.saveBrowseHistory) return
        withContext(Dispatchers.IO) {
            AppContainer.database.browseHistory.upsert(
                contentType = contentType,
                targetId = targetId,
                payloadJson = gson.toJson(payload),
            )
        }
    }
}
