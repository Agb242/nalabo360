package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EquirectangularFitTest {

    @Test
    fun `a panorama that is already 2 to 1 fills the canvas untouched`() {
        val placement = EquirectangularFit.place(4000, 2000, maxCanvasWidth = 8192)

        assertEquals(4000, placement.canvasWidth)
        assertEquals(2000, placement.canvasHeight)
        assertEquals(0, placement.left)
        assertEquals(0, placement.top)
        assertEquals(4000, placement.width)
        assertEquals(2000, placement.height)
        assertTrue(placement.isFullCanvas)
    }

    @Test
    fun `a band of sky and ground is letterboxed, not stretched`() {
        // What the ±60° capture plan actually produces: full 360° around, but
        // only the middle 120° vertically.
        val placement = EquirectangularFit.place(3600, 1200, maxCanvasWidth = 8192)

        assertEquals(3600, placement.canvasWidth)
        assertEquals(1800, placement.canvasHeight)
        // Height is untouched, so latitudes stay where they belong.
        assertEquals(1200, placement.height)
        assertEquals(3600, placement.width)
        assertEquals(0, placement.left)
        assertEquals(300, placement.top)
    }

    @Test
    fun `a partial sweep is pillarboxed`() {
        val placement = EquirectangularFit.place(1000, 800, maxCanvasWidth = 8192)

        assertEquals(1600, placement.canvasWidth)
        assertEquals(800, placement.canvasHeight)
        assertEquals(300, placement.left)
        assertEquals(0, placement.top)
        assertEquals(1000, placement.width)
        assertEquals(800, placement.height)
    }

    @Test
    fun `the canvas never exceeds the cap, and the panorama scales with it`() {
        val placement = EquirectangularFit.place(8000, 2000, maxCanvasWidth = 4096)

        assertEquals(4096, placement.canvasWidth)
        assertEquals(2048, placement.canvasHeight)
        // Halved, so the aspect ratio survives the cap.
        assertEquals(4096, placement.width)
        assertEquals(1024, placement.height)
        assertEquals(512, placement.top)
    }

    @Test
    fun `the canvas is always exactly 2 to 1`() {
        val sources = listOf(
            1 to 1,
            4001 to 999,
            3333 to 1111,
            123 to 4567,
            8000 to 100,
        )

        sources.forEach { (width, height) ->
            listOf(64, 999, 4096, 100_000).forEach { cap ->
                val placement = EquirectangularFit.place(width, height, cap)
                assertEquals(
                    "canvas for ${width}x$height capped at $cap",
                    placement.canvasWidth,
                    placement.canvasHeight * 2,
                )
                assertTrue(placement.canvasWidth <= cap)
                // The panorama stays inside the canvas.
                assertTrue(placement.left >= 0 && placement.top >= 0)
                assertTrue(placement.left + placement.width <= placement.canvasWidth)
                assertTrue(placement.top + placement.height <= placement.canvasHeight)
            }
        }
    }

    @Test
    fun `the panorama is never enlarged to fill the canvas`() {
        val placement = EquirectangularFit.place(600, 400, maxCanvasWidth = 8192)

        assertEquals(600, placement.width)
        assertEquals(400, placement.height)
    }
}
