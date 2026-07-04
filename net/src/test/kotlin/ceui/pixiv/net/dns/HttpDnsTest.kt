package ceui.pixiv.net.dns

import ceui.pixiv.net.impl.InMemorySettings
import ceui.pixiv.net.impl.StdoutLogger
import okhttp3.Dns
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.UnknownHostException

class HttpDnsTest {

    private val failingDns = object : Dns {
        override fun lookup(hostname: String) = throw UnknownHostException("test")
    }
    private val settings = InMemorySettings(isUseSecureDns = false)

    @Test
    fun `DoH off + system DNS fails → pximg fallback IPs`() {
        val dns = HttpDns(settings, StdoutLogger, failingDns)
        val addresses = dns.lookup("i.pximg.net")
        assertFalse(addresses.isEmpty(), "should return fallback IPs, not empty")
        assertTrue(
            addresses.all { it.hostAddress!!.startsWith("210.140.139.") },
            "pximg.net should resolve to 210.140.139.x fallback, got $addresses",
        )
    }

    @Test
    fun `DoH off + system DNS fails → app-api CF fallback IPs`() {
        val dns = HttpDns(settings, StdoutLogger, failingDns)
        val addresses = dns.lookup("app-api.pixiv.net")
        assertFalse(addresses.isEmpty(), "should return fallback IPs, not empty")
        val ips = addresses.map { it.hostAddress }
        assertTrue(
            ips.contains("104.18.42.239") || ips.contains("172.64.145.17"),
            "app-api should resolve to CF fallback IPs, got $ips",
        )
    }

    @Test
    fun `DoH off + system DNS succeeds → returns system result (issue 616)`() {
        val systemIps = listOf(InetAddress.getByName("1.2.3.4"))
        val systemDns = object : Dns {
            override fun lookup(hostname: String) = systemIps
        }
        val dns = HttpDns(settings, StdoutLogger, systemDns)
        assertEquals(systemIps, dns.lookup("app-api.pixiv.net"))
    }
}
