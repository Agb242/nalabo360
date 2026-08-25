package com.n30dyn4m1c.photosphere.camera

/**
 * Picks the sharpest of a burst of otherwise-identical frames.
 *
 * Every shot in a burst is captured at the same locked exposure, focus and
 * colour, so they differ only in what handheld motion did to them. The one to
 * keep is the sharpest, judged by the variance of the Laplacian — the classic
 * focus/sharpness metric: edges push the Laplacian response up, so a frame with
 * crisp edges scores higher than one blurred by shake. (The platform decode-
 * and-pick half lives beside this scorer; see `pickSharpest` on Android.)
 *
 * Frames are scored downsampled. The copies of a scene are nearly identical, so
 * a score only has to be right about *which* is sharper, not about how sharp
 * either one is; a coarse decode is enough and costs a few milliseconds per
 * frame.
 */
object SharpnessSelection {

    /** Long edge a frame is decoded to before scoring. */
    const val SCORE_MAX_DIMENSION: Int = 320

    /**
     * Variance of the Laplacian response over [pixels], a 1-D array laid out
     * row-major as [width] × [height] ARGB ints.
     *
     * Pure, so it can be unit-tested without a device. Higher is sharper.
     */
    fun laplacianVariance(pixels: IntArray, width: Int, height: Int): Float {
        require(pixels.size >= width * height) { "pixel buffer is too small" }
        if (width < 3 || height < 3) return 0f

        var sum = 0.0
        var sumSquared = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val lap = luma(pixels[i - 1]) + luma(pixels[i + 1]) +
                    luma(pixels[i - width]) + luma(pixels[i + width]) -
                    4.0 * luma(pixels[i])
                sum += lap
                sumSquared += lap * lap
                count++
            }
        }
        if (count == 0) return 0f

        val mean = sum / count
        return ((sumSquared / count) - mean * mean).toFloat()
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
