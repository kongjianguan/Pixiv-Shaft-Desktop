package ceui.pixiv.poc

import ceui.pixiv.net.NettyQuicInterceptor
import ceui.pixiv.net.PixivHosts
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

fun main() {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(NettyQuicInterceptor())
        .build()

    val req = Request.Builder()
        .url("https://${PixivHosts.APP_API_HOST}${PixivHosts.WALKTHROUGH_PATH}")
        .build()

    client.newCall(req).execute().use { resp ->
        println("HTTP ${resp.code}  proto=${resp.protocol}")
        require(resp.code == 200) { "反墙 POC 失败：HTTP ${resp.code}" }

        val body = resp.body?.string() ?: error("空响应体")
        val json = JsonParser.parseString(body).asJsonObject
        val illusts = json.getAsJsonArray("illusts") ?: error("无 illusts 字段")
        println("illusts count = ${illusts.size()}")
        require(illusts.size() > 0) { "illusts 为空" }

        val first = illusts[0].asJsonObject
        println("first illust id=${first.get("id")} title=${first.get("title")}")
        println("POC GATE PASSED")
    }
}
