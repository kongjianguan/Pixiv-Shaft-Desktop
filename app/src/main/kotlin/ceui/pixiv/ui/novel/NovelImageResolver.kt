package ceui.pixiv.ui.novel

import ceui.loxia.NovelImages
import ceui.loxia.WebNovel

/** Resolves embedded novel image tokens to the protected CDN URL in WebNovel. */
object NovelImageResolver {

    fun urlFor(webNovel: WebNovel, token: ContentToken): String? = when (token) {
        is ContentToken.UploadedImage -> webNovel.images?.get(token.imageId.toString())?.urls.preferredUrl()
        is ContentToken.PixivImage -> {
            val pageKey = if (token.pageIndex > 0) "${token.illustId}-${token.pageIndex}" else token.illustId.toString()
            val illust = webNovel.illusts?.get(pageKey)?.illust
                ?: webNovel.illusts?.get(token.illustId.toString())?.illust
            illust?.images?.findMaxSizeUrl()
                ?: illust?.urls?.values?.firstOrNull { !it.isNullOrBlank() }
        }
        else -> null
    }

    private fun Map<String, String>?.preferredUrl(): String? {
        if (this == null) return null
        return listOf("original", NovelImages.Size.Size1200x1200, NovelImages.Size.Size480mw, NovelImages.Size.Size240mw)
            .asSequence()
            .mapNotNull { this[it] }
            .firstOrNull { it.isNotBlank() }
            ?: values.firstOrNull { it.isNotBlank() }
    }
}
