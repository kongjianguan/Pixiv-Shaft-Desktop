package ceui.pixiv.net.impl

import ceui.pixiv.net.abstractions.*

object StdoutLogger : Logger {
    override fun d(msg: String) = println("[D] $msg")
    override fun w(msg: String, t: Throwable?) = println("[W] $msg").also { t?.printStackTrace() }
    override fun e(msg: String, t: Throwable?) = println("[E] $msg").also { t?.printStackTrace() }
}

class DefaultLanguageProvider : LanguageProvider {
    override fun acceptLanguage() = "zh"
    override fun appAcceptLanguage() = "zh-Hans"
}
