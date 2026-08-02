package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the parts of the pipeline that are pure Kotlin. The stitch itself needs
 * OpenCV's native library and real frames, so it belongs on a device.
 */
class PhotoSphereStitcherTest {

    @Test
    fun `OpenCV return codes map to statuses a user can be told about`() {
        assertEquals(StitchStatus.Ok, StitchStatus.fromOpenCvCode(0))
        assertEquals(StitchStatus.NeedMoreImages, StitchStatus.fromOpenCvCode(1))
        assertEquals(StitchStatus.AlignmentFailed, StitchStatus.fromOpenCvCode(2))
        assertEquals(StitchStatus.CameraEstimationFailed, StitchStatus.fromOpenCvCode(3))
    }

    @Test
    fun `an unrecognised return code does not go unreported`() {
        assertEquals(StitchStatus.Unknown, StitchStatus.fromOpenCvCode(42))
        assertEquals(StitchStatus.Unknown, StitchStatus.fromOpenCvCode(-1))
    }

    @Test
    fun `this pipeline's own statuses cannot collide with OpenCV's`() {
        val openCvCodes = setOf(
            StitchStatus.Ok,
            StitchStatus.NeedMoreImages,
            StitchStatus.AlignmentFailed,
            StitchStatus.CameraEstimationFailed,
        ).map(StitchStatus::code)

        StitchStatus.entries
            .filterNot { it.code in openCvCodes }
            .forEach { assertTrue("${it.name} sits in OpenCV's range", it.code >= 100) }

        assertEquals(
            "every status needs its own code",
            StitchStatus.entries.size,
            StitchStatus.entries.map(StitchStatus::code).toSet().size,
        )
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
    fun `progress only reports a fraction when it has one`() {
        assertEquals(null, StitchProgress(StitchStage.Stitching).fraction)
        assertEquals(0.25f, StitchProgress(StitchStage.Reading, 11, 44).fraction!!, 1e-4f)
        assertEquals(1f, StitchProgress(StitchStage.Reading, 44, 44).fraction!!, 1e-4f)
    }
}
