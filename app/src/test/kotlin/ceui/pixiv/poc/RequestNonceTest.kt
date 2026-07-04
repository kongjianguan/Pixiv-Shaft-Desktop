package ceui.pixiv.poc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RequestNonceTest {
    @Test
    fun `md5 of known input matches reference`() {
        // 与现 app ceui/loxia/PixivHeaders.kt 同算法；用固定输入校验
        // MD5("hello") = 5d41402abc4b2a76b9719d911017c592
        assertEquals("5d41402abc4b2a76b9719d911017c592", md5("hello"))
    }

    @Test
    fun `build produces hash from time plus secret`() {
        val nonce = RequestNonce.forTime("2026-07-04T12:00:00+00:00")
        // MD5("2026-07-04T12:00:00+00:0028c1fdd170a5204386cb1313c7077b34f83e4aaf4aa829ce78c231e05b0bae2c")
        // Step 4 回填：经实现 md5() 实算 + macOS `md5 -s` 双校验
        assertEquals("d541f1962d1753aa5e16f76d82906441", nonce.xClientHash)
    }

    @Test
    fun `build default uses current time format`() {
        val nonce = RequestNonce.build()
        assertTrue(nonce.xClientTime.contains("T"))
        assertTrue(nonce.xClientHash.length == 32)
    }
}
