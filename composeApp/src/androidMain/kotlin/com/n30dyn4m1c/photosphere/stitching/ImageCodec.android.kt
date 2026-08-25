package com.n30dyn4m1c.photosphere.stitching

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okio.Path
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * The platform half of [ImageCodec]: `BitmapFactory` for the decode — its
 * power-of-two subsampling keeps a 12-megapixel still from ever existing whole
 * in memory — and `Bitmap.compress` for the encode.
 *
 * EXIF rotation is deliberately *not* applied here; [PhotoSphereStitcher]
 * reads the tag with [JpegOrientation] and rotates explicitly, so the
 * transposed-frame fallback keeps working when a tag was lost.
 */
internal class AndroidImageCodec : ImageCodec {

    override fun decodeJpeg(path: Path, maxLongEdge: Int): RgbImage? {
        val file = File(path.toString())

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
            // getPixels accepts any config; ARGB_8888 avoids RGB_565 banding.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var decoded = BitmapFactory.decodeFile(file.path, options) ?: return null

        // The power-of-two step can undershoot: 4032px sampled by 4 lands at
        // 1008 > 1024? No — but odd sensor sizes can leave the long edge over
        // the limit by a few pixels. Scale exactly once rather than carry it.
        val longEdge = maxOf(decoded.width, decoded.height)
        if (longEdge > maxLongEdge) {
            val scale = maxLongEdge.toDouble() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * scale).roundToInt().coerceAtLeast(1),
                (decoded.height * scale).roundToInt().coerceAtLeast(1),
                /* filter = */ true,
            )
            decoded.recycle()
            decoded = scaled
        }

        try {
            val width = decoded.width
            val height = decoded.height
            val pixels = IntArray(width * height)
            decoded.getPixels(pixels, 0, width, 0, 0, width, height)
            val bytes = ByteArray(width * height * 3)
            var o = 0
            for (pixel in pixels) {
                bytes[o] = ((pixel shr 16) and 0xff).toByte()
                bytes[o + 1] = ((pixel shr 8) and 0xff).toByte()
                bytes[o + 2] = (pixel and 0xff).toByte()
                o += 3
            }
            return RgbImage(width, height, bytes)
        } finally {
            decoded.recycle()
        }
    }

    override fun encodeJpeg(image: RgbImage, quality: Int): ByteArray? = runCatching {
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            val pixels = IntArray(image.pixelCount)
            val bytes = image.bytes
            for (i in pixels.indices) {
                val b = i * 3
                pixels[i] = 0xff000000.toInt() or
                    ((bytes[b].toInt() and 0xff) shl 16) or
                    ((bytes[b + 1].toInt() and 0xff) shl 8) or
                    (bytes[b + 2].toInt() and 0xff)
            }
            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(0, 100), out)) {
                return null
            }
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }.getOrNull()
}

actual fun platformImageCodec(): ImageCodec = AndroidImageCodec()
