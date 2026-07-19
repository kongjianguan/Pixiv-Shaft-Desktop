package ceui.pixiv.download

import ceui.loxia.GifFrame
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode

internal object UgoiraGifEncoder {
    fun encode(frameDirectory: Path, frames: List<GifFrame>, output: Path) {
        val orderedFrames = frames.mapNotNull { frame ->
            frame.file?.takeIf { it.isNotBlank() }?.let { it to (frame.delay ?: DEFAULT_DELAY_MS) }
        }
        require(orderedFrames.isNotEmpty()) { "Ugoira contains no frames" }

        val writer = ImageIO.getImageWritersBySuffix("gif").asSequence().firstOrNull()
            ?: error("No GIF image writer is available")
        val writeParam = writer.defaultWriteParam
        Files.createDirectories(output.parent)

        try {
            ImageIO.createImageOutputStream(output.toFile()).use { imageOutputStream ->
                requireNotNull(imageOutputStream) { "Cannot open GIF output" }
                writer.output = imageOutputStream
                writer.prepareWriteSequence(null)

                orderedFrames.forEachIndexed { index, (fileName, delayMs) ->
                    val framePath = frameDirectory.resolve(fileName).normalize()
                    require(framePath.parent == frameDirectory || framePath.startsWith(frameDirectory)) {
                        "Unsafe ugoira frame path: $fileName"
                    }
                    val image = ImageIO.read(framePath.toFile())
                        ?: error("Cannot decode ugoira frame: $fileName")
                    try {
                        writer.writeToSequence(
                            IIOImage(
                                image,
                                null,
                                configureFrameMetadata(
                                    writer.getDefaultImageMetadata(
                                        ImageTypeSpecifier.createFromRenderedImage(image),
                                        writeParam,
                                    ),
                                    delayMs,
                                    includeLoopExtension = index == 0,
                                ),
                            ),
                            writeParam,
                        )
                    } finally {
                        image.flush()
                    }
                }
                writer.endWriteSequence()
                imageOutputStream.flush()
            }
        } finally {
            writer.dispose()
        }
    }

    private fun configureFrameMetadata(
        metadata: IIOMetadata,
        delayMs: Int,
        includeLoopExtension: Boolean,
    ): IIOMetadata {
        val root = metadata.getAsTree(IMAGE_METADATA_FORMAT) as IIOMetadataNode
        val control = childOrCreate(root, "GraphicControlExtension")
        control.setAttribute("disposalMethod", "none")
        control.setAttribute("userInputFlag", "FALSE")
        control.setAttribute("transparentColorFlag", "FALSE")
        control.setAttribute("delayTime", ((delayMs.coerceAtLeast(10) + 5) / 10).toString())
        control.setAttribute("transparentColorIndex", "0")
        if (includeLoopExtension) {
            val extensions = childOrCreate(root, "ApplicationExtensions")
            val extension = IIOMetadataNode("ApplicationExtension")
            extension.setAttribute("applicationID", "NETSCAPE")
            extension.setAttribute("authenticationCode", "2.0")
            extension.userObject = byteArrayOf(1, 0, 0)
            extensions.appendChild(extension)
        }
        metadata.setFromTree(IMAGE_METADATA_FORMAT, root)
        return metadata
    }

    private fun childOrCreate(parent: IIOMetadataNode, name: String): IIOMetadataNode {
        for (index in 0 until parent.length) {
            val child = parent.item(index)
            if (child is IIOMetadataNode && child.nodeName == name) return child
        }
        return IIOMetadataNode(name).also(parent::appendChild)
    }

    private const val DEFAULT_DELAY_MS = 100
    private const val IMAGE_METADATA_FORMAT = "javax_imageio_gif_image_1.0"
}
