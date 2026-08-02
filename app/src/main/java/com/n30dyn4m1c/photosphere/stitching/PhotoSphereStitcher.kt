package com.n30dyn4m1c.photosphere.stitching

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.PhotoSphereApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.stitching.Stitcher
import java.io.File

private const val TAG = "PhotoSphereStitcher"

/**
 * Why a stitch ended the way it did.
 *
 * The first four map straight onto OpenCV's own return codes so a caller can log
 * something a bug report can be searched for; the rest are this pipeline's own,
 * numbered clear of OpenCV's range.
 */
enum class StitchStatus(val code: Int) {
    /** A panorama came back. */
    Ok(Stitcher.OK),

    /**
     * Too few frames overlapped enough to be joined. Usually a run that stopped
     * early, or a pan that skipped a chunk of the sphere.
     */
    NeedMoreImages(Stitcher.ERR_NEED_MORE_IMAGES),

    /**
     * OpenCV could not work out how the frames relate to each other. The classic
     * cause is the camera *translating* between frames — walking, or pivoting
     * around the body rather than the lens — which breaks the pure-rotation
     * assumption a panorama stitch is built on. A near-featureless scene (blank
     * sky, plain wall) does it too.
     */
    AlignmentFailed(Stitcher.ERR_HOMOGRAPHY_EST_FAIL),

    /** Frames aligned pairwise but no consistent camera could explain them all. */
    CameraEstimationFailed(Stitcher.ERR_CAMERA_PARAMS_ADJUST_FAIL),

    /** The native library never loaded, so there is no stitcher to run. */
    OpenCvUnavailable(100),

    /** Nothing was handed in. */
    NoInputImages(101),

    /** A buffered frame could not be decoded — deleted, or truncated mid-write. */
    UnreadableInput(102),

    /** OpenCV reported success but produced nothing. */
    EmptyResult(103),

    /** Ran out of memory holding the frames or the panorama. */
    OutOfMemory(104),

    /** Anything else, including native exceptions from inside OpenCV. */
    Unknown(199),
    ;

    companion object {
        /** Maps an `ERR_*` value returned by [Stitcher.stitch]. */
        fun fromOpenCvCode(code: Int): StitchStatus = when (code) {
            Stitcher.OK -> Ok
            Stitcher.ERR_NEED_MORE_IMAGES -> NeedMoreImages
            Stitcher.ERR_HOMOGRAPHY_EST_FAIL -> AlignmentFailed
            Stitcher.ERR_CAMERA_PARAMS_ADJUST_FAIL -> CameraEstimationFailed
            else -> Unknown
        }
    }
}

/** A stitch that did not produce a panorama, carrying the [status] that says why. */
class StitchException(
    val status: StitchStatus,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Which part of the pipeline is running. */
enum class StitchStage {
    /** Checking inputs and the native library. */
    Preparing,

    /** Decoding buffered frames into matrices. */
    Reading,

    /** Inside OpenCV: registration, camera estimation, warping, blending. */
    Stitching,

    /** Cropping and placing the panorama in its equirectangular canvas. */
    Projecting,
}

/**
 * How far along a stitch is.
 *
 * Only [StitchStage.Reading] can report real progress — everything inside
 * OpenCV's `stitch` call is one opaque block — so [fraction] is null for the
 * other stages and the UI shows an indeterminate spinner there rather than a
 * bar that lies.
 */
data class StitchProgress(
    val stage: StitchStage,
    val completed: Int = 0,
    val total: Int = 0,
) {
    val fraction: Float?
        get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null

    companion object {
        val Preparing = StitchProgress(StitchStage.Preparing)
    }
}

/**
 * Turns a session's frames into one equirectangular sphere.
 *
 * The work is OpenCV's `Stitcher` in [Stitcher.PANORAMA] mode: it finds features
 * in each frame, matches them, solves for the camera's rotation and focal
 * length, warps everything onto a sphere and blends the seams. That mode's
 * spherical warper is what makes the output equirectangular geometry rather than
 * a flat mosaic — [EquirectangularFit] only has to crop it and put it in a 2:1
 * frame.
 *
 * Everything runs on [Dispatchers.Default]: it is compute-bound, and OpenCV
 * blocks the calling thread for the whole registration pass. Failures come back
 * as a failed [Result] holding a [StitchException] — a stitch that cannot find
 * enough overlap is an ordinary outcome of a hurried capture, not a crash.
 *
 * Memory is the real constraint. A full sphere is forty-odd frames, and OpenCV
 * holds them all plus their warped copies at once, so frames are decoded down to
 * [DEFAULT_MAX_INPUT_DIMENSION] on the long edge first. Raising that improves
 * detail and quadratically increases the chance of an out-of-memory failure on a
 * mid-range phone.
 */
object PhotoSphereStitcher {

    /** Below this a sphere is not worth attempting; also what the UI gates on. */
    const val MIN_FRAMES = 6

    /** OpenCV itself cannot do anything with fewer than two views. */
    const val MIN_STITCHABLE_FRAMES = 2

    /** Long-edge limit each frame is decoded down to. */
    const val DEFAULT_MAX_INPUT_DIMENSION = 1024

    /** Width cap for the finished sphere; the canvas is always half as tall. */
    const val DEFAULT_MAX_OUTPUT_WIDTH = 4096

    /**
     * Confidence a frame needs to join the panorama.
     *
     * OpenCV defaults to 1.0. Guided capture aims for ~40% overlap on frames
     * shot handheld, which lands a little under that on plain surfaces, so the
     * bar is lowered enough to keep those frames rather than silently dropping
     * them and reporting `ERR_NEED_MORE_IMAGES`.
     */
    private const val PANORAMA_CONFIDENCE_THRESHOLD = 0.7

    private const val ZERO_BYTE: Byte = 0

    @Volatile
    private var isOpenCvReady: Boolean = false

    /**
     * Loads the native library if it is not up already.
     *
     * [PhotoSphereApplication] does this at process start; this is the belt to
     * that braces, for a stitch running in a process where the Application class
     * never ran (tests, or a content provider hosting the code). `initLocal` is
     * idempotent, so calling it twice costs nothing.
     */
    @Synchronized
    fun ensureOpenCv(): Boolean {
        if (isOpenCvReady) return true
        isOpenCvReady = PhotoSphereApplication.isOpenCvAvailable || OpenCVLoader.initLocal()
        if (!isOpenCvReady) Log.e(TAG, "OpenCV native library unavailable; cannot stitch")
        return isOpenCvReady
    }

    /**
     * Stitches [imageFiles] into a 2:1 equirectangular [Bitmap].
     *
     * [onProgress] is called from the stitching thread, not the main one — hand
     * the value to a `StateFlow` or post it rather than writing Compose state
     * from it directly.
     *
     * Cancelling the calling coroutine takes effect at the stage boundaries: the
     * native `stitch` call cannot be interrupted, so a cancellation raised while
     * it is running is honoured as soon as it returns.
     *
     * The returned bitmap is the caller's to recycle.
     */
    suspend fun stitchPhotos(
        imageFiles: List<File>,
        maxInputDimension: Int = DEFAULT_MAX_INPUT_DIMENSION,
        maxOutputWidth: Int = DEFAULT_MAX_OUTPUT_WIDTH,
        onProgress: (StitchProgress) -> Unit = {},
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        val images = ArrayList<Mat>(imageFiles.size)
        try {
            onProgress(StitchProgress.Preparing)

            if (!ensureOpenCv()) {
                throw StitchException(
                    StitchStatus.OpenCvUnavailable,
                    "OpenCV is not loaded on this device",
                )
            }
            if (imageFiles.isEmpty()) {
                throw StitchException(StitchStatus.NoInputImages, "No frames to stitch")
            }
            if (imageFiles.size < MIN_STITCHABLE_FRAMES) {
                throw StitchException(
                    StitchStatus.NeedMoreImages,
                    "Got ${imageFiles.size} frame(s), need at least $MIN_STITCHABLE_FRAMES",
                )
            }

            imageFiles.forEachIndexed { position, file ->
                ensureActive()
                onProgress(StitchProgress(StitchStage.Reading, position, imageFiles.size))
                images += readFrame(file, maxInputDimension)
            }
            onProgress(StitchProgress(StitchStage.Reading, imageFiles.size, imageFiles.size))

            ensureActive()
            onProgress(StitchProgress(StitchStage.Stitching))
            val panorama = Mat()
            try {
                val status = StitchStatus.fromOpenCvCode(newStitcher().stitch(images, panorama))
                if (status != StitchStatus.Ok) {
                    throw StitchException(status, "OpenCV stitch failed with ${status.name}")
                }
                if (panorama.empty()) {
                    throw StitchException(StitchStatus.EmptyResult, "Stitch produced no pixels")
                }

                ensureActive()
                onProgress(StitchProgress(StitchStage.Projecting))
                Result.success(toEquirectangularBitmap(panorama, maxOutputWidth))
            } finally {
                panorama.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StitchException) {
            Log.w(TAG, "Stitch failed: ${e.status} (${e.status.code})", e)
            Result.failure<Bitmap>(e)
        } catch (e: OutOfMemoryError) {
            // Recoverable here: the frames are released in the finally below, and
            // the user can retry at a lower input resolution.
            Log.e(TAG, "Out of memory stitching ${imageFiles.size} frames", e)
            Result.failure<Bitmap>(
                StitchException(StitchStatus.OutOfMemory, "Not enough memory to stitch", e)
            )
        } catch (e: Exception) {
            // Native OpenCV failures arrive as CvException, a RuntimeException.
            Log.e(TAG, "Stitch failed", e)
            Result.failure<Bitmap>(
                StitchException(StitchStatus.Unknown, e.message ?: "Stitch failed", e)
            )
        } finally {
            images.forEach(Mat::release)
        }
    }

    /** A stitcher configured for handheld sphere capture. */
    private fun newStitcher(): Stitcher = Stitcher.create(Stitcher.PANORAMA).apply {
        setPanoConfidenceThresh(PANORAMA_CONFIDENCE_THRESHOLD)
        // Straightens the horizon: without it the panorama drifts into a wave as
        // small roll errors accumulate around the sphere.
        setWaveCorrection(true)
    }

    /**
     * Decodes one frame into the 8-bit 3-channel matrix the stitcher expects.
     *
     * Frames are decoded subsampled and rotated upright first. The rotation
     * matters more than it looks: CameraX records device rotation in EXIF rather
     * than rotating pixels, so a run that crossed a screen rotation would
     * otherwise hand OpenCV a mix of portrait and landscape frames with no way
     * to relate them.
     */
    private fun readFrame(file: File, maxDimension: Int): Mat {
        val bitmap = decodeUpright(file, maxDimension)
            ?: throw StitchException(
                StitchStatus.UnreadableInput,
                "Could not decode ${file.name}",
            )

        val rgba = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        } catch (e: Throwable) {
            rgb.release()
            throw e
        } finally {
            rgba.release()
            bitmap.recycle()
        }
        return rgb
    }

    /** Decodes [file] no larger than [maxDimension], with EXIF rotation applied. */
    private fun decodeUpright(file: File, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
            // Utils.bitmapToMat accepts ARGB_8888 and RGB_565 only.
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        return applyExifRotation(decoded, file)
    }

    /** Rotates [bitmap] to match the orientation EXIF claims for [file]. */
    private fun applyExifRotation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }

        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, /* filter = */ true,
        )
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Crops the panorama's black surround and centres it in a 2:1 canvas.
     *
     * The stitched matrix is the bounding box of everything that was warped onto
     * the sphere, so it carries black wherever the capture did not reach. The
     * crop takes the tight bounding box of the pixels that *are* covered — gaps
     * inside it stay black, which is honest about what was shot — and
     * [EquirectangularFit] decides where that band sits in the equirectangular
     * frame.
     */
    private fun toEquirectangularBitmap(panorama: Mat, maxOutputWidth: Int): Bitmap {
        val bounds = contentBounds(panorama)
        val cropped = if (bounds.width == panorama.cols() && bounds.height == panorama.rows()) {
            panorama
        } else {
            panorama.submat(bounds)
        }

        val placement = EquirectangularFit.place(
            sourceWidth = cropped.cols(),
            sourceHeight = cropped.rows(),
            maxCanvasWidth = maxOutputWidth,
        )

        // The canvas takes the panorama's own type so the resize below writes
        // through the submat instead of quietly reallocating it.
        val canvas = Mat.zeros(placement.canvasHeight, placement.canvasWidth, cropped.type())
        try {
            val target = Rect(placement.left, placement.top, placement.width, placement.height)
            val region = canvas.submat(target)
            try {
                // Sizes and type already match, so this writes through the
                // submat into the canvas rather than reallocating.
                Imgproc.resize(
                    cropped,
                    region,
                    Size(placement.width.toDouble(), placement.height.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA,
                )
            } finally {
                region.release()
            }

            val bitmap = Bitmap.createBitmap(
                placement.canvasWidth,
                placement.canvasHeight,
                Bitmap.Config.ARGB_8888,
            )
            Utils.matToBitmap(canvas, bitmap)
            return bitmap
        } finally {
            canvas.release()
            if (cropped !== panorama) cropped.release()
        }
    }

    /**
     * Tight bounding box of the non-black pixels of [image].
     *
     * Found by collapsing the mask to one row and one column with a max
     * reduction, which costs two passes and a few kilobytes — `findNonZero` on a
     * panorama would allocate a point per lit pixel, tens of megabytes for the
     * same answer.
     */
    private fun contentBounds(image: Mat): Rect {
        val gray = Mat()
        val mask = Mat()
        val columns = Mat()
        val rows = Mat()
        try {
            when (image.channels()) {
                1 -> image.copyTo(gray)
                4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
            }
            Core.compare(gray, Scalar(0.0), mask, Core.CMP_GT)
            Core.reduce(mask, columns, /* dim = */ 0, Core.REDUCE_MAX, CvType.CV_8U)
            Core.reduce(mask, rows, /* dim = */ 1, Core.REDUCE_MAX, CvType.CV_8U)

            val columnData = ByteArray(columns.cols())
            columns.get(0, 0, columnData)
            val rowData = ByteArray(rows.rows())
            rows.get(0, 0, rowData)

            val left = columnData.indexOfFirst { it != ZERO_BYTE }
            val right = columnData.indexOfLast { it != ZERO_BYTE }
            val top = rowData.indexOfFirst { it != ZERO_BYTE }
            val bottom = rowData.indexOfLast { it != ZERO_BYTE }

            // An all-black panorama has no content to crop to; hand back the
            // whole thing and let the caller ship a black sphere rather than a
            // zero-sized crash.
            if (left < 0 || top < 0) return Rect(0, 0, image.cols(), image.rows())

            return Rect(left, top, right - left + 1, bottom - top + 1)
        } finally {
            gray.release()
            mask.release()
            columns.release()
            rows.release()
        }
    }
}

/**
 * Power-of-two subsampling factor that brings the longer edge to [maxDimension]
 * or below.
 *
 * `BitmapFactory` only honours powers of two, so this returns one directly
 * rather than letting it round the value down and hand back something larger
 * than asked for.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    require(maxDimension > 0) { "maxDimension must be positive" }
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / sample > maxDimension) sample *= 2
    return sample
}
