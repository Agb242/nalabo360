package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
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
    fun `an empty graph leaves every frame neutral`() {
        val gains = ExposureCompensation.solveGains(
            meanLuma = doubleArrayOf(80.0, 120.0),
            edges = emptyList(),
        )
        assertEquals(1f, gains[0], 1e-6f)
        assertEquals(1f, gains[1], 1e-6f)
    }
}
