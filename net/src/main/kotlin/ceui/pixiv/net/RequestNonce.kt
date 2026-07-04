package ceui.pixiv.net

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RequestNonce(
    val xClientTime: String,
    val xClientHash: String,
) {
    companion object {
        private const val SECRET = "28c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c"
        private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZZZZZ", Locale.US)

        fun build(): RequestNonce = forTime(format.format(Date()))

        fun forTime(time: String): RequestNonce =
            RequestNonce(time, md5("$time$SECRET"))
    }
}

fun md5(plainText: String): String {
    val md = MessageDigest.getInstance("MD5")
    val b = md.digest(plainText.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder()
    for (byte in b) {
        var i = byte.toInt()
        if (i < 0) i += 256
        if (i < 16) sb.append('0')
        sb.append(Integer.toHexString(i))
    }
    return sb.toString()
}
