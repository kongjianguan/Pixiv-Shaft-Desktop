package ceui.pixiv.testutil

import ceui.pixiv.net.abstractions.LanguageProvider
import ceui.pixiv.net.abstractions.Settings
import ceui.pixiv.net.abstractions.TokenRefresher
import ceui.pixiv.net.abstractions.TokenStore
import ceui.pixiv.net.api.API
import ceui.pixiv.net.api.Client
import ceui.pixiv.net.impl.StdoutLogger
import ceui.pixiv.store.InMemoryKvStore
import ceui.pixiv.store.SettingsStore
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** 用假 API 构造可注入 ScreenModel 的 Client（网络层全部走内存实现）。 */
fun fakeClient(api: API): Client = Client(
    settings = object : Settings {
        override val isDirectConnect: Boolean get() = false
        override val isUseSecureDns: Boolean get() = false
        override val imageHostMode: Int get() = 0
        override val customImageHost: String get() = ""
    },
    tokenStore = object : TokenStore {
        override val isLoggedIn: Boolean get() = false
        override fun getAccessToken(): String? = null
        override fun getBearerToken(): String? = null
        override fun getRefreshToken(): String? = null
        override fun saveTokens(accessToken: String?, refreshToken: String?, userJson: String?) {}
        override fun clear() {}
    },
    refresher = object : TokenRefresher {
        override suspend fun refreshAccessToken(currentAccessToken: String?): String? = null
    },
    lang = object : LanguageProvider {
        override fun acceptLanguage(): String = "en"
        override fun appAcceptLanguage(): String = "en"
    },
    logger = StdoutLogger,
    api = api,
)

fun fakeSettingsStore(): SettingsStore = SettingsStore(InMemoryKvStore())

/** 按方法名分发的动态代理假 API。 */
fun fakeApi(handler: (methodName: String, args: Array<out Any?>) -> Any?): API {
    val loader = API::class.java.classLoader
    return Proxy.newProxyInstance(loader, arrayOf(API::class.java)) { _, method, args ->
        handler(method.name, args ?: emptyArray())
    } as API
}

/** 让动态代理支持 suspend 方法：resume continuation 并返回 COROUTINE_SUSPENDED */
@Suppress("UNCHECKED_CAST")
fun resumeSuspend(args: Array<out Any?>, value: Any?): Any {
    val continuation = args.last() as Continuation<Any?>
    continuation.resumeWith(Result.success(value))
    return COROUTINE_SUSPENDED
}

/** 让 suspend 方法以异常结束（模拟网络失败） */
@Suppress("UNCHECKED_CAST")
fun resumeSuspendError(args: Array<out Any?>, error: Throwable): Any {
    val continuation = args.last() as Continuation<Any?>
    continuation.resumeWith(Result.failure(error))
    return COROUTINE_SUSPENDED
}
