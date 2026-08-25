package com.n30dyn4m1c.photosphere.stitching

/**
 * Platform-neutral RGB image: 8 bits per channel, interleaved, row-major.
 *
 * This replaces the two representations the Android-only pipeline carried —
 * `android.graphics.Bitmap` at the edges and an OpenCV `CV_8UC3` `Mat` inside —
 * with one plain buffer every target speaks. The layout matches what
 * `cvtColor(RGBA2RGB)` produced before, so the geometry and sampling code that
 * was written against those mats reads this buffer the same way: pixel
 * `(x, y)` channel `c` lives at `(y * width + x) * 3 + c`.
 *
 * There is no `release()`: the buffer is garbage-collected like any array.
 * Callers that used to free mats deterministically simply let these go out of
 * scope instead — the banded pipeline keeps at most a few alive at once.
 */
class RgbImage(
    val width: Int,
    val height: Int,
    /** Interleaved RGB, exactly [width] * [height] * 3 bytes. */
    val bytes: ByteArray,
) {
    init {
        require(width > 0 && height > 0) { "image must have extent" }
        require(bytes.size == width * height * 3) {
            "expected ${width * height * 3} bytes for ${width}x$height RGB, got ${bytes.size}"
        }
    }

    val pixelCount: Int get() = width * height

    fun copy(): RgbImage = RgbImage(width, height, bytes.copyOf())

    override fun toString(): String = "RgbImage(${width}x$height)"

    companion object {
        /** A black image of the given size — the renderer's starting canvas. */
        fun zeros(width: Int, height: Int): RgbImage =
            RgbImage(width, height, ByteArray(width * height * 3))
    }
}
