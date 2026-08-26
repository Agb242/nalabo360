package com.n30dyn4m1c.photosphere.stitching.view

import com.n30dyn4m1c.photosphere.stitching.RgbImage
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Renders perspective views out of an equirectangular panorama, one pixel at a
 * time.
 *
 * This is the viewing twin of the stitcher's own projection: where the stitcher
 * folds captured frames *onto* the 2:1 equirectangular canvas, this walks every
 * viewport pixel along its ray *off* that canvas again, so what appears in the
 * viewer is what a person standing at the sphere's centre would see towards
 * [yawDegrees]/[pitchDegrees] through a [verticalFovDegrees]-wide lens.
 *
 * The whole renderer is pure arithmetic on the [RgbImage] buffer — no platform
 * graphics anywhere — so it runs identically on Android, iOS, and the JVM test
 * suite, and it is deliberately nearest-neighbour: one texture fetch per pixel
 * keeps a drag frame in the low tens of milliseconds on a low-RAM phone, where
 * bilinear would double the cost for a softness the eye forgives in motion.
 *
 * Output is packed opaque ARGB ints, the format both platform bitmap factories
 * ingest directly (`Bitmap.createBitmap(IntArray…)` / Skia `installPixels`).
 */
class SphereProjector(private val source: RgbImage) {

    /** Per-column ray terms, grown on demand and reused across frames. */
    private var columnTerms = FloatArray(0)

    /** Per-row ray terms, ditto. */
    private var rowTerms = FloatArray(0)

    /**
     * Projects one view into [output], filling [width] × [height] entries
     * starting at index 0. [output] may be larger than needed; it may not be
     * smaller.
     */
    fun render(
        output: IntArray,
        width: Int,
        height: Int,
        yawDegrees: Float,
        pitchDegrees: Float,
        verticalFovDegrees: Float,
    ) {
        require(width > 0 && height > 0) { "viewport must have extent" }
        require(output.size >= width * height) {
            "output needs ${width * height} ints for ${width}x$height, has ${output.size}"
        }

        val fovRadians =
            verticalFovDegrees.coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES) * DEGREES_TO_RADIANS
        val yawRadians = yawDegrees * DEGREES_TO_RADIANS
        val pitchRadians =
            pitchDegrees.coerceIn(-MAX_PITCH_DEGREES, MAX_PITCH_DEGREES) * DEGREES_TO_RADIANS

        // Camera basis, world axes as in the stitcher: +X east, +Y up, −Z north.
        // Forward is the looked-at direction; right and up complete the frame so
        // viewport coordinates become offsets across the image plane.
        val cosPitch = cos(pitchRadians)
        val sinPitch = sin(pitchRadians)
        val cosYaw = cos(yawRadians)
        val sinYaw = sin(yawRadians)

        val forwardX = cosPitch * sinYaw
        val forwardY = sinPitch
        val forwardZ = -cosPitch * cosYaw

        val rightX = cosYaw
        val rightZ = sinYaw

        // up = right × forward, which collapses to these three terms.
        val upX = -sinYaw * sinPitch
        val upY = cosPitch
        val upZ = cosYaw * sinPitch

        val halfHeight = tan(fovRadians * 0.5f)
        val halfWidth = halfHeight * width / height

        // Ray terms precompute everything that varies along one axis alone:
        // column x carries its horizontal image-plane offset, row y its
        // vertical one, and the inner loop is three multiply-adds plus one
        // normalisation and two inverse trig per pixel.
        if (columnTerms.size < width) columnTerms = FloatArray(width)
        if (rowTerms.size < height) rowTerms = FloatArray(height)
        for (x in 0 until width) {
            val ndcX = (x + 0.5f) / width * 2f - 1f
            columnTerms[x] = ndcX * halfWidth
        }
        for (y in 0 until height) {
            val ndcY = 1f - (y + 0.5f) / height * 2f
            rowTerms[y] = ndcY * halfHeight
        }

        val pixels = source.bytes
        val sourceWidth = source.width
        val sourceHeight = source.height

        var outIndex = 0
        for (y in 0 until height) {
            val planeY = rowTerms[y]
            for (x in 0 until width) {
                val planeX = columnTerms[x]

                // Un-normalised ray direction through this viewport pixel.
                var rayX = forwardX + planeX * rightX + planeY * upX
                var rayY = forwardY + planeY * upY
                var rayZ = forwardZ + planeX * rightZ + planeY * upZ

                val inverseLength = 1f / sqrt(rayX * rayX + rayY * rayY + rayZ * rayZ)
                rayX *= inverseLength
                rayY *= inverseLength
                rayZ *= inverseLength

                // Direction → spherical coordinates. asin's argument can land a
                // ulp outside [-1, 1] after the normalise, and asin answers NaN
                // there, hence the clamp. atan2 is already wrapped to [-π, π].
                val latitude = asin(rayY.coerceIn(-1f, 1f))
                val longitude = atan2(rayX, -rayZ)

                // Equirectangular lookup: longitude spans the full width,
                // latitude runs top (zenith) to bottom (nadir).
                var u = ((longitude * INVERSE_TWO_PI + 0.5f) * sourceWidth).toInt()
                if (u < 0) u = 0 else if (u >= sourceWidth) u = sourceWidth - 1
                var v = ((0.5f - latitude * INVERSE_PI) * sourceHeight).toInt()
                if (v < 0) v = 0 else if (v >= sourceHeight) v = sourceHeight - 1

                val sampleIndex = (v * sourceWidth + u) * RGB_CHANNELS
                output[outIndex++] = OPAQUE_ALPHA or
                    ((pixels[sampleIndex].toInt() and 0xFF) shl 16) or
                    ((pixels[sampleIndex + 1].toInt() and 0xFF) shl 8) or
                    (pixels[sampleIndex + 2].toInt() and 0xFF)
            }
        }
    }

    companion object {
        /** Field-of-view limits the pinch gesture is clamped to, in degrees. */
        const val MIN_FOV_DEGREES = 35f
        const val MAX_FOV_DEGREES = 100f

        /** The lens the viewer opens with — wide enough to feel immersive. */
        const val DEFAULT_FOV_DEGREES = 75f

        /**
         * How far from straight up/down the view may point. Beyond ~85° the
         * horizon spins wildly under the finger for no viewing benefit.
         */
        const val MAX_PITCH_DEGREES = 85f

        private const val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()
        private const val INVERSE_PI = (1.0 / PI).toFloat()
        private const val INVERSE_TWO_PI = (1.0 / (2.0 * PI)).toFloat()
        private const val RGB_CHANNELS = 3
        private const val OPAQUE_ALPHA = 0xFF000000.toInt()
    }
}
