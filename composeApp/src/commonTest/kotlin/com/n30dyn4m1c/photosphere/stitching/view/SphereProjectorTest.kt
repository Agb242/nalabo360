package com.n30dyn4m1c.photosphere.stitching.view

import com.n30dyn4m1c.photosphere.stitching.RgbImage
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the projector's geometry with synthetic panoramas whose pixel colours
 * encode where on the sphere they sit.
 *
 * A real stitched photo can only be judged by eye; these sources make "the
 * centre of the view samples longitude +90°" a channel value an assertion can
 * read, which is what keeps a sign flip or a swapped axis from ever shipping.
 */
class SphereProjectorTest {

    private fun solid(width: Int, height: Int, argb: Int): RgbImage {
        val bytes = ByteArray(width * height * 3)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        var i = 0
        while (i < bytes.size) {
            bytes[i] = r.toByte()
            bytes[i + 1] = g.toByte()
            bytes[i + 2] = b.toByte()
            i += 3
        }
        return RgbImage(width, height, bytes)
    }

    /**
     * An equirectangular gradient: red encodes longitude (0 at lon −180°,
     * 255 at lon +180°), green encodes latitude (255 at the zenith, 0 at the
     * nadir). One degree per source pixel in both axes.
     */
    private fun directionGradient(): RgbImage {
        val width = 360
        val height = 180
        val bytes = ByteArray(width * height * 3)
        for (y in 0 until height) {
            val green = (255f - y / (height - 1f) * 255f).roundToInt()
            for (x in 0 until width) {
                val red = (x / (width - 1f) * 255f).roundToInt()
                val index = (y * width + x) * 3
                bytes[index] = red.toByte()
                bytes[index + 1] = green.toByte()
                bytes[index + 2] = 127
            }
        }
        return RgbImage(width, height, bytes)
    }

    private fun render(
        source: RgbImage,
        width: Int,
        height: Int,
        yaw: Float = 0f,
        pitch: Float = 0f,
        fov: Float = SphereProjector.DEFAULT_FOV_DEGREES,
    ): IntArray {
        val output = IntArray(width * height)
        SphereProjector(source).render(output, width, height, yaw, pitch, fov)
        return output
    }

    private fun centerPixel(pixels: IntArray, width: Int, height: Int): Int =
        pixels[(height / 2) * width + width / 2]

    @Test
    fun uniformSourceFillsEveryPixelAtAnyAngle() {
        val output = render(solid(8, 4, 0xFF3366AA.toInt()), 24, 18, yaw = 123f, pitch = -40f)
        for (pixel in output) {
            assertEquals(0xFF3366AA.toInt(), pixel)
        }
    }

    @Test
    fun centerOfViewSamplesTheLookedAtLongitude() {
        val source = directionGradient()

        // Red channel runs 0→255 across 360° of longitude, so looking at yaw Y
        // must sample (Y + 180) / 360 · 255.
        fun expectedRedForYaw(yawDegrees: Float): Int =
            ((yawDegrees + 180f) / 360f * 255f).roundToInt().coerceIn(0, 255)

        for (yaw in listOf(0f, 90f, -90f, 45f, 179f)) {
            val pixel = centerPixel(render(source, 61, 61, yaw = yaw), 61, 61)
            val actualRed = (pixel shr 16) and 0xFF
            val expected = expectedRedForYaw(yaw)
            assertTrue(
                actualRed in (expected - 2)..(expected + 2),
                "yaw $yaw°: expected red≈$expected, got $actualRed",
            )
            // The view is level, so the centre sits on the horizon: mid green.
            val actualGreen = (pixel shr 8) and 0xFF
            assertTrue(actualGreen in 125..130, "yaw $yaw°: horizon green ≈128, got $actualGreen")
        }
    }

    @Test
    fun pitchingUpLooksTowardTheZenithAndDownTowardTheNadir() {
        val source = directionGradient()

        val upPixel = centerPixel(render(source, 41, 41, pitch = 80f, fov = 20f), 41, 41)
        assertTrue(
            ((upPixel shr 8) and 0xFF) >= 240,
            "pitch +80° should sample near the zenith, got green ${(upPixel shr 8) and 0xFF}",
        )

        val downPixel = centerPixel(render(source, 41, 41, pitch = -80f, fov = 20f), 41, 41)
        assertTrue(
            ((downPixel shr 8) and 0xFF) <= 15,
            "pitch −80° should sample near the nadir, got green ${(downPixel shr 8) and 0xFF}",
        )
    }

    @Test
    fun viewIsStableUnderAFullTurn() {
        val source = directionGradient()
        val plain = render(source, 61, 61, yaw = 30f)
        val wrapped = render(source, 61, 61, yaw = 390f)

        // 30° and 390° are the same pose; only float noise in the trig differs,
        // which can flip a sample at worst across the dateline column jump.
        val channelDiffLimit = 8
        var mismatchedPixels = 0
        for (i in plain.indices) {
            fun channels(pixel: Int) = listOf(
                (pixel shr 16) and 0xFF,
                (pixel shr 8) and 0xFF,
                pixel and 0xFF,
            )
            if (channels(plain[i]).zip(channels(wrapped[i]))
                    .any { (a, b) -> kotlin.math.abs(a - b) > channelDiffLimit }
            ) {
                mismatchedPixels++
            }
        }
        assertTrue(
            mismatchedPixels <= plain.size / 100,
            "$mismatchedPixels of ${plain.size} pixels differ between yaw 30° and 390°",
        )
    }

    @Test
    fun datelineCenterSamplesOneOfTheTwoDatelineColumns() {
        val source = directionGradient()
        val pixel = centerPixel(render(source, 41, 41, yaw = 180f), 41, 41)
        val red = (pixel shr 16) and 0xFF

        // Straight at the ±180° seam, the centre must land on column 0 or the
        // last column — anything mid-range would mean longitude was clamped
        // into range instead of wrapped.
        assertTrue(red <= 3 || red >= 252, "seam centre sampled red=$red, expected an edge column")
    }

    @Test
    fun widerViewportSeesMoreHorizonAtTheSameVerticalFov() {
        val source = directionGradient()
        val square = render(source, 60, 60, fov = 60f)
        val landscape = render(source, 120, 60, fov = 60f)

        fun rowSpan(pixels: IntArray, width: Int): Int {
            val row = 30 * width
            val first = (pixels[row] shr 16) and 0xFF
            val last = (pixels[row + width - 1] shr 16) and 0xFF
            return kotlin.math.abs(last - first)
        }

        // Doubling the width at a fixed height widens the horizontal reach
        // (the image plane scales with w/h); the angular measure grows less
        // than 2× because of the tangent, so assert direction, with margin.
        assertTrue(
            rowSpan(landscape, 120) > rowSpan(square, 60),
            "landscape span ${rowSpan(landscape, 120)} should exceed the square span ${rowSpan(square, 60)}",
        )
    }

    @Test
    fun oversizedOutputBufferIsAcceptedAndOnlyTheViewportIsWritten() {
        val output = IntArray(64 * 48 + 7)
        SphereProjector(solid(6, 3, 0xFF112233.toInt()))
            .render(output, 64, 48, 0f, 0f, SphereProjector.DEFAULT_FOV_DEGREES)
        for (i in 0 until 64 * 48) {
            assertEquals(0xFF112233.toInt(), output[i])
        }
        // Past the viewport the caller's buffer is untouched.
        repeat(7) { extra -> assertEquals(0, output[64 * 48 + extra]) }
    }

    @Test
    fun undersizedOutputBufferIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SphereProjector(directionGradient()).render(IntArray(10), 8, 8, 0f, 0f, 75f)
        }
    }
}
