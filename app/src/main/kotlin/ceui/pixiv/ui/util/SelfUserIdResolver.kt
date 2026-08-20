package ceui.pixiv.ui.util

import ceui.pixiv.net.api.Client
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 进程级「当前登录用户 id」解析器：多个页面（详情评论/收藏标签/关注列表）共用
 * 一次 getSelfProfile 请求，并发调用串行等待同一结果。
 *
 * 解析失败向上抛、无效 id（<= 0）不缓存——下一次调用会重新请求；否则服务端
 * 偶发返回 0（Pixiv 已知怪癖）会把「删除评论」等操作永久卡死到重启。
 * 退出登录/切换账号时必须调 [clear]，避免缓存串到下一个账号。
 */
object SelfUserIdResolver {

    private val mutex = Mutex()

    @Volatile
    private var resolved: Long = 0L

    /** 解析当前登录用户 id；[fetch] 返回 <= 0 时视为未解析成功，不缓存。 */
    suspend fun resolve(fetch: suspend () -> Long): Long {
        if (resolved > 0L) return resolved
        return mutex.withLock {
            if (resolved > 0L) return resolved
            val id = fetch()
            if (id > 0L) resolved = id
            resolved
        }
    }

    fun clear() {
        resolved = 0L
    }
}

suspend fun Client.resolveSelfUserId(): Long = SelfUserIdResolver.resolve {
    val self = appApi.getSelfProfile()
    self.profile.user_id.takeIf { it > 0 } ?: self.profile.id
}
