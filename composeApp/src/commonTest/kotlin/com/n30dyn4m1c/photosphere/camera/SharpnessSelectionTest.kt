package com.n30dyn4m1c.photosphere.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessSelectionTest {

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun flatPixels(width: Int, height: Int, value: Int): IntArray =
        IntArray(width * height) { argb(value, value, value) }

    private fun checkerboardPixels(width: Int, height: Int): IntArray {
        val dark = argb(0, 0, 0)
        val light = argb(255, 255, 255)
        return IntArray(width * height) { i ->
            if (((i / width) + (i % width)) % 2 == 0) dark else light
        }
    }

    @Test
    fun `a scene full of edges scores higher than a flat one`() {
        val sharp = SharpnessSelection.laplacianVariance(checkerboardPixels(32, 32), 32, 32)
        val flat = SharpnessSelection.laplacianVariance(flatPixels(32, 32, 128), 32, 32)

        assertTrue(sharp > flat)
    }

    @Test
    fun `a perfectly flat frame scores zero`() {
        assertEquals(0f, SharpnessSelection.laplacianVariance(flatPixels(32, 32, 128), 32, 32), 1e-6f)
    }

    @Test
    fun `a flat frame scores zero whatever its brightness`() {
        assertEquals(0f, SharpnessSelection.laplacianVariance(flatPixels(32, 32, 12), 32, 32), 1e-6f)
    }

    @Test
    fun `frames too small to score are treated as flat`() {
        assertEquals(0f, SharpnessSelection.laplacianVariance(IntArray(2 * 2) { 0 }, 2, 2), 1e-6f)
    }
}
