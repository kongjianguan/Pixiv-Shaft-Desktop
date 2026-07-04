package ceui.pixiv.net.dns

import okhttp3.OkHttpClient
import okhttp3.Protocol
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface CloudFlareDNSService {

    @Headers("Accept: application/dns-json")
    @GET("/dns-query")
    fun query(
        @Query("name") name: String?,
        @Query("type") type: String = "A"
    ): Call<CloudFlareDNSResponse>

    companion object {

        val CLOUDFLARE_DOH_POINT: String = "https://1.0.0.1/"
        val DNSSB_DOH_POINT: String = "https://185.222.222.222/"
        val ALIDNS_DOH_POINT: String = "https://223.5.5.5/"

        private val serviceCache = ConcurrentHashMap<String, CloudFlareDNSService>()

        private val dohClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
        }

        operator fun invoke(point: String): CloudFlareDNSService {
            return serviceCache.getOrPut(point) {
                Retrofit.Builder()
                    .baseUrl(point)
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(dohClient)
                    .build()
                    .create(CloudFlareDNSService::class.java)
            }
        }
    }
}
