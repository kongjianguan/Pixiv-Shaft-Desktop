package ceui.pixiv.ui.util

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SelfUserIdResolverTest {

    @Test
    fun `invalid zero id is not cached and fetch is retried`() = runBlocking {
        SelfUserIdResolver.clear()
        val fetches = AtomicInteger(0)
        val fetch = { fetches.incrementAndGet(); 0L }

        assertEquals(0L, SelfUserIdResolver.resolve(fetch))
        assertEquals(0L, SelfUserIdResolver.resolve(fetch))

        // 无效 id 不缓存：每次调用都重新请求
        assertEquals(2, fetches.get())
    }

    @Test
    fun `valid id is cached across resolves`() = runBlocking {
        SelfUserIdResolver.clear()
        val fetches = AtomicInteger(0)
        val fetch = { fetches.incrementAndGet(); 7L }

        assertEquals(7L, SelfUserIdResolver.resolve(fetch))
        assertEquals(7L, SelfUserIdResolver.resolve(fetch))

        assertEquals(1, fetches.get())
    }

    @Test
    fun `failed fetch is retried on next resolve`() = runBlocking {
        SelfUserIdResolver.clear()
        val fetches = AtomicInteger(0)
        val fetch = {
            if (fetches.incrementAndGet() == 1) throw IOException("network down")
            5L
        }

        val failure = runCatching { SelfUserIdResolver.resolve(fetch) }.exceptionOrNull()
        assertEquals(IOException::class.java, failure?.javaClass)
        assertEquals(5L, SelfUserIdResolver.resolve(fetch))
        assertEquals(2, fetches.get())
    }

    @Test
    fun `clear forgets cached id`() = runBlocking {
        SelfUserIdResolver.clear()
        val fetches = AtomicInteger(0)
        val fetch = { fetches.incrementAndGet(); 7L }

        SelfUserIdResolver.resolve(fetch)
        SelfUserIdResolver.clear()
        SelfUserIdResolver.resolve(fetch)

        assertEquals(2, fetches.get())
    }
}
