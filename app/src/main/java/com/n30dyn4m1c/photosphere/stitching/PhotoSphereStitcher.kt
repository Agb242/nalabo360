package com.n30dyn4m1c.photosphere.stitching

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.n30dyn4m1c.photosphere.PhotoSphereApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "PhotoSphereStitcher"

/**
 * Why a stitch ended the way it did.
 *
 * The codes are this pipeline's contract with its own logs and bug reports, not
 * anyone else's: 0–3 are the outcomes a registration pass can have, and
 * everything from 100 up is a failure of the machinery around it.
 */
enum class StitchStatus(val code: Int) {
    /** A panorama came back. */
    Ok(0),

    /**
     * Too little of the sphere was covered to be worth calling a photo sphere.
     * Usually a run that stopped early, or a pan that skipped a chunk.
     */
    NeedMoreImages(1),

    /**
     * The frames could not be reconciled into one view. The classic cause is the
     * camera *translating* between frames — walking, or pivoting around the body
     * rather than the lens — which breaks the pure-rotation assumption a
     * panorama is built on.
     */
    AlignmentFailed(2),

    /** No consistent camera could explain the frames that were handed in. */
    CameraEstimationFailed(3),

    /** The native library never loaded, so there is no stitcher to run. */
    OpenCvUnavailable(100),

    /** Nothing was handed in. */
    NoInputImages(101),

    /** A buffered frame could not be decoded — deleted, or truncated mid-write. */
    UnreadableInput(102),

    /** The render completed but reached none of the sphere. */
    EmptyResult(103),

    /** Ran out of memory holding the frames or the panorama. */
    OutOfMemory(104),

    /** Anything else, including native exceptions from inside OpenCV. */
    Unknown(199),
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

    /** Projecting frames onto the sphere and blending the overlaps. */
    Stitching,

    /** Turning the finished canvas into a bitmap. */
    Projecting,
}

/**
 * How far along a stitch is.
 *
 * [StitchStage.Reading] and [StitchStage.Stitching] both report real progress —
 * one frame and one band of canvas at a time. The short stages either side leave
 * [fraction] null, and the UI shows an indeterminate spinner for those rather
 * than a bar that lies.
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
 * One frame on its way into a sphere: the pixels, and where the camera was.
 *
 * The pose is what makes this pipeline possible at all — see the class docs on
 * [PhotoSphereStitcher].
 */
data class SphereFrame(
    val file: File,
    val pose: CameraPose,
)

/**
 * Turns a session's frames into one equirectangular sphere.
 *
 * **Why this is not OpenCV's `Stitcher`.** It cannot be: the stitching module is
 * absent from every prebuilt OpenCV for Android. `org.opencv:opencv` ships Java
 * bindings for core, imgproc, features2d, calib3d and the rest, but there is no
 * `org.opencv.stitching` package and `libopencv_java4.so` contains none of the
 * pipeline's symbols either. `cv::Stitcher` is, in practice, a desktop API.
 *
 * **What replaces it.** Guided capture already knows where the camera was
 * pointing for every frame, because the alignment gate only fires when the
 * device is held on a known target. That turns the hard half of stitching —
 * solving for each camera's rotation — into something already measured, and what
 * is left is a reprojection: every pixel of the output canvas is a direction on
 * the sphere, and for each frame that direction is rotated into the frame's axes
 * and divided through by depth to find the pixel that saw it. Overlaps are
 * resolved by a feathered weighted mean, so seams cross-fade.
 *
 * The result is equirectangular by construction rather than by cropping
 * something else into shape: a pixel's row *is* its latitude, so an incomplete
 * sphere is black exactly where it was not shot, at the right elevation.
 *
 * The accuracy ceiling is the rotation vector sensor, which is fused and
 * drift-free but not perfect; residual error shows up as soft doubling in the
 * overlaps rather than as a broken panorama.
 *
 * Everything runs on [Dispatchers.Default] — it is compute-bound — and failures
 * come back as a failed [Result] holding a [StitchException]. A sphere that was
 * captured too sparsely is an ordinary outcome of a hurried run, not a crash.
 */
object PhotoSphereStitcher {

    /** Below this a sphere is not worth attempting; also what the UI gates on. */
    const val MIN_FRAMES = 6

    /** One frame is a photo, not a panorama. */
    const val MIN_STITCHABLE_FRAMES = 2

    /** Long-edge limit each frame is decoded down to. */
    const val DEFAULT_MAX_INPUT_DIMENSION = 1024

    /** Width cap for the finished sphere; the canvas is always half as tall. */
    const val DEFAULT_MAX_OUTPUT_WIDTH = 4096

    /** Never render a canvas narrower than this, however coarse the input. */
    private const val MIN_OUTPUT_WIDTH = 512

    /**
     * Fraction of the sphere that has to be covered for the result to be worth
     * showing. A full plan reaches about two thirds of the canvas — everything
     * between ±60° of elevation — so this only catches runs that barely started.
     */
    private const val MIN_COVERAGE = 0.02f

    @Volatile
    private var isOpenCvReady: Boolean = false

    /**
     * Loads the native library if it is not up already.
     *
     * [PhotoSphereApplication] does this at process start; this is the belt to
     * that braces, for a stitch running in a process where the Application class
     * never ran. `initLocal` is idempotent, so calling it twice costs nothing.
     */
    @Synchronized
    fun ensureOpenCv(): Boolean {
        if (isOpenCvReady) return true
        isOpenCvReady = PhotoSphereApplication.isOpenCvAvailable || OpenCVLoader.initLocal()
        if (!isOpenCvReady) Log.e(TAG, "OpenCV native library unavailable; cannot stitch")
        return isOpenCvReady
    }

    /**
     * Stitches [frames] into a 2:1 equirectangular [Bitmap].
     *
     * The two field of view angles describe the *decoded, upright* frame — the
     * same screen-frame angles `rememberCameraFieldOfView` reports — and set how
     * much sphere each frame is taken to cover. Getting them wrong scales the
     * whole panorama: too narrow leaves gaps between frames, too wide overlaps
     * them.
     *
     * [onProgress] is called from the stitching thread, not the main one — hand
     * the value to a `StateFlow` or post it rather than writing Compose state
     * from it directly.
     *
     * The returned bitmap is the caller's to recycle.
     */
    suspend fun stitchPhotos(
        frames: List<SphereFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
        maxInputDimension: Int = DEFAULT_MAX_INPUT_DIMENSION,
        maxOutputWidth: Int = DEFAULT_MAX_OUTPUT_WIDTH,
        onProgress: (StitchProgress) -> Unit = {},
    ): Result<Bitmap> = withContext(Dispatchers.Default) {
        val prepared = ArrayList<PreparedFrame>(frames.size)
        try {
            onProgress(StitchProgress.Preparing)

            if (!ensureOpenCv()) {
                throw StitchException(
                    StitchStatus.OpenCvUnavailable,
                    "OpenCV is not loaded on this device",
                )
            }
            if (frames.isEmpty()) {
                throw StitchException(StitchStatus.NoInputImages, "No frames to stitch")
            }
            if (frames.size < MIN_STITCHABLE_FRAMES) {
                throw StitchException(
                    StitchStatus.NeedMoreImages,
                    "Got ${frames.size} frame(s), need at least $MIN_STITCHABLE_FRAMES",
                )
            }

            // The canvas is sized to the detail the frames actually carry: a
            // frame is worth `width / horizontal fov` pixels per degree, and 360°
            // of that is as wide as the sphere can be without inventing pixels.
            var canvasWidth = maxOutputWidth

            frames.forEachIndexed { position, frame ->
                ensureActive()
                onProgress(StitchProgress(StitchStage.Reading, position, frames.size))

                val image = readFrame(frame.file, maxInputDimension)
                val intrinsics = FrameIntrinsics.fromFieldOfView(
                    widthPx = image.cols(),
                    heightPx = image.rows(),
                    horizontalFovDegrees = horizontalFovDegrees,
                    verticalFovDegrees = verticalFovDegrees,
                )
                if (position == 0) {
                    canvasWidth = canvasWidthFor(
                        frameWidthPx = image.cols(),
                        horizontalFovDegrees = horizontalFovDegrees,
                        maxOutputWidth = maxOutputWidth,
                    )
                }
                val basis = CameraBasis.of(frame.pose)
                prepared += PreparedFrame(
                    image = image,
                    basis = basis,
                    intrinsics = intrinsics,
                    footprint = FrameFootprint.compute(
                        basis = basis,
                        intrinsics = intrinsics,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasWidth / 2,
                    ),
                )
            }
            onProgress(StitchProgress(StitchStage.Reading, frames.size, frames.size))

            ensureActive()
            onProgress(StitchProgress(StitchStage.Stitching))
            // Captured rather than called inside the lambda: the renderer is not
            // a suspending function, so the context has to be carried in.
            val context = currentCoroutineContext()
            val rendered = EquirectangularRenderer.render(
                frames = prepared,
                canvasWidth = canvasWidth,
                canvasHeight = canvasWidth / 2,
                onBandComplete = { completed, total ->
                    onProgress(StitchProgress(StitchStage.Stitching, completed, total))
                },
                checkCancelled = { context.ensureActive() },
            )

            try {
                if (rendered.coverage <= 0f) {
                    throw StitchException(
                        StitchStatus.EmptyResult,
                        "The render reached none of the sphere",
                    )
                }
                if (rendered.coverage < MIN_COVERAGE) {
                    throw StitchException(
                        StitchStatus.NeedMoreImages,
                        "Only ${(rendered.coverage * 100).roundToInt()}% of the sphere was covered",
                    )
                }

                ensureActive()
                onProgress(StitchProgress(StitchStage.Projecting))
                Result.success(toBitmap(rendered.canvas))
            } finally {
                rendered.canvas.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: StitchException) {
            Log.w(TAG, "Stitch failed: ${e.status} (${e.status.code})", e)
            Result.failure<Bitmap>(e)
        } catch (e: OutOfMemoryError) {
            // Recoverable here: the frames are released in the finally below, and
            // the user can retry at a lower input resolution.
            Log.e(TAG, "Out of memory stitching ${frames.size} frames", e)
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
            prepared.forEach { it.image.release() }
        }
    }

    /**
     * Canvas width that matches the detail in the frames.
     *
     * Rendering wider than this only interpolates: the frames hold
     * `frameWidthPx / fov` pixels per degree and the sphere is 360° around. The
     * width is forced even so the 2:1 canvas has an integer height.
     */
    internal fun canvasWidthFor(
        frameWidthPx: Int,
        horizontalFovDegrees: Float,
        maxOutputWidth: Int,
    ): Int {
        val ideal = (360.0 * frameWidthPx / horizontalFovDegrees).roundToInt()
        val width = minOf(ideal, maxOutputWidth).coerceAtLeast(MIN_OUTPUT_WIDTH)
        return width - (width % 2)
    }

    /**
     * Decodes one frame into the 8-bit 3-channel matrix the renderer samples.
     *
     * Frames are decoded subsampled and rotated upright first. The rotation
     * matters more than it looks: CameraX records device rotation in EXIF rather
     * than rotating pixels, so a run that crossed a screen rotation would
     * otherwise hand the renderer a mix of portrait and landscape frames while
     * the field of view describes only one of them.
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

    /** Copies the finished canvas out into a bitmap the UI can show. */
    private fun toBitmap(canvas: Mat): Bitmap {
        val bitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(canvas, bitmap)
        return bitmap
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
