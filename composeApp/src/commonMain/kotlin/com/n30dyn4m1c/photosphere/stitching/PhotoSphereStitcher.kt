package com.n30dyn4m1c.photosphere.stitching

import com.n30dyn4m1c.photosphere.isDebugBuild
import com.n30dyn4m1c.photosphere.toRadiansSafe
import com.n30dyn4m1c.photosphere.util.KLog
import okio.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

private const val TAG = "PhotoSphereStitcher"

/** Debug palette for [PhotoSphereStitcher.stitchPhotos]' colour-frames mode. */
private val DEBUG_FRAME_COLORS = listOf(
    Triple(230, 30, 90),   // blue
    Triple(80, 210, 40),   // green
    Triple(230, 40, 40),   // red
    Triple(30, 210, 220),  // yellow
    Triple(230, 40, 220),  // magenta
    Triple(230, 220, 40),  // cyan
)

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
     * panorama is built on. Reserved: no current pass produces it, but capture
     * and UI still speak this dialect.
     */
    AlignmentFailed(2),

    /** No consistent camera could explain the frames that were handed in. */
    CameraEstimationFailed(3),

    /** Nothing was handed in. */
    NoInputImages(101),

    /** A buffered frame could not be decoded — deleted, or truncated mid-write. */
    UnreadableInput(102),

    /** The render completed but reached none of the sphere. */
    EmptyResult(103),

    /** Anything else. */
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
    /** Checking inputs. */
    Preparing,

    /** Decoding buffered frames into images. */
    Reading,

    /** Finding the seam lines the overlaps will be cut along. */
    Seaming,

    /** Projecting frames onto the sphere and blending the overlaps. */
    Stitching,

    /** Sharpening the finished canvas and handing it back. */
    Projecting,
}

/**
 * How far along a stitch is.
 *
     * [StitchStage.Reading], [StitchStage.Seaming] and [StitchStage.Stitching]
     * all report real progress — one frame, one seam and one band of canvas at a
     * time. The short stages either side leave [fraction] null, and the UI shows
     * an indeterminate spinner for those rather than a bar that lies.
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
    val file: Path,
    val pose: CameraPose,
)

/**
 * Turns a session's frames into one equirectangular sphere.
 *
 * **Why this was never OpenCV's `Stitcher`.** Guided capture already knows where
 * the camera was pointing for every frame, because the alignment gate only fires
 * when the device is held on a known target. That turns the hard half of stitching —
 * solving for each camera's rotation — into something already measured, and what
 * is left is a reprojection: every pixel of the output canvas is a direction on
 * the sphere, and for each frame that direction is rotated into the frame's axes
 * and divided through by depth to find the pixel that saw it. Overlaps are
 * resolved by multi-band blending (see [MultibandBlender]): every frame and its
 * feather mask are split into Laplacian/Gaussian pyramids and each band is
 * cross-faded with a mask sized to the band, so a seam is faded at every scale
 * by a transition narrower than the detail that scale carries.
 *
 * The measured poses are used exactly as captured: the alignment gate guarantees
 * they were taken on known targets, so no feature-based refinement stands between
 * them and the render (an earlier ORB refinement existed on top of OpenCV and
 * left with it — the sensor pose stays both starting guess and last word). The
 * lens's radial distortion is carried through the whole model (see
 * [RadialDistortion]) so frame edges, where seams live, land where the lens
 * really put them.
 *
 * The result is equirectangular *by construction* rather than by cropping
 * something else into shape: a pixel's row *is* its latitude, so an incomplete
 * sphere is black exactly where it was not shot, at the right elevation.
 *
 * Everything runs on [Dispatchers.Default] — it is compute-bound — and failures
 * come back as a failed [Result] holding a [StitchException]. A sphere that was
 * captured too sparsely is an ordinary outcome of a hurried run, not a crash.
 */
object PhotoSphereStitcher {

    /**
     * Below this a stitch is not worth offering; also what the UI gates on.
     *
     * Three, because three overlapping frames already make a panorama worth
     * having — a corner of a room, a stretch of skyline — and holding the button
     * back until a third of a sphere is in the buffer only turns a usable
     * capture into a failed one. The pipeline places frames from their measured
     * pose rather than by searching for a chain of matches, so it has no
     * minimum-frames requirement of its own; what the extra frames buy is
     * coverage, and coverage is the user's call to make.
     */
    const val MIN_FRAMES = 3

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
     * showing.
     *
     * Deliberately near the floor. Three frames of a narrow lens reach under 2%
     * of the canvas, and a partial capture — a single ring, or a corner of a
     * room — is now a supported outcome rather than a failed sphere, so this is
     * only here to catch a render that reached essentially nothing. Anything
     * above it is the user's to judge on the result screen.
     */
    private const val MIN_COVERAGE = 0.002f

    /** Gaussian sigma for the unsharp mask, in output pixels. */
    private const val UNSHARP_RADIUS = 1.5

    /** Pixels brighter than this count as shot content rather than a gap. */
    private const val UNSHARP_CONTENT_THRESHOLD = 24.0

    /** Rows the unsharp mask sharpens at a time. */
    private const val UNSHARP_BAND_HEIGHT = 256

    /**
     * Rows of context carried either side of a band.
     *
     * The Gaussian reaches [UNSHARP_RADIUS]·3 pixels or so; a band blurred
     * without its neighbours would darken toward its own edges and leave a
     * horizontal line every [UNSHARP_BAND_HEIGHT] rows.
     */
    private const val UNSHARP_HALO = 16

    /**
     * Stitches [frames] into an equirectangular [RgbImage].
     *
     * By default the canvas is the full sphere — 2:1, 360° of longitude by 180°
     * of latitude. A ring capture covers the same 360° of longitude but only a
     * band of latitude, so [latitudeSpanDegrees] (with [centerLatitudeDegrees])
     * narrows the canvas to that band and the output comes out as a wide strip
     * instead of a 2:1 image with black wedges at the poles.
     *
     * The two field of view angles describe the *decoded, upright* frame — the
     * same screen-frame angles `rememberSphereOptics` reports — and set how
     * much sphere each frame is taken to cover. Getting them wrong scales the
     * whole panorama: too narrow leaves gaps between frames, too wide overlaps
     * them.
     *
     * [radialDistortion], when the optics reported one, carries the lens's
     * Brown-Conrady coefficients through the projection so frame edges land
     * where the lens put them.
     *
     * [unsharpAmount] (0 = off) is the strength of a masked unsharp mask
     * applied to the finished canvas — the perceived crispness of the output.
     *
     * [pivot] says how far the lens sat from the axis the capture was turned
     * about. The default treats the two as the same point, which is what a
     * tripod gives; a hand-held sphere shot by swivelling on the spot wants
     * [PivotModel.HandheldBodySwivel].
     *
     * [portraitRotationDegrees] is the clockwise turn that makes a raw
     * (sensor-native) frame display-upright at the current display rotation —
     * the rotation the camera records in each still's EXIF `ORIENTATION` tag.
     * When a frame's tag is missing or was lost in a metadata rewrite, the frame
     * decodes in the sensor's native orientation, transposed against the
     * portrait pose; it is rotated by this amount so its content still lands
     * upright on the sphere.
     *
     * [useSeams] opts into seam carving: instead of the wide cross-fade, every
     * pixel is painted by the single frame the graph-cut [SeamFinder] assigns
     * it, and only a few pixels across each cut blend the loser in — sharper
     * overlaps at the price of a little compute and a seam that is only as
     * good as the frames agree.
     *
     * [onProgress] is called from the stitching thread, not the main one — hand
     * the value to a `StateFlow` or post it rather than writing Compose state
     * from it directly.
     *
     * The returned image is the caller's to keep or drop; it is plain memory
     * and needs no releasing.
     */
    suspend fun stitchPhotos(
        frames: List<SphereFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
        radialDistortion: RadialDistortion? = null,
        maxInputDimension: Int = DEFAULT_MAX_INPUT_DIMENSION,
        maxOutputWidth: Int = DEFAULT_MAX_OUTPUT_WIDTH,
        unsharpAmount: Float = 0f,
        pivot: PivotModel = PivotModel.None,
        portraitRotationDegrees: Int = 90,
        useSeams: Boolean = false,
        debugColorFrames: Boolean = false,
        longitudeSpanDegrees: Float = 360f,
        centerLongitudeDegrees: Float = 0f,
        latitudeSpanDegrees: Float = 180f,
        centerLatitudeDegrees: Float = 0f,
        onProgress: (StitchProgress) -> Unit = {},
    ): Result<RgbImage> = withContext(Dispatchers.Default) {
        try {
            onProgress(StitchProgress.Preparing)

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

            // The field of view describes the *upright* frame, as captured on
            // the portrait-locked display. It is checked once, against the first
            // decoded frame, and swapped if it evidently describes the other
            // axis instead: a swapped FOV makes the focal lengths the frame
            // implies disagree by roughly the square of its aspect ratio, which
            // no real lens does. See [correctFovOrientation].
            var horizontalFov = horizontalFovDegrees
            var verticalFov = verticalFovDegrees
            var fovChecked = false

            KLog.i(
                TAG,
                "Stitch input: ${frames.size} frames, reported FOV " +
                    "$horizontalFovDegrees°x$verticalFovDegrees°, " +
                    "distortion=${radialDistortion?.coefficients?.joinToString() ?: "none"}, " +
                    "pivot=$pivot",
            )

            val codec = platformImageCodec()
            val decoded = ArrayList<DecodedFrame>(frames.size)
            frames.forEachIndexed { position, frame ->
                ensureActive()
                onProgress(StitchProgress(StitchStage.Reading, position, frames.size))

                var image = decodeFrame(codec, frame.file, maxInputDimension)

                // A frame must land on the sphere the same way its pose
                // describes it. The pose is display-upright, so a frame that
                // decoded in the sensor's native (landscape) orientation —
                // its EXIF rotation tag missing, or lost when the metadata
                // rewrite re-encoded the JPEG — has to be turned upright
                // *here*, or its content paints onto the sphere rotated 90°
                // against the measured pose and no two frames line up.
                // `portraitRotationDegrees` is exactly the turn the camera
                // would have recorded in the tag.
                if (image.width > image.height && portraitRotationDegrees % 180 == 90) {
                    KLog.w(
                        TAG,
                        "Frame $position decoded ${image.width}x${image.height} — " +
                            "transposed against the ${horizontalFov}°x${verticalFov}° FOV; " +
                            "rotating ${portraitRotationDegrees}° clockwise",
                    )
                    image = ImageMath.rotateCw(image, portraitRotationDegrees)
                }
                KLog.i(
                    TAG,
                    "Frame $position decoded ${image.width}x${image.height} at pose " +
                        "yaw=${frame.pose.yawDegrees}° pitch=${frame.pose.pitchDegrees}° " +
                        "roll=${frame.pose.rollDegrees}°",
                )
                if (debugColorFrames) {
                    // Paint the frame a solid colour so the finished pano
                    // shows exactly where each frame was placed — the
                    // placement check.
                    val (r, g, b) = DEBUG_FRAME_COLORS[position % DEBUG_FRAME_COLORS.size]
                    ImageMath.fill(image, r, g, b)
                }
                if (!fovChecked) {
                    val corrected = correctFovOrientation(
                        widthPx = image.width,
                        heightPx = image.height,
                        horizontalFovDegrees = horizontalFov,
                        verticalFovDegrees = verticalFov,
                    )
                    if (corrected != null) {
                        KLog.w(
                            TAG,
                            "FOV ${horizontalFov}°x${verticalFov}° does not match the " +
                                "${image.width}x${image.height} frame; using " +
                                "${corrected.first}°x${corrected.second}°",
                        )
                        horizontalFov = corrected.first
                        verticalFov = corrected.second
                    }
                    fovChecked = true
                }
                // The lens's unitless coefficients are converted against the
                // focal length *this* decoded frame implies, so a frame
                // subsampled to any size still carries the same physical
                // lens.
                val intrinsics = FrameIntrinsics.forLens(
                    widthPx = image.width,
                    heightPx = image.height,
                    horizontalFovDegrees = horizontalFov,
                    verticalFovDegrees = verticalFov,
                    distortion = radialDistortion,
                )
                if (position == 0) {
                    canvasWidth = canvasWidthFor(
                        frameWidthPx = image.width,
                        horizontalFovDegrees = horizontalFov,
                        maxOutputWidth = maxOutputWidth,
                        longitudeSpanDegrees = longitudeSpanDegrees,
                    )
                }
                decoded += DecodedFrame(
                    image = image,
                    intrinsics = intrinsics,
                    basis = CameraBasis.of(frame.pose),
                )
            }
            onProgress(StitchProgress(StitchStage.Reading, frames.size, frames.size))
            val canvasHeight = canvasHeightFor(canvasWidth, longitudeSpanDegrees, latitudeSpanDegrees)
            KLog.i(
                TAG,
                "Stitch geometry: corrected FOV ${horizontalFov}°x${verticalFov}°, " +
                    "canvas ${canvasWidth}x$canvasHeight " +
                    "($longitudeSpanDegrees° x $latitudeSpanDegrees°)",
            )

            ensureActive()
            // The sensor poses go straight in: guided capture measured them on
            // known targets, so there is nothing to reconcile before rendering.
            val prepared = ArrayList<PreparedFrame>(decoded.size)
            decoded.forEach { frame ->
                ensureActive()
                prepared += PreparedFrame(
                    image = frame.image,
                    basis = frame.basis,
                    intrinsics = frame.intrinsics,
                    footprint = FrameFootprint.compute(
                        basis = frame.basis,
                        intrinsics = frame.intrinsics,
                        canvasWidth = canvasWidth,
                        canvasHeight = canvasHeight,
                        pivotRatio = pivot.ratio,
                        longitudeSpanDegrees = longitudeSpanDegrees,
                        centerLongitudeDegrees = centerLongitudeDegrees,
                        latitudeSpanDegrees = latitudeSpanDegrees,
                        centerLatitudeDegrees = centerLatitudeDegrees,
                    ),
                )
            }

            ensureActive()
            // Captured rather than called inside the lambdas below: the seam
            // finder and the renderer are not suspending functions, so the
            // context has to be carried in.
            val context = currentCoroutineContext()

            // Seam carving: decide which frame paints each pixel, then render
            // with the wide cross-fade replaced by a near-hard cut that fades
            // only a few pixels across it. Without it the render falls back to
            // the multi-band blend.
            val seams = if (useSeams) {
                onProgress(StitchProgress(StitchStage.Seaming))
                SeamFinder.computeSeams(
                    frames = prepared,
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    gains = null,
                    pivotRatio = pivot.ratio,
                    longitudeSpanDegrees = longitudeSpanDegrees,
                    centerLongitudeDegrees = centerLongitudeDegrees,
                    latitudeSpanDegrees = latitudeSpanDegrees,
                    centerLatitudeDegrees = centerLatitudeDegrees,
                    onProgress = { completed, total ->
                        onProgress(StitchProgress(StitchStage.Seaming, completed, total))
                    },
                    checkCancelled = { context.ensureActive() },
                )
            } else {
                null
            }
            if (isDebugBuild && seams != null) {
                KLog.i(
                    TAG,
                    "Seam carving: ${seams.gridWidth}x${seams.gridHeight} grid at scale " +
                        "${seams.scale} over ${prepared.size} frames",
                )
            }

            onProgress(StitchProgress(StitchStage.Stitching))
            val rendered = EquirectangularRenderer.render(
                frames = prepared,
                canvasWidth = canvasWidth,
                canvasHeight = canvasHeight,
                gains = null,
                seams = seams,
                pivotRatio = pivot.ratio,
                longitudeSpanDegrees = longitudeSpanDegrees,
                centerLongitudeDegrees = centerLongitudeDegrees,
                latitudeSpanDegrees = latitudeSpanDegrees,
                centerLatitudeDegrees = centerLatitudeDegrees,
                onBandComplete = { completed, total ->
                    onProgress(StitchProgress(StitchStage.Stitching, completed, total))
                },
                checkCancelled = { context.ensureActive() },
            )

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
            applyUnsharpMask(rendered.canvas, unsharpAmount)
            Result.success(rendered.canvas)
        } catch (e: CancellationException) {
            throw e
        } catch (e: StitchException) {
            KLog.w(TAG, "Stitch failed: ${e.status} (${e.status.code})", e)
            Result.failure(e)
        } catch (e: Exception) {
            KLog.e(TAG, "Stitch failed", e)
            Result.failure(
                StitchException(StitchStatus.Unknown, e.message ?: "Stitch failed", e)
            )
        }
    }

    /**
     * Canvas width that matches the detail in the frames.
     *
     * Rendering wider than this only interpolates: the frames hold
     * `frameWidthPx / fov` pixels per degree and the sphere is
     * [longitudeSpanDegrees] around (360° for a full sphere or a ring capture).
     * The width is forced even so a full-sphere canvas has an integer height.
     */
    internal fun canvasWidthFor(
        frameWidthPx: Int,
        horizontalFovDegrees: Float,
        maxOutputWidth: Int,
        longitudeSpanDegrees: Float = 360f,
    ): Int {
        val ideal = (longitudeSpanDegrees * frameWidthPx / horizontalFovDegrees).roundToInt()
        val width = minOf(ideal, maxOutputWidth).coerceAtLeast(MIN_OUTPUT_WIDTH)
        return width - (width % 2)
    }

    /**
     * Canvas height that keeps the pixels square for a canvas spanning
     * [longitudeSpanDegrees] of longitude and [latitudeSpanDegrees] of latitude.
     *
     * A full sphere is half as tall as wide (180° of latitude across 360° of
     * longitude); a ring capture is only as tall as its band is wide in angular
     * terms, so a 360° × 72° ring comes out at exactly one fifth of the width.
     */
    internal fun canvasHeightFor(
        canvasWidth: Int,
        longitudeSpanDegrees: Float,
        latitudeSpanDegrees: Float,
    ): Int {
        val height = (canvasWidth * latitudeSpanDegrees / longitudeSpanDegrees).roundToInt()
        return height.coerceAtLeast(1)
    }

    /**
     * Checks that the two field-of-view angles describe the axes of a decoded
     * [widthPx]x[heightPx] frame, swapping them when they evidently do not.
     *
     * A lens has square pixels, so the focal length the horizontal field of
     * view implies for the frame's width must match the one the vertical field
     * of view implies for its height. If the angles arrived transposed — the
     * axes of the sensor rather than of the upright frame — the two implied
     * focal lengths differ by roughly the square of the aspect ratio (0.56 for
     * a 4:3 frame), which is far beyond any field-of-view estimation error. The
     * boundary is drawn at 1 ± 30%: generous enough never to trip on a
     * loosely-described lens, unambiguous enough that a genuine swap is caught.
     *
     * Returns the corrected pair, or null when the angles already match the
     * frame.
     */
    internal fun correctFovOrientation(
        widthPx: Int,
        heightPx: Int,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
    ): Pair<Float, Float>? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val horizontalTan = tan(toRadiansSafe(horizontalFovDegrees / 2.0))
        val verticalTan = tan(toRadiansSafe(verticalFovDegrees / 2.0))
        if (horizontalTan <= 0.0 || verticalTan <= 0.0) return null
        val horizontalFocal = widthPx / 2.0 / horizontalTan
        val verticalFocal = heightPx / 2.0 / verticalTan
        val ratio =
            if (horizontalFocal >= verticalFocal) verticalFocal / horizontalFocal
            else horizontalFocal / verticalFocal
        if (ratio >= 0.7) return null
        return verticalFovDegrees to horizontalFovDegrees
    }

    /**
     * Decodes one frame into the upright RGB image the renderer samples.
     *
     * Frames are decoded subsampled first, then rotated upright from their EXIF
     * orientation tag. The rotation matters more than it looks: cameras record
     * device rotation in EXIF rather than rotating pixels, so a run that crossed
     * a screen rotation would otherwise hand the renderer a mix of portrait and
     * landscape frames while the field of view describes only one of them.
     */
    private fun decodeFrame(codec: ImageCodec, file: Path, maxDimension: Int): RgbImage {
        val decoded = codec.decodeJpeg(file, maxDimension)
            ?: throw StitchException(
                StitchStatus.UnreadableInput,
                "Could not decode ${file.name}",
            )
        val orientation = JpegOrientation.readOrientation(file)
        return if (orientation == JpegOrientation.ORIENTATION_NORMAL) {
            decoded
        } else {
            JpegOrientation.apply(decoded, orientation)
        }
    }

    /**
     * Applies a masked unsharp mask to the finished canvas, in place.
     *
     * `out = src + amount·(src − blur)` lifts the edge contrast that reads as
     * sharpness. The mask restricts it to pixels that were actually shot: the
     * black bands where the sphere was never captured must not grow a bright
     * rim, and near-black pixels (a night shot) are left alone so noise is not
     * sharpened along with the edges.
     *
     * **Memory.** Done whole, this is the peak of the entire pipeline: a blur, a
     * sharpened copy, a greyscale and a mask all at canvas size, which on the
     * 6144-wide profile is around 200 MB on top of the canvas itself. Working
     * in bands holds one untouched copy of the canvas and a few band-sized
     * temporaries instead. The copy is what keeps the result identical to the
     * whole-canvas version: every band reads its blur input from the original
     * pixels, so a band's halo cannot pick up the sharpening its neighbour just
     * wrote and sharpen it a second time.
     */
    private fun applyUnsharpMask(canvas: RgbImage, amount: Float) {
        if (amount <= 0f) return
        val height = canvas.height
        val width = canvas.width
        if (height <= 0 || width <= 0) return

        val source = canvas.copy()
        var bandTop = 0
        while (bandTop < height) {
            val bandHeight = min(UNSHARP_BAND_HEIGHT, height - bandTop)
            // The band plus the context the Gaussian needs, clipped to the
            // canvas at the top and bottom rows.
            val haloTop = max(0, bandTop - UNSHARP_HALO)
            val haloBottom = min(height, bandTop + bandHeight + UNSHARP_HALO)
            sharpenBand(
                source = source,
                canvas = canvas,
                width = width,
                haloTop = haloTop,
                haloHeight = haloBottom - haloTop,
                bandTop = bandTop,
                bandHeight = bandHeight,
                amount = amount,
            )
            bandTop += bandHeight
        }
    }

    /** Sharpens one band of [canvas], reading its blur input from [source]. */
    private fun sharpenBand(
        source: RgbImage,
        canvas: RgbImage,
        width: Int,
        haloTop: Int,
        haloHeight: Int,
        bandTop: Int,
        bandHeight: Int,
        amount: Float,
    ) {
        // Slice the halo out of the untouched copy so the Gaussian never sees
        // a neighbour this pass has already sharpened.
        val haloBytes = ByteArray(width * haloHeight * 3)
        source.bytes.copyInto(
            haloBytes,
            0,
            haloTop * width * 3,
            (haloTop + haloHeight) * width * 3,
        )
        val blurred = ImageMath.gaussianBlur(RgbImage(width, haloHeight, haloBytes), UNSHARP_RADIUS)

        val src = source.bytes
        val dst = canvas.bytes
        val blur = blurred.bytes
        val gain = 1.0 + amount.toDouble()
        val weight = amount.toDouble()
        for (row in bandTop until bandTop + bandHeight) {
            var i = row * width * 3
            var h = (row - haloTop) * width * 3
            repeat(width) {
                // BT.601 luma, the weights cvtColor(RGB2GRAY) applies.
                val gray = 0.299 * (src[i].toInt() and 0xff) +
                    0.587 * (src[i + 1].toInt() and 0xff) +
                    0.114 * (src[i + 2].toInt() and 0xff)
                if (gray > UNSHARP_CONTENT_THRESHOLD) {
                    for (c in 0 until 3) {
                        val s = src[i + c].toInt() and 0xff
                        val b = blur[h + c].toInt() and 0xff
                        dst[i + c] = ImageMath.roundToByte(s.toDouble() * gain - b * weight)
                    }
                }
                i += 3
                h += 3
            }
        }
    }
}

/** One frame decoded and placed: everything the render needs about it. */
private class DecodedFrame(
    val image: RgbImage,
    val intrinsics: FrameIntrinsics,
    val basis: CameraBasis,
)

/**
 * Power-of-two subsampling factor that brings the longer edge to [maxDimension]
 * or below.
 *
 * Android's `BitmapFactory` only honours powers of two, so this returns one
 * directly rather than letting it round the value down and hand back something
 * larger than asked for. Other decoders take the limit as-is; sharing the one
 * function keeps every platform's working resolution comparable.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
    require(maxDimension > 0) { "maxDimension must be positive" }
    var sample = 1
    val longest = maxOf(width, height)
    while (longest / sample > maxDimension) sample *= 2
    return sample
}
