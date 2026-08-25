package com.n30dyn4m1c.photosphere.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.n30dyn4m1c.photosphere.stitching.sampleSizeFor
import java.io.File

/**
 * The Android half of burst selection: decode each candidate downsampled —
 * the power-of-two subsampling keeps a full-resolution still from ever being
 * read whole just to score it — then let the shared scorer pick.
 */

/** The sharpest of [files], decoded and scored; the first on a tie. */
fun SharpnessSelection.pickSharpest(files: List<File>): File {
    require(files.isNotEmpty()) { "nothing to pick from" }
    if (files.size == 1) return files.first()
    return files.maxByOrNull { laplacianVarianceOf(it) } ?: files.first()
}

private fun SharpnessSelection.laplacianVarianceOf(file: File): Float {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 0f

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, SCORE_MAX_DIMENSION)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return 0f
    // Read the dimensions out before recycling: a recycled bitmap's
    // accessors are not contractually defined, and scoring against whatever
    // they happen to return would silently pick the wrong frame.
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
    bitmap.recycle()
    return laplacianVariance(pixels, width, height)
}
