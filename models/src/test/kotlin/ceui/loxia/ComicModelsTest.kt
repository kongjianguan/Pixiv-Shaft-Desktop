package ceui.loxia

import com.google.gson.Gson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComicModelsTest {
    @Test
    fun `parses comic top response field names`() {
        val response = Gson().fromJson(
            """
            {
              "data": {
                "banners": [{"id": 7, "image_url": "banner.jpg", "url": "https://comic.pixiv.net/works/7"}],
                "recent_updated_official_works": [{"id": 8, "title": "作品", "stories_count": 12, "like_count": 33}]
              }
            }
            """.trimIndent(),
            ComicTopResponse::class.java,
        )

        assertEquals(7L, response.data?.banners?.single()?.id)
        assertEquals("作品", response.data?.recent_updated_official_works?.single()?.title)
        assertEquals(12, response.data?.recent_updated_official_works?.single()?.stories_count)
    }
}
