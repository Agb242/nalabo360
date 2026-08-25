package com.n30dyn4m1c.photosphere.stitching

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import okio.Path
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageAlphaInfo.kCGImageAlphaPremultipliedLast
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixels are 3-channel RGB in, 4-channel RGBA through Core Graphics. */
private const val RGB_CHANNELS = 3
private const val RGBA_PIXEL_BYTES = 4

/**
 * The iOS half of [ImageCodec]: ImageIO decodes the JPEG straight into a
 * byte-backed RGBA bitmap context — no `UIImage` decode cache, no implicit
 * orientation application (the stitcher reads the EXIF tag itself) — and the
 * finished pixels go back out through Core Graphics plus
 * `UIImageJPEGRepresentation`.
 *
 * Both directions pass through a plain `ByteArray`, which is exactly the shape
 * [RgbImage] speaks.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosImageCodec : ImageCodec {

    override fun decodeJpeg(path: Path, maxLongEdge: Int): RgbImage? {
        // UIImage decodes straight from the file; its CGImage is the raw pixel
        // buffer — orientation metadata stays separate, which is exactly what
        // the stitcher expects when it reads EXIF itself.
        val uiImage = UIImage.imageWithContentsOfFile(path.toString()) ?: return null
        val cgImage = uiImage.CGImage ?: return null

        val srcWidth = CGImageGetWidth(cgImage).toInt()
        val srcHeight = CGImageGetHeight(cgImage).toInt()
        if (srcWidth <= 0 || srcHeight <= 0) return null

        // Power-of-two subsampling is a BitmapFactory luxury; Core Graphics
        // scales while drawing, which lands on the cap in one pass anyway.
        val scale = min(1.0, maxLongEdge.toDouble() / max(srcWidth, srcHeight))
        val width = max(1, (srcWidth * scale).roundToInt())
        val height = max(1, (srcHeight * scale).roundToInt())

        val rgba = ByteArray(width * height * RGBA_PIXEL_BYTES)
        val context = rgba.usePinned { pinned ->
            CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = BITS_PER_COMPONENT,
                bytesPerRow = (width * RGBA_PIXEL_BYTES).toULong(),
                space = CGColorSpaceCreateDeviceRGB(),
                // No byte-order flag: Quartz's default ordering is big-endian,
                // which is what the removed kCGBitmapByteOrder32Big macro
                // spelled (macros do not cross into the Kotlin bindings).
                bitmapInfo = kCGImageAlphaPremultipliedLast.value,
            )
        } ?: return null

        // Bitmap contexts put the origin at bottom-left; the pipeline's
        // row-major buffers are top-down, so flip before drawing.
        CGContextTranslateCTM(context, 0.0, height.toDouble())
        CGContextScaleCTM(context, 1.0, -1.0)
        CGContextDrawImage(
            context,
            CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
            cgImage,
        )

        return RgbImage(width, height, rgbaToRgb(rgba))
    }

    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray? {
        val width = image.width
        val height = image.height
        val rgba = ByteArray(width * height * RGBA_PIXEL_BYTES)
        var src = 0
        var dst = 0
        while (src < image.bytes.size) {
            rgba[dst] = image.bytes[src]
            rgba[dst + 1] = image.bytes[src + 1]
            rgba[dst + 2] = image.bytes[src + 2]
            rgba[dst + 3] = OPAQUE_ALPHA
            src += RGB_CHANNELS
            dst += RGBA_PIXEL_BYTES
        }

        val context = rgba.usePinned { pinned ->
            CGBitmapContextCreate(
                data = pinned.addressOf(0),
                width = width.toULong(),
                height = height.toULong(),
                bitsPerComponent = BITS_PER_COMPONENT,
                bytesPerRow = (width * RGBA_PIXEL_BYTES).toULong(),
                space = CGColorSpaceCreateDeviceRGB(),
                bitmapInfo = kCGImageAlphaPremultipliedLast.value,
            )
        } ?: return null
        val cgImage = CGBitmapContextCreateImage(context) ?: return null

        val data = UIImageJPEGRepresentation(
            /* image = */ UIImage.imageWithCGImage(cgImage),
            /* compressionQuality = */ quality.coerceIn(0, 100) / 100.0,
        ) ?: return null
        return data.toByteArray()
    }

    private companion object {
        val BITS_PER_COMPONENT: ULong = 8uL
        const val OPAQUE_ALPHA = 0xff.toByte()
    }
}

actual fun platformImageCodec(): ImageCodec = IosImageCodec()

/** Drops the alpha plane a bitmap context insists on drawing with. */
private fun rgbaToRgb(rgba: ByteArray): ByteArray {
    val rgb = ByteArray(rgba.size / RGBA_PIXEL_BYTES * RGB_CHANNELS)
    var src = 0
    var dst = 0
    while (dst < rgb.size) {
        rgb[dst] = rgba[src]
        rgb[dst + 1] = rgba[src + 1]
        rgb[dst + 2] = rgba[src + 2]
        src += RGBA_PIXEL_BYTES
        dst += RGB_CHANNELS
    }
    return rgb
}

/** Copies an `NSData` into a Kotlin array — the bridge hands out a pointer. */
@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val result = ByteArray(length.toInt())
    result.usePinned { pinned ->
        memcpy(pinned.addressOf(0), this@toByteArray.bytes, length)
    }
    return result
}
