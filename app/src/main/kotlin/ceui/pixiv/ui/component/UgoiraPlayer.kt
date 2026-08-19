package ceui.pixiv.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import ceui.loxia.GifFrame
import ceui.pixiv.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jetbrains.skia.Image as SkiaImage
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Displays an animated ugoira illustration by downloading the zip,
 * extracting frames, and cycling through them at the specified delays.
 */
@Composable
fun UgoiraPlayer(
    zipUrl: String,
    frames: List<GifFrame>,
    modifier: Modifier = Modifier
) {
    var bitmaps by remember(zipUrl) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }

    // Download + unzip + decode
    LaunchedEffect(zipUrl) {
        try {
            val decoded = withContext(Dispatchers.IO) {
                val client = AppContainer.imageClient
                val request = Request.Builder().url(zipUrl).build()
                client.newCall(request).execute().use { resp ->
                    val bytes = resp.body?.bytes() ?: return@withContext emptyList()
                    decodeUgoiraZip(bytes)
                }
            }
            bitmaps = decoded
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Download/decode failed — bitmaps stays empty, shows nothing
        }
    }

    // Animate
    val currentBitmap = bitmaps.getOrNull(currentIndex)
    if (currentBitmap != null) {
        LaunchedEffect(bitmaps) {
            var i = 0
            while (true) {
                val delayMs = frames.getOrNull(i)?.delay ?: 100
                try {
                    withContext(Dispatchers.Default) {
                        delay(delayMs.toLong())
                    }
                } catch (e: CancellationException) {
                    throw e
                }
                i = (i + 1) % bitmaps.size
                currentIndex = i
            }
        }
        Image(
            bitmap = currentBitmap,
            contentDescription = "Ugoira animation",
            modifier = modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(modifier = modifier.fillMaxWidth())
    }
}

private fun decodeUgoiraZip(zipBytes: ByteArray): List<ImageBitmap> {
    val entries = mutableListOf<Pair<String, ByteArray>>()
    ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && (entry.name.endsWith(".jpg") || entry.name.endsWith(".png"))) {
                val data = zis.readBytes()
                entries.add(entry.name to data)
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
    }
    val sortedEntries = entries.sortedBy { it.first }
    return sortedEntries.map { (_, data) ->
        SkiaImage.makeFromEncoded(data).toComposeImageBitmap()
    }
}
