package com.n30dyn4m1c.photosphere.stitching

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * One frame ready to be painted onto the sphere: its pixels, where the camera
 * was, and the block of canvas it can reach.
 *
 * [image] is `CV_8UC3` and belongs to whoever built the frame — the renderer
 * reads from it and never releases it.
 */
internal class PreparedFrame(
    val image: Mat,
    val basis: CameraBasis,
    val intrinsics: FrameIntrinsics,
    val footprint: CanvasFootprint,
)

/**
 * Paints frames onto an equirectangular canvas.
 *
 * Each output pixel is a direction on the sphere. For every frame that can see
 * that direction, the direction is rotated into the frame's own axes and divided
 * through by depth, which gives the pixel of the frame that looked at it — the
 * inverse of the projection the lens performed. `remap` then does the sampling.
 *
 * Where frames overlap, the result is a weighted mean rather than whichever
 * frame was painted last. The weight falls to zero at each frame's border, so a
 * pixel near the edge of one frame and the middle of its neighbour takes almost
 * all of its colour from the neighbour, and the seam becomes a cross-fade
 * instead of a line. A pixel only one frame reached still comes out at full
 * strength: the weight divides out.
 *
 * **Memory.** A 4096-wide canvas needs 100 MB of float accumulator if it is held
 * all at once, which is exactly the kind of allocation that ends a stitch on a
 * mid-range phone. The canvas is therefore built in horizontal bands: only the
 * band being accumulated exists in float, and each frame contributes to a band
 * through the intersection of the band with its own footprint. The total work is
 * unchanged — every frame still touches each of its pixels once — but the peak
 * is a few megabytes instead of a hundred.
 */
internal object EquirectangularRenderer {

    /** Rows accumulated at once. Sets the renderer's peak memory, with the width. */
    private const val BAND_HEIGHT = 128

    /**
     * Exponent the feather weight is raised to.
     *
     * A power greater than one makes the closest frame dominate a shared pixel
     * and confines the cross-fade to a thin band. A linear feather leaves both
     * frames at comparable weights across a wide overlap, which turns the
     * residual pose error between neighbouring frames into a broad smear.
     */
    private const val BLEND_POWER = 3.0

    /**
     * Added to the weight before dividing, so an uncovered pixel divides 0 by a
     * small number rather than 0 by 0. Small enough not to darken real pixels,
     * whose weights are orders of magnitude larger.
     */
    private const val WEIGHT_EPSILON = 1e-6

    /** What a render produced, and how much of the sphere it reached. */
    class Rendered(
        /** The finished `CV_8UC3` canvas. The caller owns it. */
        val canvas: Mat,
        /** Fraction of the canvas any frame reached, 0..1. */
        val coverage: Float,
    )

    /**
     * Renders [frames] into a [canvasWidth] × [canvasHeight] canvas.
     *
     * [gains], when given, holds one brightness multiplier per frame, aligned by
     * position with [frames]; it is applied before the overlaps are blended so
     * the exposure compensation lands inside the mean rather than on top of it.
     * [onBandComplete] is called with the number of bands finished so far and
     * the total, for progress reporting; [checkCancelled] is called at every band
     * boundary and may throw to abandon the render.
     *
     * [pivotRatio] is [PivotModel.ratio]: how far the lens sat from the axis the
     * user turned about, as a fraction of the scene's distance. Zero treats the
     * lens as the pivot, which is what a tripod gives and what a hand-held
     * capture never quite does.
     */
    fun render(
        frames: List<PreparedFrame>,
        canvasWidth: Int,
        canvasHeight: Int,
        gains: FloatArray? = null,
        pivotRatio: Double = 0.0,
        onBandComplete: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Rendered {
        val canvas = Mat.zeros(canvasHeight, canvasWidth, CvType.CV_8UC3)
        var coveredPixels = 0L
        val bandCount = (canvasHeight + BAND_HEIGHT - 1) / BAND_HEIGHT

        try {
            for (band in 0 until bandCount) {
                checkCancelled()
                val bandTop = band * BAND_HEIGHT
                val bandHeight = min(BAND_HEIGHT, canvasHeight - bandTop)
                coveredPixels += renderBand(
                    frames = frames,
                    canvas = canvas,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    bandTop = bandTop,
                    bandHeight = bandHeight,
                    gains = gains,
                    pivotRatio = pivotRatio,
                )
                onBandComplete(band + 1, bandCount)
            }
        } catch (e: Throwable) {
            canvas.release()
            throw e
        }

        val total = canvasWidth.toLong() * canvasHeight
        return Rendered(canvas, coverage = (coveredPixels.toDouble() / total).toFloat())
    }

    /** Accumulates every frame that reaches one band, and writes it into [canvas]. */
    private fun renderBand(
        frames: List<PreparedFrame>,
        canvas: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        bandTop: Int,
        bandHeight: Int,
        gains: FloatArray?,
        pivotRatio: Double,
    ): Long {
        val colorSum = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC3)
        val weightSum = Mat.zeros(bandHeight, canvasWidth, CvType.CV_32FC1)
        try {
            for (frameIndex in frames.indices) {
                val frame = frames[frameIndex]
                val footprint = frame.footprint
                if (footprint.isEmpty) continue

                val rowStart = max(bandTop, footprint.startRow)
                val rowEnd = min(bandTop + bandHeight, footprint.startRow + footprint.rowSpan)
                if (rowEnd <= rowStart) continue

                forEachColumnRun(footprint, canvasWidth) { canvasColumn, columnSpan ->
                    accumulateSegment(
                        frame = frame,
                        colorSum = colorSum,
                        weightSum = weightSum,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        bandTop = bandTop,
                        rowStart = rowStart,
                        rowCount = rowEnd - rowStart,
                        columnStart = canvasColumn,
                        columnCount = columnSpan,
                        gain = gains?.getOrNull(frameIndex) ?: 1f,
                        pivotRatio = pivotRatio,
                    )
                }
            }

            val covered = Core.countNonZero(weightSum).toLong()
            writeBand(colorSum, weightSum, canvas, bandTop, bandHeight, canvasWidth)
            return covered
        } finally {
            colorSum.release()
            weightSum.release()
        }
    }

    /**
     * Projects one rectangle of canvas back through a frame and adds it in.
     *
     * The two trigonometric factors of a direction separate — longitude varies
     * only across columns and latitude only down rows — so the per-column parts
     * are computed once for the whole rectangle and the inner loop is left with
     * a couple of multiplies per pixel.
     */
    private fun accumulateSegment(
        frame: PreparedFrame,
        colorSum: Mat,
        weightSum: Mat,
        canvasWidth: Int,
        canvasHeight: Int,
        bandTop: Int,
        rowStart: Int,
        rowCount: Int,
        columnStart: Int,
        columnCount: Int,
        gain: Float,
        pivotRatio: Double,
    ) {
        if (rowCount <= 0 || columnCount <= 0) return

        val basis = frame.basis
        val intrinsics = frame.intrinsics
        val centreX = intrinsics.centerXPx
        val centreY = intrinsics.centerYPx
        val maxColumn = intrinsics.widthPx - 1.0
        val maxRow = intrinsics.heightPx - 1.0
        val radial = intrinsics.radial

        // Longitude is periodic, so a column that was unwrapped past the seam
        // gives the same direction as the column it wraps onto — the canvas
        // column can be used directly here.
        val forwardHorizontal = DoubleArray(columnCount)
        val rightHorizontal = DoubleArray(columnCount)
        val upHorizontal = DoubleArray(columnCount)
        for (offset in 0 until columnCount) {
            val longitude = Math.toRadians(
                Equirectangular.longitudeDegrees(columnStart + offset, canvasWidth)
            )
            val sinLongitude = sin(longitude)
            val cosLongitude = cos(longitude)
            forwardHorizontal[offset] = sinLongitude * basis.forwardX + cosLongitude * basis.forwardY
            rightHorizontal[offset] = sinLongitude * basis.rightX + cosLongitude * basis.rightY
            upHorizontal[offset] = sinLongitude * basis.upX + cosLongitude * basis.upY
        }

        val pixelCount = rowCount * columnCount
        val mapX = FloatArray(pixelCount)
        val mapY = FloatArray(pixelCount)
        val weights = FloatArray(pixelCount)
        var anyCovered = false

        for (rowOffset in 0 until rowCount) {
            val latitude = Math.toRadians(
                Equirectangular.latitudeDegrees(rowStart + rowOffset, canvasHeight)
            )
            val cosLatitude = cos(latitude)
            val sinLatitude = sin(latitude)
            val rowBase = rowOffset * columnCount

            for (columnOffset in 0 until columnCount) {
                // The canvas direction is a unit vector from the pivot, and the
                // lens sits `pivotRatio` of the way out along its own optical
                // axis (see PivotModel). The lever arm is parallel to forward, so
                // it drops out of the two lateral components and shortens the
                // depth alone — the whole parallax correction is this subtraction.
                val depth = cosLatitude * forwardHorizontal[columnOffset] +
                    sinLatitude * basis.forwardZ - pivotRatio
                val index = rowBase + columnOffset
                if (depth <= MIN_DEPTH) {
                    // Behind the camera. A map coordinate outside the source
                    // makes remap emit the border colour, and the zero weight
                    // keeps it out of the mean regardless.
                    mapX[index] = -1f
                    mapY[index] = -1f
                    continue
                }

                val lateral = cosLatitude * rightHorizontal[columnOffset] +
                    sinLatitude * basis.rightZ
                val vertical = cosLatitude * upHorizontal[columnOffset] + sinLatitude * basis.upZ

                // The lens model in two steps: the ideal pinhole pixel, then the
                // radial push to where the lens actually recorded it. The ideal
                // pixel is what the feather measures against — it is proportional
                // to the angle off the optical axis — while the distorted pixel
                // is what `remap` samples.
                val idealColumn = centreX + intrinsics.focalXPx * lateral / depth
                val idealRow = centreY - intrinsics.focalYPx * vertical / depth
                var sourceColumn = idealColumn
                var sourceRow = idealRow
                if (radial != null) {
                    val x = idealColumn - centreX
                    val y = idealRow - centreY
                    val factor = radialFactor(radial, x * x + y * y)
                    sourceColumn = centreX + x * factor
                    sourceRow = centreY + y * factor
                }
                if (sourceColumn < 0.0 || sourceColumn > maxColumn ||
                    sourceRow < 0.0 || sourceRow > maxRow
                ) {
                    mapX[index] = -1f
                    mapY[index] = -1f
                    continue
                }

                mapX[index] = sourceColumn.toFloat()
                mapY[index] = sourceRow.toFloat()
                // Feather: full strength at the optical axis, falling to nothing
                // at the frame's border, so overlaps cross-fade. Raised to a
                // power so the frame that sees the direction most head-on wins
                // the blend instead of a wide linear cross-fade.
                val acrossFrame = 1.0 - abs(idealColumn - centreX) / centreX
                val downFrame = 1.0 - abs(idealRow - centreY) / centreY
                val weight = (acrossFrame * downFrame).pow(BLEND_POWER)
                if (weight > 0.0) {
                    weights[index] = weight.toFloat()
                    anyCovered = true
                }
            }
        }

        if (!anyCovered) return

        val mapXMat = Mat(rowCount, columnCount, CvType.CV_32FC1)
        val mapYMat = Mat(rowCount, columnCount, CvType.CV_32FC1)
        val weightMat = Mat(rowCount, columnCount, CvType.CV_32FC1)
        val warped = Mat()
        val warpedFloat = Mat()
        val weight3 = Mat()
        try {
            mapXMat.put(0, 0, mapX)
            mapYMat.put(0, 0, mapY)
            weightMat.put(0, 0, weights)

            // Lanczos rather than linear: the canvas is now often rendered
            // close to the frames' own resolution, and the sharper kernel keeps
            // the edge detail that a linear filter would soften.
            Imgproc.remap(
                frame.image,
                warped,
                mapXMat,
                mapYMat,
                Imgproc.INTER_LANCZOS4,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0),
            )
            warped.convertTo(warpedFloat, CvType.CV_32FC3)
            Core.merge(listOf(weightMat, weightMat, weightMat), weight3)
            Core.multiply(warpedFloat, weight3, warpedFloat)
            if (gain != 1f) {
                Core.multiply(warpedFloat, Scalar(gain.toDouble(), gain.toDouble(), gain.toDouble()), warpedFloat)
            }

            val target = Rect(columnStart, rowStart - bandTop, columnCount, rowCount)
            val colorRegion = colorSum.submat(target)
            val weightRegion = weightSum.submat(target)
            try {
                Core.add(colorRegion, warpedFloat, colorRegion)
                Core.add(weightRegion, weightMat, weightRegion)
            } finally {
                colorRegion.release()
                weightRegion.release()
            }
        } finally {
            mapXMat.release()
            mapYMat.release()
            weightMat.release()
            warped.release()
            warpedFloat.release()
            weight3.release()
        }
    }

    /** Divides the accumulated colour by its weight and stores the band. */
    private fun writeBand(
        colorSum: Mat,
        weightSum: Mat,
        canvas: Mat,
        bandTop: Int,
        bandHeight: Int,
        canvasWidth: Int,
    ) {
        val safeWeight = Mat()
        val weight3 = Mat()
        val normalized = Mat()
        try {
            // Uncovered pixels hold a zero colour sum, so dividing by the epsilon
            // leaves them black rather than producing a NaN.
            Core.add(weightSum, Scalar(WEIGHT_EPSILON), safeWeight)
            Core.merge(listOf(safeWeight, safeWeight, safeWeight), weight3)
            Core.divide(colorSum, weight3, normalized)

            val region = canvas.submat(Rect(0, bandTop, canvasWidth, bandHeight))
            try {
                normalized.convertTo(region, CvType.CV_8UC3)
            } finally {
                region.release()
            }
        } finally {
            safeWeight.release()
            weight3.release()
            normalized.release()
        }
    }

    /**
     * Splits a footprint's column range into runs of contiguous canvas columns.
     *
     * A footprint that crosses the ±180° seam is one unbroken band of longitude
     * but two blocks of canvas, so [block] is called twice for it and once for
     * everything else. The span never exceeds the canvas width, so there are
     * never more than two runs.
     */
    private inline fun forEachColumnRun(
        footprint: CanvasFootprint,
        canvasWidth: Int,
        block: (canvasColumn: Int, columnSpan: Int) -> Unit,
    ) {
        val span = min(footprint.columnSpan, canvasWidth)
        if (span <= 0) return
        val first = wrapColumn(footprint.startColumn, canvasWidth)
        val firstSpan = min(span, canvasWidth - first)
        block(first, firstSpan)
        val remainder = span - firstSpan
        if (remainder > 0) block(0, remainder)
    }
}
