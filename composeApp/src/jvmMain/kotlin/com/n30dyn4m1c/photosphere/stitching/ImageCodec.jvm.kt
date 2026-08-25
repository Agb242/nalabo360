package com.n30dyn4m1c.photosphere.stitching

import okio.FileSystem
import okio.Path
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.stream.ImageOutputStream
import kotlin.math.roundToInt

/**
 * The JVM half of [ImageCodec], backed by ImageIO.
 *
 * The JVM target runs the pipeline's own unit tests, so this decodes the whole
 * file and downsamples with the shared [ImageMath.resizeArea] — no need for a
 * streaming decoder here, and it keeps the resize code under test for free.
 */
internal class JvmImageCodec : ImageCodec {

    override fun decodeJpeg(path: Path, maxLongEdge: Int): RgbImage? = runCatching {
        val encoded = FileSystem.SYSTEM.read(path) { readByteArray() }
        val buffered = ImageIO.read(ByteArrayInputStream(encoded)) ?: return null
        val image = rgbFrom(buffered)
        val longEdge = maxOf(image.width, image.height)
        if (longEdge > maxLongEdge) {
            val scale = maxLongEdge.toDouble() / longEdge
            ImageMath.resizeArea(
                image,
                (image.width * scale).roundToInt().coerceAtLeast(1),
                (image.height * scale).roundToInt().coerceAtLeast(1),
            )
        } else {
            image
        }
    }.getOrNull()

    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray? = runCatching {
        val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB)
        val bytes = image.bytes
        val pixels = IntArray(image.pixelCount)
        for (i in pixels.indices) {
            val b = i * 3
            pixels[i] = ((bytes[b].toInt() and 0xff) shl 16) or
                ((bytes[b + 1].toInt() and 0xff) shl 8) or
                (bytes[b + 2].toInt() and 0xff)
        }
        buffered.setRGB(0, 0, image.width, image.height, pixels, 0, image.width)

        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val params = writer.defaultWriteParam
        params.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
        params.compressionQuality = quality.coerceIn(0, 100) / 100f
        val out = ByteArrayOutputStream()
        val stream = ImageIO.createImageOutputStream(out) as ImageOutputStream
        writer.output = stream
        writer.write(null, IIOImage(buffered, null, null), params)
        writer.dispose()
        stream.close()
        out.toByteArray()
    }.getOrNull()

    private fun rgbFrom(buffered: BufferedImage): RgbImage {
        val width = buffered.width
        val height = buffered.height
        // TYPE_INT_RGB reads are fast paths; anything else still works through
        // getRGB's colour-model conversion.
        if (buffered.type != BufferedImage.TYPE_INT_RGB) {
            val plain = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = plain.createGraphics()
            graphics.drawImage(buffered, 0, 0, null)
            graphics.dispose()
            return rgbFrom(plain)
        }
        val pixels = IntArray(width * height)
        buffered.getRGB(0, 0, width, height, pixels, 0, width)
        val bytes = ByteArray(width * height * 3)
        var o = 0
        for (pixel in pixels) {
            bytes[o] = ((pixel shr 16) and 0xff).toByte()
            bytes[o + 1] = ((pixel shr 8) and 0xff).toByte()
            bytes[o + 2] = (pixel and 0xff).toByte()
            o += 3
        }
        return RgbImage(width, height, bytes)
    }
}

actual fun platformImageCodec(): ImageCodec = JvmImageCodec()
