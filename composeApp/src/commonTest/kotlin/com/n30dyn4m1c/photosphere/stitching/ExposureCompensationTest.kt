package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gain compensation that stops the blends showing seams of light.
 */
class ExposureCompensationTest {

    @Test
    fun `gains equalise a pair of frames`() {
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(100.0, 200.0),
            edges = listOf(0 to 1),
        )

        // The corrected means agree, and the geometric mean of the gains is 1 so
        // the sphere is neither brightened nor darkened overall.
        assertEquals(100.0 * gains[0], 200.0 * gains[1], 1e-3)
        assertEquals(1.0, Math.sqrt(gains[0].toDouble() * gains[1]), 1e-3)
    }

    @Test
    fun `a connected chain of frames converges to consistent gains`() {
        val means = doubleArrayOf(80.0, 100.0, 130.0, 90.0)
        val edges = listOf(0 to 1, 1 to 2, 2 to 3)
        val gains = ExposureCompensation.solveGains(means, edges)

        edges.forEach { (a, b) ->
            assertEquals(
                "edge $a-$b: ${means[a] * gains[a]} vs ${means[b] * gains[b]}",
                means[a] * gains[a],
                means[b] * gains[b],
                1e-2,
            )
        }
    }

    @Test
    fun `frames the graph does not reach keep a neutral gain`() {
        // Frame 2 is isolated: its gain must stay exactly 1 (the geometric mean
        // of the others' gains is what absorbs the global factor).
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(100.0, 200.0, 50.0),
            edges = listOf(0 to 1),
        )

        assertEquals(1f, gains[2], 1e-6f)
        assertEquals(100.0 * gains[0], 200.0 * gains[1], 1e-3)
    }

    @Test
    fun `identical frames get a gain of one`() {
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(120.0, 120.0, 120.0),
            edges = listOf(0 to 1, 1 to 2),
        )

        gains.forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test
    fun `a runaway gain is clamped rather than blowing out a frame`() {
        // The whole-frame mean is a coarse stand-in for the brightness of the
        // shared region, so a frame that is half sky pulls its own gain in a
        // direction the overlap never asked for. Left unbounded, that lands a
        // blown-out patch on the sphere — worse than the seam it was shaving.
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(10.0, 240.0),
            edges = listOf(0 to 1),
        )

        gains.forEach {
            assertTrue(
                "gain $it escaped the clamp",
                it in ExposureCompensation.MIN_GAIN..ExposureCompensation.MAX_GAIN,
            )
        }
        // The clamp is symmetric in log space, so it does not tilt the sphere's
        // overall exposure one way or the other.
        assertEquals(1.0, Math.sqrt(gains[0].toDouble() * gains[1]), 1e-3)
    }

    @Test
    fun `a healthy session never reaches the clamp`() {
        val means = doubleArrayOf(96.0, 104.0, 112.0, 100.0)
        val gains = ExposureCompensation.solveGains(means, listOf(0 to 1, 1 to 2, 2 to 3))
        gains.forEach { assertTrue("gain $it is suspiciously large", it in 0.85f..1.18f) }
    }

    @Test
    fun `a self edge cannot divide a frame's gain by itself`() {
        // Nothing in the pipeline produces one, but a degenerate edge would make
        // the frame its own neighbour and freeze its equation.
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(100.0, 200.0),
            edges = listOf(0 to 0, 0 to 1),
        )
        assertEquals(100.0 * gains[0], 200.0 * gains[1], 1e-3)
    }

    @Test
    fun `an empty graph leaves every frame neutral`() {
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(80.0, 120.0),
            edges = emptyList(),
        )
        assertEquals(1f, gains[0], 1e-6f)
        assertEquals(1f, gains[1], 1e-6f)
    }
}
