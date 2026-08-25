package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.floor

/**
 * The renderer's per-level accumulators: a weighted colour sum and its mask
 * sum, held as flat float arrays instead of OpenCV mats.
 *
 * The old pipeline accumulated into `CV_32FC3`/`CV_32FC1` mats through submat
 * views. The layout here is identical — row-major, colour interleaved — so the
 * maths is a direct translation; only the addressing became explicit.
 */
internal class BlendPlanes(val width: Int, val height: Int) {

    /** Weighted RGB sums, [width] × [height] × 3 floats, all zero initially. */
    val color = FloatArray(width * height * 3)

    /** Mask sums, one float per pixel. */
    val weight = FloatArray(width * height)

    /**
     * Folds one warped segment into the planes at ([offsetX], [offsetY]).
     *
     * [rgb] holds the remapped pixels ([segmentWidth] × [segmentHeight], RGB),
     * [segmentWeights] the blend weight per pixel — feather or seam lookup —
     * already scaled by nothing else. Pixels whose weight is zero are skipped:
     * adding black times zero changes neither plane, and skipping them keeps
     * the uncovered majority of a wide segment free.
     */
    fun addSegment(
        rgb: ByteArray,
        segmentWeights: FloatArray,
        gain: Float,
        offsetX: Int,
        offsetY: Int,
        segmentWidth: Int,
        segmentHeight: Int,
    ) {
        val pw = width
        var s = 0
        for (row in 0 until segmentHeight) {
            var base = ((offsetY + row) * pw + offsetX) * 3
            val weightBase = (offsetY + row) * pw + offsetX
            for (col in 0 until segmentWidth) {
                val w = segmentWeights[s]
                if (w != 0f) {
                    val weighted = w * gain
                    color[base] += (rgb[s * 3].toInt() and 0xff) * weighted
                    color[base + 1] += (rgb[s * 3 + 1].toInt() and 0xff) * weighted
                    color[base + 2] += (rgb[s * 3 + 2].toInt() and 0xff) * weighted
                    weight[weightBase + col] += w
                }
                s++
                base += 3
            }
        }
    }

    /**
     * Counts pixels of rows `[topRow, topRow + rowCount)` whose weight sum is
     * nonzero — the coverage measure the render reports.
     */
    fun countCoveredRows(topRow: Int, rowCount: Int): Long {
        var covered = 0L
        val start = topRow * width
        val end = start + rowCount * width
        for (i in start until end) {
            if (weight[i] != 0f) covered++
        }
        return covered
    }

    /**
     * Writes the normalised blend `color / (weight + ε)` of rows
     * `[topRow, topRow + rowCount)` into [out] as 8-bit RGB. The band lands at
     * [destRow] in [out] — the same row as [topRow] when the plane is the whole
     * image, offset when it is one horizontal band of a banded canvas.
     */
    fun normalizeRowsInto(out: RgbImage, topRow: Int, rowCount: Int, destRow: Int = topRow) {
        val pw = width
        for (row in 0 until rowCount) {
            var c = ((topRow + row) * pw) * 3
            val wBase = (topRow + row) * pw
            var o = ((destRow + row) * out.width) * 3
            for (col in 0 until pw) {
                val denominator = weight[wBase + col] + MultibandBlender.WEIGHT_EPSILON.toFloat()
                out.bytes[o] = ImageMath.roundToByte(color[c] / denominator)
                out.bytes[o + 1] = ImageMath.roundToByte(color[c + 1] / denominator)
                out.bytes[o + 2] = ImageMath.roundToByte(color[c + 2] / denominator)
                c += 3
                o += 3
            }
        }
    }

    /**
     * Copies rows `[topRow, topRow + rowCount)` out as band-local arrays, the
     * shape [MultibandBlender.reconstructBand] consumes.
     */
    fun copyRows(topRow: Int, rowCount: Int): Pair<FloatArray, FloatArray> {
        val colorStart = topRow * width * 3
        val bandColor = color.copyOfRange(colorStart, colorStart + rowCount * width * 3)
        val weightStart = topRow * width
        val bandWeight = weight.copyOfRange(weightStart, weightStart + rowCount * width)
        return bandColor to bandWeight
    }
}

/**
 * Burt–Adelson multi-band (Laplacian pyramid) blending, in the streaming form
 * the renderer's banded canvas requires.
 *
 * **Why not the feathered mean.** A cross-fade resolves an overlap by fading
 * detail across the whole width of it, so a seam that is even a pixel off
 * shows up twice: the transition is so wide that both copies of an edge are
 * visible inside it. Multi-band blending splits every frame into frequency
 * bands and blends those bands with *progressively wider* masks — a narrow
 * mask for the fine bands, a wide one for the coarse bands. Fine detail is
 * faded across the few pixels the frames genuinely disagree on, while broad
 * illumination is faded across the whole overlap. A seam disappears because at
 * every scale the transition is narrower than the detail it carries.
 *
 * **The maths.** Each frame is decomposed into a Laplacian pyramid
 * `L_l = G_l − EXPAND(G_{l+1})` ending in a coarse Gaussian `G_L`, and its
 * feather mask into a Gaussian pyramid `M_l`. Every band is blended by the
 * normalised weighted mean, and the bands are added back from coarse to fine:
 *
 * ```
 * acc_L = Σ G_i,L·M_i,L / Σ M_i,L
 * acc_l = EXPAND(acc_{l+1}) + Σ L_i,l·M_i,l / Σ M_i,l
 * ```
 *
 * `acc_0` is the finished blend. For a single frame the recurrence collapses
 * to the pyramid reconstruction identity `G_0 = G_L + Σ EXPAND(L_l)` — the
 * frame itself — so a lone frame comes through untouched; in an overlap each
 * band is the feathered mean *restricted to one band*, which is exactly the
 * property that makes multi-band work.
 *
 * **What this object owns.** The pyramid arithmetic: how many levels a canvas
 * warrants, the EXPAND step (a bilinear resize of float planes, standing in
 * for `Imgproc.resize`), the normalised division, and [reconstructBand], the
 * core recurrence evaluated over one horizontal band. It never touches a
 * frame's pixels — the renderer accumulates each level's weighted colour and
 * weight sums in [BlendPlanes], hands them here to be rebuilt, and paints the
 * result.
 */
internal object MultibandBlender {

    /**
     * Most pyramid levels a canvas is split into. Each extra level is another
     * full pass over the frames at a quarter of the size of the last, so
     * beyond ~6 levels the wide cross-fade gains nothing while the passes keep
     * costing. Six levels over a 2048-tall canvas leave a 64-row coarse
     * Gaussian, which is already deep into "broad illumination" territory.
     */
    const val MAX_LEVELS = 6

    /**
     * Added to the weight sum before the normalised division, so an uncovered
     * pixel divides 0 by ε rather than 0 by 0. Small enough not to move real
     * pixels, whose weights are orders of magnitude larger.
     */
    const val WEIGHT_EPSILON = 1e-6

    /**
     * How many bands a [canvasHeight]-tall canvas splits into (levels `0..n-1`).
     *
     * The coarsest band should still be a meaningful Gaussian of the frames —
     * about 8 rows tall — so the count is `log2(height / 8)` capped at
     * [MAX_LEVELS]. A canvas shorter than 8 rows keeps a single level, which
     * degrades to the feathered mean the renderer fell back to.
     */
    fun levelCountFor(canvasHeight: Int): Int {
        if (canvasHeight <= 0) return 1
        var height = canvasHeight
        var levels = 1
        while (height > 8 && levels < MAX_LEVELS) {
            height /= 2
            levels++
        }
        return levels
    }

    /** Width and height of pyramid level [level] of a [width]×[height] canvas. */
    fun levelSize(width: Int, height: Int, level: Int): Pair<Int, Int> {
        val shift = level.coerceAtLeast(0)
        return (width shr shift).coerceAtLeast(1) to (height shr shift).coerceAtLeast(1)
    }

    /**
     * The rectangles one band's reconstruction touches, so the work stays
     * in bounds.
     *
     * A band of canvas rows `[bandTop, bandTop + bandRows)` is rebuilt from
     * the coarser level's rows that cover it, `[coarseTop, coarseTop + coarseRows)`,
     * upsampled to [upRows] rows. [localTop] is where the band starts inside
     * the upsampled region.
     *
     * The upsampled region is sized to *exactly* [localTop] + [bandRows] rows,
     * not the naive `2 × coarseRows`: a canvas height that is not a power of
     * two gives odd level heights, where the last band's rows reach further
     * than a clean ×2 of its coarse rows (the topmost fine rows all read the
     * last coarse row, clamped). Sizing the region that way keeps every crop
     * in bounds and matches the clamped full-level upsample at the edge.
     *
     * Returns null when the band is empty or reaches nothing in the coarser
     * level.
     */
    fun bandGeometry(
        bandTop: Int,
        bandHeight: Int,
        canvasHeight: Int,
        coarseHeight: Int,
    ): BandGeometry? {
        val bandRows = minOf(bandHeight, canvasHeight - bandTop)
        if (bandRows <= 0) return null
        val coarseTop = (bandTop / 2 - 1).coerceIn(0, coarseHeight)
        val coarseBottom = ((bandTop + bandRows) / 2 + 1).coerceIn(coarseTop, coarseHeight)
        val coarseRows = coarseBottom - coarseTop
        if (coarseRows <= 0) return null
        val localTop = bandTop - coarseTop * 2
        return BandGeometry(
            bandRows = bandRows,
            coarseTop = coarseTop,
            coarseRows = coarseRows,
            localTop = localTop,
            upRows = localTop + bandRows,
        )
    }

    /**
     * The layout one band's reconstruction uses. All rects derived from it are
     * in bounds by construction: `coarseTop + coarseRows ≤ coarseHeight` and
     * `localTop + bandRows = upRows`.
     */
    class BandGeometry(
        val bandRows: Int,
        val coarseTop: Int,
        val coarseRows: Int,
        val localTop: Int,
        val upRows: Int,
    )

    /**
     * The normalised image `color / (weight + ε)` of whole planes, the single-
     * channel weight broadcast across colour — the coarsest level's entire
     * blend, and the final step of every other level's recurrence.
     */
    fun normalizeColor(planes: BlendPlanes): FloatArray {
        val pixels = planes.width * planes.height
        val out = FloatArray(pixels * 3)
        val epsilon = WEIGHT_EPSILON.toFloat()
        for (p in 0 until pixels) {
            val denominator = planes.weight[p] + epsilon
            val c = p * 3
            out[c] = planes.color[c] / denominator
            out[c + 1] = planes.color[c + 1] / denominator
            out[c + 2] = planes.color[c + 2] / denominator
        }
        return out
    }

    /**
     * Reconstructs one horizontal band of a pyramid level.
     *
     * [colorBand]/[weightBand] are this level's weighted sums over the band's
     * rows (`canvasWidth` × [bandHeight]); obtain them from
     * [BlendPlanes.copyRows]. [coarseAcc], [coarseColor] and [coarseWeight]
     * are the next-coarser level's *reconstructed* image, colour sum and
     * weight sum — full planes at [coarseWidth] × [coarseHeight]. The band
     * occupies canvas rows `[bandTop, bandTop + bandHeight)`.
     *
     * The recurrence, in the streaming form the memory budget demands:
     *
     * ```
     * acc_l = EXPAND(acc_{l+1}) + (PWarp_l − EXPAND(PWarp_{l+1})·r_l) / (WSum_l + ε)
     * r_l   = WSum_l / (EXPAND(WSum_{l+1}) + ε)
     * ```
     *
     * with `PWarp_l = Σ W_i,l·M_i,l` and `WSum_l = Σ M_i,l` the renderer's
     * accumulators. The per-frame Laplacian sum `Σ L_i,l·M_i,l` is recovered
     * from them rather than summed per frame — that would hold a whole pyramid
     * per frame — and the `r_l` factor is what keeps that recovery honest at a
     * frame's border. Without it, the upsampled coarse content stays nonzero
     * one pixel past where the fine mask has already gone to zero, and a pixel
     * the frames just stop covering divides a nonzero band by ~ε. Scaling the
     * upsampled term by `r_l` makes the numerator vanish exactly where
     * `WSum_l` does, so uncovered pixels fall back to the already-reconstructed
     * coarser content instead of dividing by nothing.
     *
     * Returns the reconstructed band as `canvasWidth × bandRows × 3` floats,
     * owned by the caller — or null when the band is empty.
     */
    fun reconstructBand(
        colorBand: FloatArray,
        weightBand: FloatArray,
        coarseAcc: FloatArray,
        coarseColor: FloatArray,
        coarseWeight: FloatArray,
        canvasWidth: Int,
        canvasHeight: Int,
        coarseWidth: Int,
        coarseHeight: Int,
        bandTop: Int,
        bandHeight: Int,
    ): FloatArray? {
        val bandRows = minOf(bandHeight, canvasHeight - bandTop)
        if (bandRows <= 0) return null

        // This level's rows come from the coarser level's rows that cover
        // them — [bandTop/2 − 1, (bandTop+bandRows)/2 + 1] — upsampled back.
        val geometry = bandGeometry(bandTop, bandHeight, canvasHeight, coarseHeight)
            ?: return null
        val epsilon = WEIGHT_EPSILON.toFloat()

        // Extract the covering coarse region once, then bilinear-upsample it to
        // `canvasWidth × upRows`. This is the EXPAND step.
        val accUp = expandRows(coarseAcc, coarseWidth, geometry.coarseTop, geometry.coarseRows, canvasWidth, geometry.upRows, 3)
        val colorUp = expandRows(coarseColor, coarseWidth, geometry.coarseTop, geometry.coarseRows, canvasWidth, geometry.upRows, 3)
        val weightUp = expandRows(coarseWeight, coarseWidth, geometry.coarseTop, geometry.coarseRows, canvasWidth, geometry.upRows, 1)

        val result = FloatArray(canvasWidth * bandRows * 3)
        val localTop = geometry.localTop
        for (row in 0 until bandRows) {
            val bandRowBase = row * canvasWidth
            val upRowBase = (localTop + row) * canvasWidth
            for (col in 0 until canvasWidth) {
                val fineWeight = weightBand[bandRowBase + col]
                val expandedWeight = weightUp[upRowBase + col]
                val ratio = fineWeight / (expandedWeight + epsilon)
                val fineBase = (bandRowBase + col) * 3
                val upBase = (upRowBase + col) * 3
                val outBase = fineBase
                for (c in 0 until 3) {
                    // The band of the Laplacian sum: PWarp_l − EXPAND(PWarp_{l+1})·r_l,
                    // divided by WSum_l + ε, folded onto the coarser content.
                    val laplacian = colorBand[fineBase + c] - colorUp[upBase + c] * ratio
                    result[outBase + c] = accUp[upBase + c] + laplacian / (fineWeight + epsilon)
                }
            }
        }
        return result
    }

    /**
     * The EXPAND step: bilinear-upsamples rows `[topRow, topRow + rowCount)` of
     * a float plane to [outWidth] × [outHeight], [channels] interleaved.
     * Edge taps clamp, matching how the old clamped-region resize behaved at
     * the canvas border.
     */
    private fun expandRows(
        source: FloatArray,
        sourceWidth: Int,
        topRow: Int,
        rowCount: Int,
        outWidth: Int,
        outHeight: Int,
        channels: Int,
    ): FloatArray {
        val out = FloatArray(outWidth * outHeight * channels)
        val scaleX = sourceWidth.toDouble() / outWidth
        val scaleY = rowCount.toDouble() / outHeight
        for (dy in 0 until outHeight) {
            val fy = (dy + 0.5) * scaleY - 0.5
            val y0 = (ImageMath.clamp(floor(fy).toInt(), 0, rowCount - 1) + topRow) * sourceWidth
            val y1 = (ImageMath.clamp(floor(fy).toInt() + 1, 0, rowCount - 1) + topRow) * sourceWidth
            val wy = (fy - floor(fy)).toFloat()
            for (dx in 0 until outWidth) {
                val fx = (dx + 0.5) * scaleX - 0.5
                val x0 = ImageMath.clamp(floor(fx).toInt(), 0, sourceWidth - 1)
                val x1 = ImageMath.clamp(x0 + 1, 0, sourceWidth - 1)
                val wx = (fx - floor(fx)).toFloat()
                val i00 = (y0 + x0) * channels
                val i01 = (y0 + x1) * channels
                val i10 = (y1 + x0) * channels
                val i11 = (y1 + x1) * channels
                val o = (dy * outWidth + dx) * channels
                for (c in 0 until channels) {
                    val top = source[i00 + c] * (1f - wx) + source[i01 + c] * wx
                    val bottom = source[i10 + c] * (1f - wx) + source[i11 + c] * wx
                    out[o + c] = top * (1f - wy) + bottom * wy
                }
            }
        }
        return out
    }
}
