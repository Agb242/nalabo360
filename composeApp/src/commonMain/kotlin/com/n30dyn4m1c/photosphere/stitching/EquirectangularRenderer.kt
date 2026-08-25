package com.n30dyn4m1c.photosphere.stitching

import com.n30dyn4m1c.photosphere.toRadiansSafe
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * One frame ready to be painted onto the sphere: its pixels, where the camera
 * was, and the block of canvas it can reach.
 *
 * [image] belongs to whoever built the frame — the renderer reads it and never
 * mutates it.
 */
internal class PreparedFrame(
    val image: RgbImage,
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
 * inverse of the projection the lens performed. [ImageMath.remapLanczos] /
 * [ImageMath.remapBilinear] then do the sampling.
 *
 * Where frames overlap, the result is a multi-band blend rather than whichever
 * frame was painted last. The feather weight becomes each frame's mask, and the
 * mask and the frame are each split into a Laplacian/Gaussian pyramid: fine
 * detail is faded across a narrow cross-fade, broad illumination across a wide
 * one, so a seam that survives one band does not survive the one that carried
 * it. See [MultibandBlender] for the maths.
 *
 * With a [SeamWeights] assignment the per-pixel feather is replaced by the seam
 * weights: the winning frame of each pixel contributes at full strength and
 * every other frame contributes nothing, except for a few pixels either side of
 * each cut where the loser's contribution ramps in — seam carving, with the
 * multi-band machinery left to handle the narrow transition. Outside an overlap
 * the weights are still all on one frame, so single-coverage detail comes
 * through untouched either way. See [SeamFinder].
 *
 * **Memory.** A 4096-wide canvas needs 100 MB of float accumulator if it is
 * held all at once, which is exactly the kind of allocation that ends a stitch
 * on a mid-range phone. Every level is therefore built in horizontal bands:
 * only the band being accumulated exists in float, and each frame contributes
 * through the intersection of the band with its own footprint. The coarser
 * levels, which carry the wide cross-fade, live in float arrays a fraction of
 * the canvas. The total work is unchanged — every frame still touches each of
 * its pixels once at every level — but the peak is tens of megabytes rather
 * than a hundred.
 */
internal object EquirectangularRenderer {

    /** Rows accumulated at once. Sets the renderer's peak memory, with the width. */
    private const val BAND_HEIGHT = 128

    /** What a render produced, and how much of the sphere it reached. */
    class Rendered(
        /** The finished canvas. The caller owns it. */
        val canvas: RgbImage,
        /** Fraction of the canvas any frame reached, 0..1. */
        val coverage: Float,
    )

    /**
     * Renders [frames] into a [canvasWidth] × [canvasHeight] canvas.
     *
     * [gains], when given, holds one brightness multiplier per frame, aligned by
     * position with [frames]; it is applied before the overlaps are blended so
     * the exposure compensation lands inside the blend rather than on top of
     * it. [onBandComplete] is called with the number of units finished so far
     * and the total, for progress reporting; [checkCancelled] is called at
     * every band and level boundary and may throw to abandon the render.
     *
     * [pivotRatio] is [PivotModel.ratio]: how far the lens sat from the axis the
     * user turned about, as a fraction of the scene's distance. Zero treats the
     * lens as the pivot, which is what a tripod gives and what a hand-held
     * capture never quite does.
     *
     * [seams], when given, switches the overlaps from the wide cross-fade to the
     * seam-carved assignment (see [SeamWeights]): each pixel is painted by one
     * frame at full strength, and only a few pixels across each cut blend the
     * loser in. Without it the render behaves exactly as before.
     *
     * The four span/centre numbers describe the region of the sphere the canvas
     * holds (see [Equirectangular]); they default to a full 360°×180° sphere.
     * The canvas is expected to be sized to them — `canvasHeight` should be
     * `canvasWidth * latitudeSpan / longitudeSpan` for square pixels.
     */
    fun render(
        frames: List<PreparedFrame>,
        canvasWidth: Int,
        canvasHeight: Int,
        gains: FloatArray? = null,
        seams: SeamWeights? = null,
        pivotRatio: Double = 0.0,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
        onBandComplete: (completed: Int, total: Int) -> Unit = { _, _ -> },
        checkCancelled: () -> Unit = {},
    ): Rendered {
        val canvas = RgbImage.zeros(canvasWidth, canvasHeight)
        var coveredPixels = 0L
        // One set of scratch buffers for the whole render. A pole-covering frame
        // reaches the full canvas width, so a per-segment allocation would churn
        // tens of megabytes of float array per band across forty frames.
        val scratch = SegmentScratch()

        // Progress is reported in roughly uniform units: one per coarse level,
        // one per band of the coarsest two levels' reconstruction. The single
        // level below, if there is one, just swaps the units.
        val levelCount = MultibandBlender.levelCountFor(canvasHeight)
        val levelOneBandCount = if (levelCount >= 3) {
            val heightOne = MultibandBlender.levelSize(canvasWidth, canvasHeight, 1).second
            (heightOne + BAND_HEIGHT - 1) / BAND_HEIGHT
        } else 0
        val levelZeroBandCount = (canvasHeight + BAND_HEIGHT - 1) / BAND_HEIGHT
        val progressTotal = (levelCount - 1) + levelOneBandCount + levelZeroBandCount
        var progressDone = 0

        // The coarse accumulators live here so a failed render simply drops
        // them all to the garbage collector at once; there are no native
        // buffers to release deterministically any more.
        val coarse = ArrayList<BlendPlanes>(levelCount - 1)
        var acc: FloatArray? = null
        var levelOneAcc: FloatArray? = null

        if (levelCount <= 1) {
            // Degenerate canvas (shorter than the coarsest band): the
            // multi-band split has nowhere to go, so this is the plain
            // normalised mean the blend reduces to with a single level.
            for (band in 0 until levelZeroBandCount) {
                checkCancelled()
                val bandTop = band * BAND_HEIGHT
                val bandHeight = min(BAND_HEIGHT, canvasHeight - bandTop)
                val planes = BlendPlanes(canvasWidth, bandHeight)
                accumulateLevel(
                    frames = frames,
                    planes = planes,
                    levelCanvasWidth = canvasWidth,
                    levelCanvasHeight = bandHeight,
                    canvasRowOffset = bandTop,
                    sourceScale = 1,
                    seamCanvasHeight = canvasHeight,
                    gains = gains,
                    seams = seams,
                    pivotRatio = pivotRatio,
                    longitudeSpanDegrees = longitudeSpanDegrees,
                    centerLongitudeDegrees = centerLongitudeDegrees,
                    latitudeSpanDegrees = latitudeSpanDegrees,
                    centerLatitudeDegrees = centerLatitudeDegrees,
                    scratch = scratch,
                )
                coveredPixels += planes.countCoveredRows(0, bandHeight)
                planes.normalizeRowsInto(canvas, 0, bandHeight, destRow = bandTop)
                progressDone++
                onBandComplete(progressDone, progressTotal)
            }
        } else {
            // -- Level accumulation ------------------------------------------
            // Levels 1..L accumulate over their whole (small) canvas. Level
            // 0 accumulates band by band, later, once its coarser content
            // has been rebuilt.
            for (level in 1 until levelCount) {
                checkCancelled()
                val (width, height) = MultibandBlender.levelSize(canvasWidth, canvasHeight, level)
                val planes = BlendPlanes(width, height)
                accumulateLevel(
                    frames = frames,
                    planes = planes,
                    levelCanvasWidth = width,
                    levelCanvasHeight = height,
                    canvasRowOffset = 0,
                    sourceScale = 1 shl level,
                    seamCanvasHeight = height,
                    gains = gains,
                    seams = seams,
                    pivotRatio = pivotRatio,
                    longitudeSpanDegrees = longitudeSpanDegrees,
                    centerLongitudeDegrees = centerLongitudeDegrees,
                    latitudeSpanDegrees = latitudeSpanDegrees,
                    centerLatitudeDegrees = centerLatitudeDegrees,
                    scratch = scratch,
                )
                coarse += planes
                progressDone++
                onBandComplete(progressDone, progressTotal)
            }

            // -- Coarse reconstruction ----------------------------------------
            // Levels L down to 2 rebuild whole-canvas; they are small, and
            // the bands they expand into are a fraction of the canvas.
            acc = MultibandBlender.normalizeColor(coarse[levelCount - 2])
            for (level in (levelCount - 2) downTo 2) {
                checkCancelled()
                val current = checkNotNull(acc) { "coarse accumulator missing at level $level" }
                val (width, height) = MultibandBlender.levelSize(canvasWidth, canvasHeight, level)
                val (coarseWidth, coarseHeight) =
                    MultibandBlender.levelSize(canvasWidth, canvasHeight, level + 1)
                val next = MultibandBlender.reconstructBand(
                    colorBand = coarse[level - 1].color,
                    weightBand = coarse[level - 1].weight,
                    coarseAcc = current,
                    coarseColor = coarse[level].color,
                    coarseWeight = coarse[level].weight,
                    canvasWidth = width,
                    canvasHeight = height,
                    coarseWidth = coarseWidth,
                    coarseHeight = coarseHeight,
                    bandTop = 0,
                    bandHeight = height,
                ) ?: throw IllegalStateException("empty coarse band at level $level")
                acc = next
                // The level this iteration just folded in (level 3 or up) is
                // spent; level 2 is still needed by the level-1 bands, and
                // level 1 by the canvas.
                coarse[level] = EMPTY_PLANES_MARKER
            }

            // -- Level-1 reconstruction, banded -------------------------------
            val (levelOneWidth, levelOneHeight) =
                MultibandBlender.levelSize(canvasWidth, canvasHeight, 1)
            if (levelCount >= 3) {
                val (levelTwoWidth, levelTwoHeight) =
                    MultibandBlender.levelSize(canvasWidth, canvasHeight, 2)
                val levelOne = BlendPlanes(levelOneWidth, levelOneHeight)
                for (band in 0 until levelOneBandCount) {
                    checkCancelled()
                    val bandTop = band * BAND_HEIGHT
                    val bandHeight = min(BAND_HEIGHT, levelOneHeight - bandTop)
                    val (bandColor, bandWeight) = coarse[0].copyRows(bandTop, bandHeight)
                    val rebuilt = MultibandBlender.reconstructBand(
                        colorBand = bandColor,
                        weightBand = bandWeight,
                        coarseAcc = checkNotNull(acc),
                        coarseColor = coarse[1].color,
                        coarseWeight = coarse[1].weight,
                        canvasWidth = levelOneWidth,
                        canvasHeight = levelOneHeight,
                        coarseWidth = levelTwoWidth,
                        coarseHeight = levelTwoHeight,
                        bandTop = bandTop,
                        bandHeight = bandHeight,
                    )
                    if (rebuilt != null) {
                        rebuilt.copyInto(levelOne.color, bandTop * levelOneWidth * 3)
                    }
                    progressDone++
                    onBandComplete(progressDone, progressTotal)
                }
                // The level-2 content has done its job; level 1's raw sums stay
                // in coarse[0] for the level-0 bands below.
                acc = null
                coarse[1] = EMPTY_PLANES_MARKER
                // The rebuilt bands live in levelOne.color; that plane *is*
                // the reconstructed level one now (mirrors the original's
                // single 3-channel `levelOneAcc` mat).
                levelOneAcc = levelOne.color
            } else {
                // Two levels only: acc already is level 1's reconstruction.
                levelOneAcc = acc
            }

            // -- Level-0 reconstruction, banded, into the canvas --------------
            val levelOne = checkNotNull(levelOneAcc) { "level-one accumulator was not built" }
            for (band in 0 until levelZeroBandCount) {
                checkCancelled()
                val bandTop = band * BAND_HEIGHT
                val bandHeight = min(BAND_HEIGHT, canvasHeight - bandTop)
                val planes = BlendPlanes(canvasWidth, bandHeight)
                accumulateLevel(
                    frames = frames,
                    planes = planes,
                    levelCanvasWidth = canvasWidth,
                    levelCanvasHeight = bandHeight,
                    canvasRowOffset = bandTop,
                    sourceScale = 1,
                    seamCanvasHeight = canvasHeight,
                    gains = gains,
                    seams = seams,
                    pivotRatio = pivotRatio,
                    longitudeSpanDegrees = longitudeSpanDegrees,
                    centerLongitudeDegrees = centerLongitudeDegrees,
                    latitudeSpanDegrees = latitudeSpanDegrees,
                    centerLatitudeDegrees = centerLatitudeDegrees,
                    scratch = scratch,
                )
                coveredPixels += planes.countCoveredRows(0, bandHeight)
                val (bandColor, bandWeight) = planes.copyRows(0, bandHeight)
                val rebuilt = MultibandBlender.reconstructBand(
                    colorBand = bandColor,
                    weightBand = bandWeight,
                    coarseAcc = levelOne,
                    coarseColor = coarse[0].color,
                    coarseWeight = coarse[0].weight,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    coarseWidth = levelOneWidth,
                    coarseHeight = levelOneHeight,
                    bandTop = bandTop,
                    bandHeight = bandHeight,
                )
                if (rebuilt != null) {
                    writeFloatsTo(canvas, rebuilt, bandTop, bandHeight)
                }
                progressDone++
                onBandComplete(progressDone, progressTotal)
            }
        }

        val total = canvasWidth.toLong() * canvasHeight
        return Rendered(canvas, coverage = (coveredPixels.toDouble() / total).toFloat())
    }

    /**
     * Writes a rebuilt band (`canvasWidth × rowCount × 3` floats) into
     * [canvas] starting at canvas row [topRow], rounding to 8-bit RGB.
     */
    private fun writeFloatsTo(canvas: RgbImage, floats: FloatArray, topRow: Int, rowCount: Int) {
        var i = 0
        var o = topRow * canvas.width * 3
        val pixels = rowCount * canvas.width
        for (p in 0 until pixels) {
            canvas.bytes[o] = ImageMath.roundToByte(floats[i].toDouble())
            canvas.bytes[o + 1] = ImageMath.roundToByte(floats[i + 1].toDouble())
            canvas.bytes[o + 2] = ImageMath.roundToByte(floats[i + 2].toDouble())
            i += 3
            o += 3
        }
    }

    /**
     * Sentinel replacing spent coarse levels. The old pipeline released mats
     * progressively; here dropping the reference is what frees the arrays, and
     * this marker keeps the "already spent" bookkeeping visible.
     */
    private val EMPTY_PLANES_MARKER = BlendPlanes(1, 1)

    /**
     * Accumulates every frame that reaches one band of one pyramid level into
     * [planes].
     *
     * The plane holds [levelCanvasHeight] rows. On every level except the
     * banded finest one the plane *is* the whole level and [canvasRowOffset]
     * is zero; on the finest level the plane is one horizontal band and
     * [canvasRowOffset] is the band's first row on the real canvas. Footprint
     * rows stay in their level's own coordinates throughout — for the finest
     * level those are canvas rows, which is what latitude and seam lookup
     * expect — and are translated onto the plane only when writing.
     * [sourceScale] is `2^level`: at level 0 it is 1 and the frames are
     * sampled whole; above that each frame is downsampled to a quarter the
     * area first, so the warp lands on the level's coarse Gaussian rather
     * than a decimated copy of the full-res one.
     */
    private fun accumulateLevel(
        frames: List<PreparedFrame>,
        planes: BlendPlanes,
        levelCanvasWidth: Int,
        levelCanvasHeight: Int,
        canvasRowOffset: Int,
        sourceScale: Int,
        seamCanvasHeight: Int,
        gains: FloatArray?,
        seams: SeamWeights?,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        scratch: SegmentScratch,
    ) {
        val divisor = sourceScale.toDouble()
        val lanczos = sourceScale == 1
        for (frameIndex in frames.indices) {
            val frame = frames[frameIndex]
            val footprint = scaleFootprint(frame.footprint, sourceScale)
            if (footprint.isEmpty) continue

            val rowStart = max(canvasRowOffset, footprint.startRow)
            val rowEnd = min(canvasRowOffset + levelCanvasHeight, footprint.startRow + footprint.rowSpan)
            if (rowEnd <= rowStart) continue

            val ownsSource = sourceScale > 1
            val source = if (ownsSource) {
                ImageMath.resizeArea(
                    frame.image,
                    (frame.intrinsics.widthPx / sourceScale).coerceAtLeast(1),
                    (frame.intrinsics.heightPx / sourceScale).coerceAtLeast(1),
                )
            } else {
                frame.image
            }
            try {
                forEachColumnRun(
                    footprint,
                    levelCanvasWidth,
                    longitudeSpanDegrees,
                ) { canvasColumn, columnSpan ->
                    accumulateSegment(
                        frame = frame,
                        frameIndex = frameIndex,
                        source = source,
                        sourceDivisor = divisor,
                        lanczos = lanczos,
                        planes = planes,
                        canvasWidth = levelCanvasWidth,
                        rowStart = rowStart,
                        rowCount = rowEnd - rowStart,
                        columnStart = canvasColumn,
                        columnCount = columnSpan,
                        gain = gains?.getOrNull(frameIndex) ?: 1f,
                        seams = seams,
                        seamCanvasHeight = seamCanvasHeight,
                        canvasRowOffset = canvasRowOffset,
                        pivotRatio = pivotRatio,
                        longitudeSpanDegrees = longitudeSpanDegrees,
                        centerLongitudeDegrees = centerLongitudeDegrees,
                        latitudeSpanDegrees = latitudeSpanDegrees,
                        centerLatitudeDegrees = centerLatitudeDegrees,
                        scratch = scratch,
                    )
                }
            } finally {
                // A downsampled source is plain garbage-collected memory; the
                // `ownsSource` flag survives only as documentation of the old
                // deterministic-release discipline.
            }
        }
    }

    /**
     * Projects one rectangle of canvas back through a frame and adds it in.
     *
     * The two trigonometric factors of a direction separate — longitude varies
     * only across columns and latitude only down rows — so the per-column parts
     * are computed once for the whole rectangle and the inner loop is left with
     * a couple of multiplies per pixel.
     *
     * [source] is the frame's image, either whole ([sourceDivisor] 1) or
     * downsampled by [sourceDivisor] — the map coordinates are divided by it so
     * they land in the right pixels either way. The feather weight is measured
     * against the *ideal* (pinhole) pixel at full resolution, which is
     * scale-invariant.
     */
    private fun accumulateSegment(
        frame: PreparedFrame,
        frameIndex: Int,
        source: RgbImage,
        sourceDivisor: Double,
        lanczos: Boolean,
        planes: BlendPlanes,
        canvasWidth: Int,
        rowStart: Int,
        rowCount: Int,
        columnStart: Int,
        columnCount: Int,
        gain: Float,
        seams: SeamWeights?,
        seamCanvasHeight: Int,
        canvasRowOffset: Int,
        pivotRatio: Double,
        longitudeSpanDegrees: Float,
        centerLongitudeDegrees: Float,
        latitudeSpanDegrees: Float,
        centerLatitudeDegrees: Float,
        scratch: SegmentScratch,
    ) {
        if (rowCount <= 0 || columnCount <= 0) return

        val basis = frame.basis
        val intrinsics = frame.intrinsics
        val centreX = intrinsics.centerXPx
        val centreY = intrinsics.centerYPx
        val maxColumn = intrinsics.widthPx - 1.0
        val maxRow = intrinsics.heightPx - 1.0
        val radial = intrinsics.radial

        scratch.prepare(rowCount, columnCount)

        // Longitude is periodic, so a column that was unwrapped past the seam
        // gives the same direction as the column it wraps onto — the canvas
        // column can be used directly here. On a region canvas this holds too:
        // only [forEachColumnRun] decides whether the seam wraps or clips.
        val forwardHorizontal = scratch.forwardHorizontal
        val rightHorizontal = scratch.rightHorizontal
        val upHorizontal = scratch.upHorizontal
        for (offset in 0 until columnCount) {
            val longitude = toRadiansSafe(
                Equirectangular.longitudeDegrees(
                    columnStart + offset,
                    canvasWidth,
                    longitudeSpanDegrees,
                    centerLongitudeDegrees,
                )
            )
            val sinLongitude = sin(longitude)
            val cosLongitude = cos(longitude)
            forwardHorizontal[offset] = sinLongitude * basis.forwardX + cosLongitude * basis.forwardY
            rightHorizontal[offset] = sinLongitude * basis.rightX + cosLongitude * basis.rightY
            upHorizontal[offset] = sinLongitude * basis.upX + cosLongitude * basis.upY
        }

        val mapX = scratch.mapX
        val mapY = scratch.mapY
        val weights = scratch.weights
        var anyCovered = false

        for (rowOffset in 0 until rowCount) {
            val latitude = toRadiansSafe(
                Equirectangular.latitudeDegrees(
                    rowStart + rowOffset,
                    seamCanvasHeight,
                    latitudeSpanDegrees,
                    centerLatitudeDegrees,
                )
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
                    // makes remap paint black, and the zero weight keeps it out
                    // of the blend regardless.
                    mapX[index] = -1f
                    mapY[index] = -1f
                    weights[index] = 0f
                    continue
                }

                val lateral = cosLatitude * rightHorizontal[columnOffset] +
                    sinLatitude * basis.rightZ
                val vertical = cosLatitude * upHorizontal[columnOffset] + sinLatitude * basis.upZ

                // The lens model in two steps: the ideal pinhole pixel, then the
                // radial push to where the lens actually recorded it. The ideal
                // pixel is what the feather measures against — it is proportional
                // to the angle off the optical axis — while the distorted pixel
                // is what remap samples.
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
                    weights[index] = 0f
                    continue
                }

                mapX[index] = (sourceColumn / sourceDivisor).toFloat()
                mapY[index] = (sourceRow / sourceDivisor).toFloat()
                val weight = if (seams != null) {
                    // Seam carving: the winner paints at full strength, the
                    // loser only within a few pixels of the cut. The canvas
                    // row/column are the level's own coordinates, which the
                    // seam grid maps back from.
                    seams.weightFor(
                        frameIndex,
                        rowStart + rowOffset,
                        columnStart + columnOffset,
                        sourceDivisor.toInt(),
                    )
                } else {
                    featherWeight(idealColumn, idealRow, centreX, centreY).toFloat()
                }
                weights[index] = weight
                if (weight > 0f) anyCovered = true
            }
        }

        if (!anyCovered) return

        // Lanczos on the finest level, linear above it: the canvas is now
        // often rendered close to the frames' own resolution, and the sharper
        // kernel keeps the edge detail that a linear filter would soften.
        // Coarse levels sample an already-downsampled source, where linear is
        // all there is to keep.
        val warped = if (lanczos) {
            ImageMath.remapLanczos(source, mapX, mapY, columnCount, rowCount)
        } else {
            ImageMath.remapBilinear(source, mapX, mapY, columnCount, rowCount)
        }
        planes.addSegment(
            rgb = warped,
            segmentWeights = weights,
            gain = gain,
            offsetX = columnStart,
            offsetY = rowStart - canvasRowOffset,
            segmentWidth = columnCount,
            segmentHeight = rowCount,
        )
    }

    /**
     * A footprint in level-[scale] (power-of-two) canvas coordinates.
     *
     * The level-0 footprint is divided by [scale] with the bounds pushed out a
     * row and a column, so the coarse warp never misses a border the sampled
     * footprint was one pixel short of — the projection's own bounds check
     * trims any overreach.
     */
    internal fun scaleFootprint(footprint: CanvasFootprint, scale: Int): CanvasFootprint {
        if (scale <= 1) return footprint
        val startColumn = footprint.startColumn.floorDiv(scale) - 1
        val endColumn = (footprint.startColumn + footprint.columnSpan - 1).floorDiv(scale) + 2
        val startRow = footprint.startRow.floorDiv(scale) - 1
        val endRow = (footprint.startRow + footprint.rowSpan - 1).floorDiv(scale) + 2
        return CanvasFootprint(
            startColumn = startColumn,
            columnSpan = endColumn - startColumn,
            startRow = startRow,
            rowSpan = endRow - startRow,
        )
    }

    /**
     * Splits a footprint's column range into runs of contiguous canvas columns.
     *
     * A footprint that crosses the ±180° seam is one unbroken band of longitude
     * but two blocks of canvas, so [block] is called twice for it and once for
     * everything else. That seam is only periodic when the canvas holds a full
     * turn of longitude: a region canvas (fewer than 360° of it) has an edge
     * that is a real cut, so a footprint running past either edge is *clipped*
     * there instead — the far side is outside the captured region and stays
     * black. The span never exceeds the canvas width, so a full-turn canvas
     * never produces more than two runs.
     */
    private inline fun forEachColumnRun(
        footprint: CanvasFootprint,
        canvasWidth: Int,
        longitudeSpanDegrees: Float,
        block: (canvasColumn: Int, columnSpan: Int) -> Unit,
    ) {
        val span = min(footprint.columnSpan, canvasWidth)
        if (span <= 0) return
        if (longitudeSpanDegrees >= 360f) {
            val first = wrapColumn(footprint.startColumn, canvasWidth)
            val firstSpan = min(span, canvasWidth - first)
            block(first, firstSpan)
            val remainder = span - firstSpan
            if (remainder > 0) block(0, remainder)
        } else {
            val first = max(footprint.startColumn, 0)
            val last = min(footprint.startColumn + span, canvasWidth)
            if (last > first) block(first, last - first)
        }
    }

    /**
     * Grow-only working buffers for one segment's projection.
     *
     * A render walks every frame across every band and level, and each visit
     * needs three float arrays the size of the rectangle it is painting plus
     * three the width of it. Allocating those per visit is tens of megabytes of
     * short-lived array per band on a wide canvas — enough garbage to stall a
     * stitch on a mid-range phone. The buffers are only ever grown, so after
     * the first few segments the render allocates nothing at all.
     *
     * Confined to the rendering thread; a render is single-threaded by
     * construction, and one scratch is created per [render] call.
     */
    private class SegmentScratch {
        var mapX: FloatArray = FloatArray(0)
            private set
        var mapY: FloatArray = FloatArray(0)
            private set
        var weights: FloatArray = FloatArray(0)
            private set
        var forwardHorizontal: DoubleArray = DoubleArray(0)
            private set
        var rightHorizontal: DoubleArray = DoubleArray(0)
            private set
        var upHorizontal: DoubleArray = DoubleArray(0)
            private set

        fun prepare(rowCount: Int, columnCount: Int) {
            val pixels = rowCount * columnCount
            if (mapX.size < pixels) {
                mapX = FloatArray(pixels)
                mapY = FloatArray(pixels)
                weights = FloatArray(pixels)
            }
            if (forwardHorizontal.size < columnCount) {
                forwardHorizontal = DoubleArray(columnCount)
                rightHorizontal = DoubleArray(columnCount)
                upHorizontal = DoubleArray(columnCount)
            }
        }
    }
}
