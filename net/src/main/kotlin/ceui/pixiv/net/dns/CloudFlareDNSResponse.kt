package ceui.pixiv.net.dns

data class CloudFlareDNSResponse(
    val Answer: List<DNSAnswer>?
) {
    data class DNSAnswer(
        val `data`: String,
        val type: Int
    )
}
