package ceui.pixiv.net.dns

import ceui.pixiv.net.PixivHosts
import ceui.pixiv.net.abstractions.Logger
import ceui.pixiv.net.abstractions.Settings
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class HttpDns(
    private val settings: Settings,
    private val logger: Logger,
    private val systemDns: Dns = Dns.SYSTEM,
) : Dns {

    private val dohEndpoints = arrayOf(
        CloudFlareDNSService.CLOUDFLARE_DOH_POINT,
        CloudFlareDNSService.DNSSB_DOH_POINT,
    )

    private val domains = arrayOf(
        "app-api.pixiv.net",
        "oauth.secure.pixiv.net",
    )

    private val fallbackApiIps: List<String> = PixivHosts.CF_IPS

    private val fallbackImageIps = arrayOf(
        "210.140.139.134",
        "210.140.139.133",
        "210.140.139.131",
    )

    private val resolvedHosts: MutableMap<String, List<InetAddress>> = ConcurrentHashMap()
    private val fallbackApiAddresses: List<InetAddress>
    private val fallbackImageAddresses: List<InetAddress>

    init {
        fallbackApiAddresses = fallbackApiIps.mapNotNull { ip ->
            try { InetAddress.getByName(ip) } catch (e: UnknownHostException) { null }
        }
        fallbackImageAddresses = fallbackImageIps.mapNotNull { ip ->
            try { InetAddress.getByName(ip) } catch (e: UnknownHostException) { null }
        }
        if (settings.isUseSecureDns) {
            for (domain in domains) {
                resolveViaDoH(domain, 0)
            }
        }
    }

    fun invalidate() {
        resolvedHosts.clear()
        if (settings.isUseSecureDns) {
            for (domain in domains) {
                resolveViaDoH(domain, 0)
            }
        }
    }

    private fun resolveViaDoH(hostname: String, endpointIndex: Int) {
        if (endpointIndex >= dohEndpoints.size) {
            logger.d("HttpDns all DoH failed for $hostname, will use fallback IPs")
            return
        }
        try {
            val service = CloudFlareDNSService.invoke(dohEndpoints[endpointIndex])
            service.query(hostname, "A").enqueue(object : retrofit2.Callback<CloudFlareDNSResponse> {
                override fun onResponse(
                    call: retrofit2.Call<CloudFlareDNSResponse>,
                    response: retrofit2.Response<CloudFlareDNSResponse>,
                ) {
                    val body = response.body()
                    if (body != null && !body.Answer.isNullOrEmpty()) {
                        val addresses = mutableListOf<InetAddress>()
                        for (answer in body.Answer) {
                            try {
                                if (answer.type == 1) {
                                    addresses.add(InetAddress.getByName(answer.data))
                                }
                            } catch (ignored: Exception) {
                            }
                        }
                        if (addresses.isNotEmpty()) {
                            resolvedHosts[hostname] = addresses
                            logger.d("HttpDns resolved $hostname -> $addresses")
                        } else {
                            resolveViaDoH(hostname, endpointIndex + 1)
                        }
                    } else {
                        resolveViaDoH(hostname, endpointIndex + 1)
                    }
                }

                override fun onFailure(call: retrofit2.Call<CloudFlareDNSResponse>, t: Throwable) {
                    logger.d("HttpDns DoH failed for $hostname: ${t.message}")
                    resolveViaDoH(hostname, endpointIndex + 1)
                }
            })
        } catch (e: Exception) {
            resolveViaDoH(hostname, endpointIndex + 1)
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val start = System.nanoTime()
        if (settings.isUseSecureDns) {
            val cached = resolvedHosts[hostname]
            if (!cached.isNullOrEmpty()) {
                val elapsed = (System.nanoTime() - start) / 1_000_000
                logger.d("HttpDns lookup $hostname → DoH cached $cached [$elapsed ms]")
                return cached
            }
        } else {
            try {
                val systemResult = systemDns.lookup(hostname)
                if (systemResult.isNotEmpty()) {
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    logger.d("HttpDns lookup $hostname → system $systemResult [$elapsed ms]")
                    return systemResult
                }
            } catch (ignored: UnknownHostException) {
            }
        }
        val result: List<InetAddress>
        val source: String
        if (hostname.endsWith("pximg.net")) {
            result = fallbackImageAddresses
            source = "fallback-image"
        } else {
            result = fallbackApiAddresses
            source = "fallback-api"
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000
        logger.d("HttpDns lookup $hostname → $source $result [$elapsed ms]")
        return result
    }
}
