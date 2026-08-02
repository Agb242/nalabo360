package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a stitched panorama sits inside a 2:1 equirectangular canvas.
 *
 * Coordinates are pixels in the canvas, origin top-left.
 */
data class EquirectangularPlacement(
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** Left edge of the panorama within the canvas. */
    val left: Int,
    /** Top edge of the panorama within the canvas. */
    val top: Int,
    /** Width the panorama is drawn at. */
    val width: Int,
    /** Height the panorama is drawn at. */
    val height: Int,
) {
    /** True when the panorama fills the canvas outright — a complete sphere. */
    val isFullCanvas: Boolean
        get() = left == 0 && top == 0 && width == canvasWidth && height == canvasHeight
}

/**
 * Fits a stitched panorama into the 2:1 canvas an equirectangular image needs.
 *
 * A sphere covering the full 360°×180° comes out of the stitcher at 2:1 already
 * and lands unchanged. Anything less — a run that stopped early, or the ±60°
 * rings the capture plan actually shoots — comes out wider than it is tall, and
 * has to be **placed** rather than stretched: scaling a 360°×120° panorama to
 * fill a 2:1 frame would put every pixel at the wrong latitude and viewers would
 * render a visibly squashed sphere. So the panorama keeps its aspect ratio and
 * is centred, with the uncovered poles left black.
 *
 * Centring is the honest default without knowing the true angular extent: the
 * capture plan is symmetric about the horizon, so the covered band is centred on
 * the equator. Recovering the real extent needs the stitcher's estimated camera
 * parameters, which OpenCV's Java bindings do not expose.
 *
 * Pure integer geometry, so it is unit-tested rather than eyeballed on a device.
 */
object EquirectangularFit {

    /**
     * Places a [sourceWidth]×[sourceHeight] panorama in the smallest 2:1 canvas
     * that contains it, capped at [maxCanvasWidth].
     *
     * The source is never enlarged: below the cap it sits at 1:1 and the canvas
     * grows around it; above the cap both are scaled down together.
     */
    fun place(sourceWidth: Int, sourceHeight: Int, maxCanvasWidth: Int): EquirectangularPlacement {
        require(sourceWidth > 0 && sourceHeight > 0) {
            "source must have area, was ${sourceWidth}x$sourceHeight"
        }
        require(maxCanvasWidth >= 2) { "canvas needs at least 2px of width" }

        // The smallest 2:1 canvas containing the source at 1:1, then capped.
        // Even, so the half that becomes the height is exact.
        val canvasWidth = min(maxOf(sourceWidth, 2 * sourceHeight), maxCanvasWidth).let { it - it % 2 }
        val canvasHeight = canvasWidth / 2

        // At most 1: the uncapped canvas contains the source exactly, so the
        // only way to scale here is down.
        val scale = min(
            canvasWidth.toFloat() / sourceWidth,
            canvasHeight.toFloat() / sourceHeight,
        ).coerceAtMost(1f)

        val width = (sourceWidth * scale).roundToInt().coerceIn(1, canvasWidth)
        val height = (sourceHeight * scale).roundToInt().coerceIn(1, canvasHeight)

        return EquirectangularPlacement(
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            left = (canvasWidth - width) / 2,
            top = (canvasHeight - height) / 2,
            width = width,
            height = height,
        )
    }
}
