package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The image operations the pipeline needs, implemented directly on [RgbImage]
 * buffers.
 *
 * On Android these used to be calls into OpenCV (`Imgproc.resize`, `remap`,
 * `GaussianBlur`, `Core.rotate`). They are reimplemented here, in pure Kotlin,
 * so the whole stitching pipeline runs unchanged on iOS — where there is no
 * OpenCV — and on both platforms produces bit-identical geometry. The kernels
 * follow the same semantics as their OpenCV counterparts: INTER_AREA is a true
 * area average, INTER_LINEAR is bilinear, the Gaussian uses reflect-101 borders
 * and `remap` paints a constant black wherever a map coordinate leaves the
 * source (which is how the renderer encodes "behind the camera").
 *
 * None of these allocate beyond their outputs, and the hot loops index flat
 * arrays only — the access pattern that made the original Mat pipeline fast.
 */
internal object ImageMath {

    // ---------------------------------------------------------------- rotation

    /**
     * Rotates [source] by [degrees] clockwise. Only multiples of 90 are
     * supported — the values EXIF orientations and the portrait fallback use;
     * anything else returns [source] itself, like the old `Core.rotate` path.
     */
    fun rotateCw(source: RgbImage, degrees: Int): RgbImage = when (((degrees % 360) + 360) % 360) {
        90 -> {
            val out = RgbImage.zeros(source.height, source.width)
            val b = out.bytes
            val s = source.bytes
            var i = 0
            for (y in 0 until source.height) {
                for (x in 0 until source.width) {
                    // Destination of (x, y) under a 90° CW turn is (H-1-y, x).
                    val o = (x * source.height + (source.height - 1 - y)) * 3
                    b[o] = s[i]; b[o + 1] = s[i + 1]; b[o + 2] = s[i + 2]
                    i += 3
                }
            }
            out
        }

        180 -> {
            val out = RgbImage.zeros(source.width, source.height)
            val b = out.bytes
            val s = source.bytes
            val n = source.pixelCount
            for (i in 0 until n) {
                val o = (n - 1 - i) * 3
                b[o] = s[i * 3]; b[o + 1] = s[i * 3 + 1]; b[o + 2] = s[i * 3 + 2]
            }
            out
        }

        270 -> rotateCw(rotateCw(source, 180), 90)

        else -> source
    }

    /** Mirrors horizontally — EXIF orientation 2. */
    fun flipH(source: RgbImage): RgbImage {
        val out = RgbImage.zeros(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                copyPixel(
                    source, x, y,
                    out, source.width - 1 - x, y,
                )
            }
        }
        return out
    }

    /** Mirrors vertically — EXIF orientation 4. */
    fun flipV(source: RgbImage): RgbImage {
        val out = RgbImage.zeros(source.width, source.height)
        for (y in 0 until source.height) {
            copyRow(source, y, out, source.height - 1 - y)
        }
        return out
    }

    private fun copyPixel(src: RgbImage, sx: Int, sy: Int, dst: RgbImage, dx: Int, dy: Int) {
        val o = ((dy * dst.width) + dx) * 3
        val i = ((sy * src.width) + sx) * 3
        dst.bytes[o] = src.bytes[i]
        dst.bytes[o + 1] = src.bytes[i + 1]
        dst.bytes[o + 2] = src.bytes[i + 2]
    }

    internal fun copyRow(src: RgbImage, sy: Int, dst: RgbImage, dy: Int) {
        src.bytes.copyInto(dst.bytes, dy * dst.width * 3, sy * src.width * 3, (sy + 1) * src.width * 3)
    }

    // ------------------------------------------------------------------ resize

    /**
     * Area-average downsample — the INTER_AREA replacement. Every destination
     * pixel is the coverage-weighted mean of the source rectangle it covers, so
     * downscales keep detail proportional instead of point-sampling it.
     */
    fun resizeArea(source: RgbImage, outWidth: Int, outHeight: Int): RgbImage {
        if (outWidth == source.width && outHeight == source.height) return source.copy()
        val out = RgbImage.zeros(outWidth, outHeight)
        val scaleX = source.width.toDouble() / outWidth
        val scaleY = source.height.toDouble() / outHeight
        val acc = DoubleArray(3)
        for (dy in 0 until outHeight) {
            val fy0 = dy * scaleY
            val fy1 = (dy + 1) * scaleY
            val iy0 = floor(fy0).toInt()
            val iy1 = ceil(fy1).toInt()
            for (dx in 0 until outWidth) {
                val fx0 = dx * scaleX
                val fx1 = (dx + 1) * scaleX
                val ix0 = floor(fx0).toInt()
                val ix1 = ceil(fx1).toInt()
                acc[0] = 0.0; acc[1] = 0.0; acc[2] = 0.0
                var area = 0.0
                for (sy in iy0 until minOf(iy1, source.height)) {
                    val wy = overlap(fy0, fy1, sy.toDouble(), (sy + 1).toDouble())
                    if (wy <= 0.0) continue
                    for (sx in ix0 until minOf(ix1, source.width)) {
                        val wx = overlap(fx0, fx1, sx.toDouble(), (sx + 1).toDouble())
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val i = (sy * source.width + sx) * 3
                        acc[0] += (source.bytes[i].toInt() and 0xff) * w
                        acc[1] += (source.bytes[i + 1].toInt() and 0xff) * w
                        acc[2] += (source.bytes[i + 2].toInt() and 0xff) * w
                        area += w
                    }
                }
                val o = (dy * outWidth + dx) * 3
                if (area > 0.0) {
                    out.bytes[o] = roundToByte(acc[0] / area)
                    out.bytes[o + 1] = roundToByte(acc[1] / area)
                    out.bytes[o + 2] = roundToByte(acc[2] / area)
                }
            }
        }
        return out
    }

    /** Coverage of the intersection of two half-open spans, ≥ 0. */
    private fun overlap(a0: Double, a1: Double, b0: Double, b1: Double): Double =
        (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0.0)

    /**
     * Bilinear resize — the INTER_LINEAR replacement, used for the pyramid
     * EXPAND step (upsampling coarse accumulators back toward the canvas).
     */
    fun resizeLinear(source: RgbImage, outWidth: Int, outHeight: Int): RgbImage {
        if (outWidth == source.width && outHeight == source.height) return source.copy()
        val out = RgbImage.zeros(outWidth, outHeight)
        val scaleX = source.width.toDouble() / outWidth
        val scaleY = source.height.toDouble() / outHeight
        for (dy in 0 until outHeight) {
            val fy = (dy + 0.5) * scaleY - 0.5
            val y0 = clamp(floor(fy).toInt(), 0, source.height - 1)
            val y1 = clamp(y0 + 1, 0, source.height - 1)
            val wy = fy - floor(fy)
            for (dx in 0 until outWidth) {
                val fx = (dx + 0.5) * scaleX - 0.5
                val x0 = clamp(floor(fx).toInt(), 0, source.width - 1)
                val x1 = clamp(x0 + 1, 0, source.width - 1)
                val wx = fx - floor(fx)
                val i00 = (y0 * source.width + x0) * 3
                val i01 = (y0 * source.width + x1) * 3
                val i10 = (y1 * source.width + x0) * 3
                val i11 = (y1 * source.width + x1) * 3
                val o = (dy * outWidth + dx) * 3
                for (c in 0 until 3) {
                    val top = source.bytes[i00 + c] * (1 - wx) + source.bytes[i01 + c] * wx
                    val bottom = source.bytes[i10 + c] * (1 - wx) + source.bytes[i11 + c] * wx
                    out.bytes[o + c] = (top * (1 - wy) + bottom * wy).toInt().toByte()
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------------- remap

    /**
     * Bilinear `remap`: for each output pixel ([mapX]/[mapY], row-major, exactly
     * [outWidth] × [outHeight] entries) samples the source, painting black
     * wherever the coordinate is negative or past the edge — the
     * BORDER_CONSTANT behaviour the renderer relies on to encode "not seen".
     *
     * Returns the interleaved RGB bytes of the output rectangle.
     */
    fun remapBilinear(
        source: RgbImage,
        mapX: FloatArray,
        mapY: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ): ByteArray {
        val out = ByteArray(outWidth * outHeight * 3)
        val maxX = source.width - 1
        val maxY = source.height - 1
        var index = 0
        for (row in 0 until outHeight) {
            for (col in 0 until outWidth) {
                val fx = mapX[index].toDouble()
                val fy = mapY[index].toDouble()
                index++
                if (fx < 0.0 || fy < 0.0 || fx > maxX || fy > maxY) continue
                val x0 = fx.toInt()
                val y0 = fy.toInt()
                val x1 = clamp(x0 + 1, 0, maxX)
                val y1 = clamp(y0 + 1, 0, maxY)
                val wx = (fx - x0).toFloat()
                val wy = (fy - y0).toFloat()
                val cx1 = clamp(x0, 0, maxX)
                val cy1 = clamp(y0, 0, maxY)
                val i00 = (cy1 * source.width + cx1) * 3
                val i01 = (cy1 * source.width + x1) * 3
                val i10 = (y1 * source.width + cx1) * 3
                val i11 = (y1 * source.width + x1) * 3
                val o = (row * outWidth + col) * 3
                for (c in 0 until 3) {
                    val sb = source.bytes
                    // `.toInt() and 0xFF` inside the parentheses: the named
                    // infix `and` binds looser than `*`/`+` in Kotlin, so a
                    // bare `b and 0xff * w` would parse as `b and (0xff * w)`.
                    val top = (sb[i00 + c].toInt() and 0xFF) * (1f - wx) +
                        (sb[i01 + c].toInt() and 0xFF) * wx
                    val bottom = (sb[i10 + c].toInt() and 0xFF) * (1f - wx) +
                        (sb[i11 + c].toInt() and 0xFF) * wx
                    out[o + c] = roundToByte((top * (1f - wy) + bottom * wy).toDouble())
                }
            }
        }
        return out
    }

    /**
     * Lanczos-3 `remap` — the sharpening kernel the finest render level asks
     * for (OpenCV's INTER_LANCZOS4 role). Same contract as [remapBilinear]:
     * negative or out-of-source map entries paint black. Six taps per axis;
     * weights are normalised so the kernel stays energy-preserving across the
     * phase of every sample.
     */
    fun remapLanczos(
        source: RgbImage,
        mapX: FloatArray,
        mapY: FloatArray,
        outWidth: Int,
        outHeight: Int,
    ): ByteArray {
        val out = ByteArray(outWidth * outHeight * 3)
        val maxX = source.width - 1
        val maxY = source.height - 1
        val xs = IntArray(LANCZOS_TAPS)
        val ys = IntArray(LANCZOS_TAPS)
        val wxs = DoubleArray(LANCZOS_TAPS)
        val wys = DoubleArray(LANCZOS_TAPS)
        var index = 0
        for (row in 0 until outHeight) {
            for (col in 0 until outWidth) {
                val fx = mapX[index].toDouble()
                val fy = mapY[index].toDouble()
                index++
                if (fx < 0.0 || fy < 0.0 || fx > maxX || fy > maxY) continue

                lanczosTaps(fx, xs, wxs)
                lanczosTaps(fy, ys, wys)
                val o = (row * outWidth + col) * 3
                var r = 0.0
                var g = 0.0
                var b = 0.0
                var weightSum = 0.0
                for (ty in 0 until LANCZOS_TAPS) {
                    val y = ys[ty]
                    if (y < 0 || y > maxY) continue
                    val wy = wys[ty]
                    val rowBase = y * source.width
                    for (tx in 0 until LANCZOS_TAPS) {
                        val x = xs[tx]
                        if (x < 0 || x > maxX) continue
                        val w = wxs[tx] * wy
                        val i = (rowBase + x) * 3
                        r += (source.bytes[i].toInt() and 0xff) * w
                        g += (source.bytes[i + 1].toInt() and 0xff) * w
                        b += (source.bytes[i + 2].toInt() and 0xff) * w
                        weightSum += w
                    }
                }
                if (weightSum <= 0.0) continue
                out[o] = roundToByte(r / weightSum)
                out[o + 1] = roundToByte(g / weightSum)
                out[o + 2] = roundToByte(b / weightSum)
            }
        }
        return out
    }

    private const val LANCZOS_A = 3
    private const val LANCZOS_TAPS = LANCZOS_A * 2

    private fun lanczos(value: Double): Double {
        val ax = abs(value)
        if (ax < 1e-8) return 1.0
        if (ax >= LANCZOS_A) return 0.0
        val px = PI * ax
        return LANCZOS_A * sin(px) * sin(px / LANCZOS_A) / (px * px)
    }

    /** Fills [taps]/[weights] with the [LANCZOS_TAPS] positions around [centre]. */
    private fun lanczosTaps(centre: Double, taps: IntArray, weights: DoubleArray) {
        val base = floor(centre).toInt() - LANCZOS_A + 1
        for (k in 0 until LANCZOS_TAPS) {
            val position = base + k
            taps[k] = position
            weights[k] = lanczos(centre - position)
        }
    }

    // ------------------------------------------------------------------ blur

    /**
     * Separable Gaussian blur — the `Imgproc.GaussianBlur(halo, blurred,
     * Size(0,0), sigma)` replacement, reflect-101 borders like OpenCV's
     * default. The radius follows OpenCV's rule of thumb: three sigmas either
     * side captures the kernel's visible support.
     */
    fun gaussianBlur(source: RgbImage, sigma: Double): RgbImage {
        require(sigma > 0.0) { "sigma must be positive" }
        val width = source.width
        val height = source.height
        val radius = ceil(sigma * 3.0).toInt().coerceIn(1, 256)
        val kernel = DoubleArray(radius * 2 + 1)
        var sum = 0.0
        for (k in -radius..radius) {
            val v = kotlin.math.exp(-0.5 * k * k / (sigma * sigma))
            kernel[k + radius] = v
            sum += v
        }
        for (k in kernel.indices) kernel[k] /= sum

        val horizontal = FloatArray(width * height * 3)
        val s = source.bytes
        for (y in 0 until height) {
            val rowBase = y * width
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (k in -radius..radius) {
                    val sx = reflect101(x + k, width)
                    val i = (rowBase + sx) * 3
                    val w = kernel[k + radius]
                    r += (s[i].toInt() and 0xff) * w
                    g += (s[i + 1].toInt() and 0xff) * w
                    b += (s[i + 2].toInt() and 0xff) * w
                }
                val o = (rowBase + x) * 3
                horizontal[o] = r.toFloat()
                horizontal[o + 1] = g.toFloat()
                horizontal[o + 2] = b.toFloat()
            }
        }

        val out = RgbImage.zeros(width, height)
        val ob = out.bytes
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                for (k in -radius..radius) {
                    val sy = reflect101(y + k, height)
                    val i = (sy * width + x) * 3
                    val w = kernel[k + radius]
                    r += horizontal[i] * w
                    g += horizontal[i + 1] * w
                    b += horizontal[i + 2] * w
                }
                val o = (y * width + x) * 3
                ob[o] = roundToByte(r)
                ob[o + 1] = roundToByte(g)
                ob[o + 2] = roundToByte(b)
            }
        }
        return out
    }

    /** Mirror without repeating the edge pixel — OpenCV's BORDER_REFLECT_101. */
    internal fun reflect101(index: Int, size: Int): Int {
        if (size == 1) return 0
        var i = index
        val period = size * 2 - 2
        i = ((i % period) + period) % period
        return if (i >= size) period - i else i
    }

    // ---------------------------------------------------------------- helpers

    /** Fills every pixel of [image] with the given RGB colour, in place. */
    fun fill(image: RgbImage, red: Int, green: Int, blue: Int) {
        val b = image.bytes
        var i = 0
        while (i < b.size) {
            b[i] = red.toByte()
            b[i + 1] = green.toByte()
            b[i + 2] = blue.toByte()
            i += 3
        }
    }

    internal fun clamp(value: Int, low: Int, high: Int): Int =
        when {
            value < low -> low
            value > high -> high
            else -> value
        }

    internal fun roundToByte(value: Double): Byte {
        val rounded = (value + 0.5).toInt().coerceIn(0, 255)
        return rounded.toByte()
    }

    /** Float convenience overload — Kotlin has no implicit widening. */
    internal fun roundToByte(value: Float): Byte = roundToByte(value.toDouble())
}
