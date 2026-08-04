package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts of the pipeline that are pure Kotlin. The stitch itself needs
 * OpenCV's native library and real frames, so it belongs on a device.
 */
class PhotoSphereStitcherTest {

    @Test
    fun `registration outcomes and machinery failures stay in separate ranges`() {
        val registrationOutcomes = setOf(
            StitchStatus.Ok,
            StitchStatus.NeedMoreImages,
            StitchStatus.AlignmentFailed,
            StitchStatus.CameraEstimationFailed,
        )

        registrationOutcomes.forEach {
            assertTrue("${it.name} should be a low code", it.code in 0..3)
        }
        StitchStatus.entries
            .filterNot { it in registrationOutcomes }
            .forEach { assertTrue("${it.name} should sit clear of the low range", it.code >= 100) }
    }

    @Test
    fun `every status carries its own code`() {
        assertEquals(
            "codes are what a bug report is searched by, so they cannot collide",
            StitchStatus.entries.size,
            StitchStatus.entries.map(StitchStatus::code).toSet().size,
        )
    }

    @Test
    fun `the canvas is never rendered wider than the frames justify`() {
        // A 1024px frame across 66° carries ~15.5 px per degree, so a full turn
        // is worth ~5585px — under the cap, which therefore does not bite.
        assertEquals(
            5584,
            PhotoSphereStitcher.canvasWidthFor(1024, 66f, maxOutputWidth = 8192),
        )
        // The same frames under a 4096 cap take the cap instead.
        assertEquals(
            4096,
            PhotoSphereStitcher.canvasWidthFor(1024, 66f, maxOutputWidth = 4096),
        )
    }

    @Test
    fun `the canvas width is always even so the 2 to 1 canvas has whole rows`() {
        listOf(320, 512, 1024, 1600, 4000).forEach { frameWidth ->
            listOf(37f, 66f, 90f, 121f).forEach { fov ->
                val width = PhotoSphereStitcher.canvasWidthFor(frameWidth, fov, 4096)
                assertEquals("width $width is odd for $frameWidth px at $fov°", 0, width % 2)
                assertTrue("width $width is unusably small", width >= 512)
            }
        }
    }

    @Test
    fun `the canvas height follows the latitude span`() {
        // A full sphere: 180° of latitude across 360° of longitude is a 2:1 canvas.
        assertEquals(2048, PhotoSphereStitcher.canvasHeightFor(4096, 360f, 180f))
        // A ring spanning 72° of latitude is one fifth as tall as it is wide.
        assertEquals(819, PhotoSphereStitcher.canvasHeightFor(4096, 360f, 72f))
        // A hemisphere (180° × 180°) would be square.
        assertEquals(4096, PhotoSphereStitcher.canvasHeightFor(4096, 180f, 180f))
    }

    @Test
    fun `subsampling brings the long edge under the limit`() {
        // 4:3 sensor at 12MP, decoded for a 1024px working size.
        assertEquals(4, sampleSizeFor(width = 4000, height = 3000, maxDimension = 1024))
        assertTrue(4000 / 4 <= 1024)
    }

    @Test
    fun `a frame already small enough is decoded whole`() {
        assertEquals(1, sampleSizeFor(width = 1024, height = 768, maxDimension = 1024))
        assertEquals(1, sampleSizeFor(width = 640, height = 480, maxDimension = 1024))
    }

    @Test
    fun `the factor is always a power of two`() {
        listOf(1080, 2000, 4000, 8000, 12_000).forEach { width ->
            val sample = sampleSizeFor(width, height = width * 3 / 4, maxDimension = 1024)
            assertTrue("$sample is not a power of two", sample > 0 && sample and (sample - 1) == 0)
            assertTrue("long edge still over the limit", width / sample <= 1024)
        }
    }

    @Test
    fun `a field of view that matches the frame is left alone`() {
        // Portrait 4:3 frame with the portrait screen FOV.
        assertNull(PhotoSphereStitcher.correctFovOrientation(750, 1000, 52f, 66f))
        // Landscape 4:3 frame with the landscape FOV.
        assertNull(PhotoSphereStitcher.correctFovOrientation(1000, 750, 66f, 52f))
    }

    @Test
    fun `a transposed field of view is swapped back`() {
        // The axes describe the sensor when the frame is the display's (or the
        // reverse); the stitcher must not let a frame believe it spans the
        // wrong angle.
        val corrected = PhotoSphereStitcher.correctFovOrientation(750, 1000, 66f, 52f)
        assertNotNull(corrected)
        assertEquals(52f, corrected!!.first, 1e-3f)
        assertEquals(66f, corrected.second, 1e-3f)
    }

    @Test
    fun `a loosely estimated field of view is not touched`() {
        // 10% error on a real lens still implies near-square pixels; only a
        // genuine transposition (the square of the aspect ratio) trips the swap.
        assertNull(PhotoSphereStitcher.correctFovOrientation(750, 1000, 52f * 1.1f, 66f * 0.95f))
    }

    @Test
    fun `progress only reports a fraction when it has one`() {
        assertEquals(null, StitchProgress(StitchStage.Stitching).fraction)
        assertEquals(0.25f, StitchProgress(StitchStage.Reading, 11, 44).fraction!!, 1e-4f)
        assertEquals(1f, StitchProgress(StitchStage.Reading, 44, 44).fraction!!, 1e-4f)
    }
}
