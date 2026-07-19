package ceui.pixiv.download

import ceui.loxia.GifFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class UgoiraGifEncoderTest {
    @Test
    fun `encodes all ugoira frames into animated gif`(@TempDir directory: Path) {
        val frameDirectory = directory.resolve("frames")
        Files.createDirectories(frameDirectory)
        writeFrame(frameDirectory.resolve("000000.jpg"), Color.RED)
        writeFrame(frameDirectory.resolve("000001.jpg"), Color.BLUE)

        val output = directory.resolve("ugoira.gif")
        UgoiraGifEncoder.encode(
            frameDirectory = frameDirectory,
            frames = listOf(
                GifFrame(file = "000000.jpg", delay = 120),
                GifFrame(file = "000001.jpg", delay = 80),
            ),
            output = output,
        )

        assertTrue(Files.size(output) > 0L)
        val reader = ImageIO.getImageReadersBySuffix("gif").asSequence().first()
        ImageIO.createImageInputStream(output.toFile()).use { input ->
            reader.input = input
            assertEquals(2, reader.getNumImages(true))
        }
        reader.dispose()
    }

    private fun writeFrame(path: Path, color: Color) {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                image.setRGB(x, y, color.rgb)
            }
        }
        ImageIO.write(image, "jpg", path.toFile())
    }
}
