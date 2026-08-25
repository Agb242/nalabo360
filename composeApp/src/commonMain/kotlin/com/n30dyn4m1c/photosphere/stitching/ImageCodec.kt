package com.n30dyn4m1c.photosphere.stitching

import okio.Path
import okio.FileSystem

/**
 * The JPEG entry and exit points of the pipeline — the only image operations
 * that genuinely need platform machinery (hardware-accelerated decoders, the
 * camera stack's own encoders).
 *
 * Everything between decode and encode — geometry, warping, seams, blending,
 * unsharp — is pure Kotlin on [RgbImage] and identical on every platform.
 */
interface ImageCodec {

    /**
     * Decodes the JPEG at [path], no longer than [maxLongEdge] on its long
     * edge, as an [RgbImage]. EXIF orientation is *not* applied here; callers
     * read it with [JpegOrientation] and apply it explicitly, so the fallback
     * rotation logic sees the raw truth. Returns null when the file is
     * missing or unreadable.
     */
    fun decodeJpeg(path: Path, maxLongEdge: Int): RgbImage?

    /**
     * Encodes [image] as a JPEG at the given quality (0–100). Returns null on
     * failure — callers surface that as a stitch error rather than crashing.
     */
    fun encodeJpeg(image: RgbImage, quality: Int): ByteArray?
}

/** The current platform's codec. Created lazily where it is first used. */
expect fun platformImageCodec(): ImageCodec

/**
 * Reads and applies JPEG EXIF orientation without any platform API.
 *
 * The Android pipeline leaned on `ExifInterface`; there is no equivalent in
 * Kotlin/Native, but the app only ever needs one tag (0x0112), so this parses
 * it straight from the file's APP1 segment: find the marker, read the TIFF
 * byte order, walk IFD0, done. Anything unexpected returns
 * [ORIENTATION_NORMAL] — the same default `ExifInterface` used, and the
 * pipeline already survives a lost tag via its geometric fallback.
 */
object JpegOrientation {

    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8

    private val fileSystem: FileSystem = FileSystem.SYSTEM
    private const val SCAN_BYTES = 128 * 1024

    /** The raw EXIF orientation code of the JPEG at [path]; 1 when absent. */
    fun readOrientation(path: Path): Int = runCatching {
        fileSystem.read(path) {
            // EXIF lives in APP1 right after SOI, so the first slice of the
            // file always carries it — but a thumbnail-sized JPEG is a legal
            // carrier too, so never *demand* the full prefix.
            val head =
                if (request(SCAN_BYTES.toLong())) readByteArray(SCAN_BYTES.toLong())
                else readByteArray()
            parseOrientation(head)
        }
    }.getOrDefault(ORIENTATION_NORMAL)

    /**
     * Rotates/flips [image] the way [orientation] says the stored pixels must
     * be turned to display upright. Unknown codes return the image untouched.
     */
    fun apply(image: RgbImage, orientation: Int): RgbImage = when (orientation) {
        ORIENTATION_ROTATE_90 -> ImageMath.rotateCw(image, 90)
        ORIENTATION_ROTATE_180 -> ImageMath.rotateCw(image, 180)
        ORIENTATION_ROTATE_270 -> ImageMath.rotateCw(image, 270)
        ORIENTATION_FLIP_HORIZONTAL -> ImageMath.flipH(image)
        ORIENTATION_FLIP_VERTICAL -> ImageMath.flipV(image)
        // Transpose = rotate 90° CW, then mirror horizontally; transverse is
        // the same at 270°. Matches the Matrix sequences ExifInterface implied.
        ORIENTATION_TRANSPOSE -> ImageMath.flipH(ImageMath.rotateCw(image, 90))
        ORIENTATION_TRANSVERSE -> ImageMath.flipH(ImageMath.rotateCw(image, 270))
        else -> image
    }

    // ------------------------------------------------------------- parsing

    private fun parseOrientation(data: ByteArray): Int {
        if (data.size < 4 || data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte()) {
            return ORIENTATION_NORMAL // not a JPEG this parser understands
        }
        var i = 2
        while (i + 4 <= data.size) {
            if (data[i] != 0xFF.toByte()) {
                // Skip fill bytes between markers (some encoders pad with FF).
                i++
                continue
            }
            val marker = data[i + 1].toInt() and 0xff
            when (marker) {
                0xD8, 0x01 -> { i += 2 }          // SOI, TEM: no length field
                in 0xD0..0xD7 -> { i += 2 }       // RSTn
                else -> {
                    if (i + 4 > data.size) return ORIENTATION_NORMAL
                    val length = ((data[i + 2].toInt() and 0xff) shl 8) or
                        (data[i + 3].toInt() and 0xff)
                    if (length < 2) return ORIENTATION_NORMAL
                    if (marker == 0xE1 && i + 4 + length <= data.size &&
                        hasExifHeader(data, i + 4)
                    ) {
                        return readTagOrientation(data, i + 10) ?: ORIENTATION_NORMAL
                    }
                    i += 2 + length
                }
            }
        }
        return ORIENTATION_NORMAL
    }

    private fun hasExifHeader(data: ByteArray, offset: Int): Boolean {
        if (offset + 6 > data.size) return false
        val exif = "Exif"
        for (k in exif.indices) {
            if (data[offset + k] != exif[k].code.toByte()) return false
        }
        return data[offset + 4].toInt() == 0 && data[offset + 5].toInt() == 0
    }

    /**
     * Walks TIFF IFD0 starting at [tiffOffset] (the "II"/"MM" position) looking
     * for tag 0x0112, orientation, always SHORT.
     */
    private fun readTagOrientation(data: ByteArray, tiffOffset: Int): Int? {
        if (tiffOffset + 8 > data.size) return null
        val bigEndian = when {
            data[tiffOffset].toInt() == 'I'.code && data[tiffOffset + 1].toInt() == 'I'.code -> false
            data[tiffOffset].toInt() == 'M'.code && data[tiffOffset + 1].toInt() == 'M'.code -> true
            else -> return null
        }
        fun u16(offset: Int): Int =
            if (bigEndian) {
                ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
            } else {
                (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
            }

        val ifd0 = tiffOffset + u16(tiffOffset + 4)
        if (ifd0 < tiffOffset || ifd0 + 2 > data.size) return null
        val entries = u16(ifd0)
        var entry = ifd0 + 2
        repeat(entries) {
            if (entry + 12 > data.size) return null
            val tag = u16(entry)
            if (tag == 0x0112) return u16(entry + 8)
            entry += 12
        }
        return null
    }
}
