package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationTracker
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchException
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.stitching.StitchStage
import com.n30dyn4m1c.photosphere.stitching.StitchStatus
import com.n30dyn4m1c.photosphere.storage.ImageBufferManager
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.SphereImageStore.StitchedSphere
import com.n30dyn4m1c.photosphere.storage.rememberImageBufferManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "PhotoSphereCamera"

/**
 * Guided capture: viewfinder, alignment overlay, and an automatic shutter.
 *
 * The screen owns the loop that turns device attitude into frames. A
 * [SphereTargetPlan] is laid out around whichever bearing the user is facing
 * when the first sensor fix lands; [TargetOverlay] projects it onto the preview;
 * and [AlignmentGate] fires [ImageCapture] once the aim has held on the active
 * target long enough to be deliberate. Frames land in cache as full-resolution
 * JPEGs — they are the stitcher's input, not photos the user asked to keep, so
 * they stay out of the gallery until there is a sphere to save.
 *
 * Stitching ends the screen's involvement: the finished sphere goes to
 * [onSphereReady] as a cached, GPano-tagged JPEG, and what happens to it —
 * gallery, share sheet, or nothing — is the result screen's business.
 *
 * The caller is responsible for the CAMERA permission; see
 * `MainActivity.RequirePermissions`.
 *
 * @param onSphereReady called on the main thread with a finished sphere, once
 *   the frames it was built from have been cleared
 */
@Composable
fun PhotoSphereCameraScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Guided capture can take minutes of pointing the phone around the scene;
    // the system screen timeout must not cut in partway through.
    val rootView = LocalView.current
    DisposableEffect(rootView) {
        rootView.keepScreenOn = true
        onDispose {
            rootView.keepScreenOn = false
        }
    }

    val tracker = rememberOrientationTracker()
    // Held as a State rather than read with `by`: touching `.value` here would
    // recompose the whole screen at the sensor's rate. Only the overlay's draw
    // lambda reads it.
    val orientationState = tracker.orientation.collectAsStateWithLifecycle()
    val feedback = rememberCaptureFeedback()
    val fieldOfView = rememberCameraFieldOfView()

    val previewView = remember(context) {
        PreviewView(context).apply {
            // The projection maths assumes a uniform fill: see
            // SphereProjection.focalLengthPx.
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val buffer = rememberImageBufferManager()
    val bufferedFrames by buffer.frames.collectAsStateWithLifecycle()
    val sessionId by buffer.sessionId.collectAsStateWithLifecycle()

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    // The locked capture settings this device settled on, and the Camera the
    // locks were bound with. Read by the capture loop, and by the HUD to say
    // what the session is holding.
    var captureProfile by remember { mutableStateOf<SphereCaptureProfile?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var plan by remember { mutableStateOf<SphereTargetPlan?>(null) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var isHolding by remember { mutableStateOf(false) }
    var accuracy by remember { mutableStateOf(OrientationAccuracy.Unknown) }

    // A one-time reminder of how to stand, shown before the first frame falls
    // so the capture that follows is built on a steady pivot.
    var showInstructions by rememberSaveable { mutableStateOf(true) }

    // The stitch runs off the main thread and reports back from there, so its
    // progress travels as a StateFlow rather than as Compose state written from
    // a background dispatcher.
    val stitchProgress = remember { MutableStateFlow(StitchProgress.Preparing) }
    var stitchJob by remember { mutableStateOf<Job?>(null) }

    /**
     * Changes at the sensor's rate and is therefore never read during
     * composition — only inside [TargetOverlay]'s draw lambda.
     */
    var alignment by remember { mutableStateOf(AlignmentState()) }

    // Bind preview + capture once per lifecycle owner. CameraX unbinds on its own
    // when that lifecycle is destroyed.
    LaunchedEffect(lifecycleOwner, previewView) {
        val cameraProvider = try {
            context.awaitCameraProvider()
        } catch (e: Exception) {
            Log.e(TAG, "Camera provider unavailable", e)
            snackbarHostState.showSnackbar(
                context.getString(R.string.capture_failed, e.message.orEmpty())
            )
            return@LaunchedEffect
        }

        val profile = resolveSphereCaptureProfile(context)
        captureProfile = profile

        // Exposure, white balance and focus are locked for the whole session
        // (see SphereCaptureProfile): a sphere's seams are exposure and colour
        // seams, so no frame is allowed to re-meter on its own. The locks ride
        // in the preview's repeating request as well as the stills', so the
        // viewfinder shows exactly what each frame will record.
        val preview = Preview.Builder()
            .also { applySphereCaptureOptions(Camera2Interop.Extender(it), profile) }
            .build()
            .apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Frames are stitched, so a flash firing on some of them would leave
            // seams no amount of blending can hide.
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .build()
            )
            .setTargetRotation(context.currentDisplayRotation())
            .also { applySphereCaptureOptions(Camera2Interop.Extender(it), profile) }
            .also { applyStillImageOptions(Camera2Interop.Extender(it), profile) }
            .build()

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            imageCapture = capture
            boundCamera = camera
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            snackbarHostState.showSnackbar(
                context.getString(R.string.capture_failed, e.message.orEmpty())
            )
        }
    }

    // Old sessions are dead weight once a new one starts; clearing them keeps the
    // cache from growing by a sphere's worth of full-resolution JPEGs per run.
    LaunchedEffect(sessionId) {
        buffer.pruneStaleSessions()
    }

    // The capture loop. Collecting suspends across the shutter, which is what
    // keeps a second capture from starting while one is still being written —
    // the StateFlow simply conflates the samples that arrive meanwhile.
    LaunchedEffect(imageCapture) {
        val capture = imageCapture ?: return@LaunchedEffect
        val profile = captureProfile ?: return@LaunchedEffect
        val gate = AlignmentGate()

        // Devices that cannot park focus at infinity hold it at the first scene
        // instead. AE and AWB are already locked by the session, so this pins
        // the last free variable before any frame is taken; the trigger lands
        // before the first dwell can complete, so it cannot race a shutter.
        if (profile.focusMode == FocusMode.LOCK_ON_FIRST) {
            runCatching { boundCamera?.lockFocusOnCenter(previewView) }
        }

        tracker.orientation.collect { orientation ->
            if (!orientation.hasFix) return@collect
            accuracy = orientation.accuracy

            // A frame landing halfway through a stitch would not be in the set
            // being stitched, and would be deleted when that set is cleared.
            if (stitchJob != null) {
                gate.reset()
                return@collect
            }

            val currentPlan = plan
                ?: SphereTargetPlan.createForFieldOfView(
                    startYawDegrees = orientation.yawDegrees,
                    fieldOfView = fieldOfView,
                ).also { plan = it }

            val target = currentPlan.getOrNull(activeIndex)
            if (target == null) {
                // Sphere finished; stop guiding until the user starts a new one.
                gate.reset()
                alignment = AlignmentState()
                isHolding = false
                return@collect
            }

            val distance = SphereProjection.angularDistanceDegrees(orientation, target)
            val reading = gate.update(distance, SystemClock.elapsedRealtime())
            alignment = AlignmentState(
                distanceDegrees = distance,
                dwellProgress = reading.dwellProgress,
                isAligned = reading.isAligned,
            )
            isHolding = reading.isAligned

            if (!reading.isTriggered) return@collect

            val index = activeIndex
            alignment = alignment.copy(isCapturing = true)
            val result = capture.saveFrame(
                context = context,
                buffer = buffer,
                index = index,
                orientation = orientation,
                burstPerTarget = profile.burstPerTarget,
            )
            alignment = alignment.copy(isCapturing = false)

            result
                .onSuccess {
                    feedback.onFrameCaptured()
                    // Advance only on a frame that actually landed, so a failed
                    // capture is retried rather than silently skipped.
                    activeIndex = index + 1
                    gate.reset()
                }
                .onFailure { error ->
                    Log.e(TAG, "Frame $index failed", error)
                    gate.reset()
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.capture_failed, error.message.orEmpty())
                        )
                    }
                }
        }
    }

    val totalTargets = plan?.size ?: 0
    val isComplete = totalTargets > 0 && activeIndex >= totalTargets

    /** Clears the guidance state so the next sphere starts from scratch. */
    fun resetGuidance() {
        plan = null
        activeIndex = 0
        isHolding = false
        alignment = AlignmentState()
    }

    /**
     * Hands the buffered frames to the stitcher and passes on what comes back.
     *
     * Started lazily so [stitchJob] is set before the body can reach its own
     * `finally` and clear it.
     */
    fun startStitch() {
        if (stitchJob != null) return
        // The stitcher works from where each frame was shot, not just its
        // pixels: the capture attitude is what places it on the sphere.
        val frames = buffer.frames.value.map { frame ->
            SphereFrame(
                file = frame.file,
                pose = CameraPose(
                    yawDegrees = frame.yawDegrees,
                    pitchDegrees = frame.pitchDegrees,
                    rollDegrees = frame.rollDegrees,
                ),
            )
        }
        stitchProgress.value = StitchProgress.Preparing

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                PhotoSphereStitcher.stitchPhotos(
                    frames = frames,
                    horizontalFovDegrees = fieldOfView.horizontalDegrees,
                    verticalFovDegrees = fieldOfView.verticalDegrees,
                ) { stitchProgress.value = it }
                    .onSuccess { sphere ->
                        val stitched = try {
                            withContext(Dispatchers.IO) {
                                SphereImageStore.writeStitchedSphere(context, sphere)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // A full sphere that will not fit on disk. The
                            // frames survive, so a retry after clearing space
                            // costs nothing more than the stitch.
                            Log.e(TAG, "Could not write the stitched sphere", e)
                            null
                        } finally {
                            sphere.recycle()
                        }

                        if (stitched == null) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.stitch_failed,
                                    context.getString(R.string.stitch_error_write_failed),
                                )
                            )
                            return@onSuccess
                        }

                        // The frames have done their job. Clearing before the
                        // handover matters: this coroutine is cancelled the
                        // moment the result screen replaces this one.
                        buffer.clear()
                        resetGuidance()
                        onSphereReady(stitched)
                    }
                    .onFailure { error ->
                        Log.e(TAG, "Stitch failed", error)
                        // Frames are deliberately kept: the usual fix is to
                        // capture a few more and try again.
                        snackbarHostState.showSnackbar(context.stitchFailureMessage(error))
                    }
            } finally {
                stitchJob = null
            }
        }
        stitchJob = job
        job.start()
    }

    val stitchState by stitchProgress.collectAsStateWithLifecycle()
    if (stitchJob != null) {
        StitchingDialog(
            progress = stitchState,
            onCancel = { stitchJob?.cancel() },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )

            TargetOverlay(
                orientation = { orientationState.value },
                alignment = { alignment },
                plan = plan,
                activeIndex = activeIndex,
                fieldOfView = fieldOfView,
            )

            if (showInstructions && activeIndex == 0 && bufferedFrames.isEmpty()) {
                CaptureInstructions(
                    onDismiss = { showInstructions = false },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                )
            }

            CaptureHud(
                capturedCount = activeIndex.coerceAtMost(totalTargets),
                totalTargets = totalTargets,
                hint = captureHint(
                    isSensorAvailable = tracker.isSensorAvailable,
                    isCameraReady = imageCapture != null,
                    hasPlan = plan != null,
                    isComplete = isComplete,
                    isHolding = isHolding,
                    accuracy = accuracy,
                ),
                lockBadge = when (captureProfile?.focusMode) {
                    FocusMode.FIXED_INFINITY -> stringResource(R.string.capture_lock_infinity)
                    FocusMode.LOCK_ON_FIRST -> stringResource(R.string.capture_lock_first)
                    FocusMode.FIXED_FOCUS -> stringResource(R.string.capture_lock_fixed)
                    null -> null
                },
                isComplete = isComplete,
                // Below this the stitcher has nothing to work with: a handful of
                // frames from one corner of the sphere cannot be registered.
                canStitch = bufferedFrames.size >= PhotoSphereStitcher.MIN_FRAMES &&
                    stitchJob == null,
                onFinish = { startStitch() },
                onRestart = {
                    resetGuidance()
                    scope.launch { buffer.cancelSession() }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets),
            )
        }
    }
}

/** Frame counter, progress bar, and the one line of guidance the user needs. */
@Composable
private fun CaptureHud(
    capturedCount: Int,
    totalTargets: Int,
    hint: String,
    lockBadge: String?,
    isComplete: Boolean,
    canStitch: Boolean,
    onFinish: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.capture_progress, capturedCount, totalTargets),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (totalTargets > 0) {
                LinearProgressIndicator(
                    progress = { capturedCount.toFloat() / totalTargets },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50)),
                    color = Color(0xFF3DDC84),
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
            if (lockBadge != null) {
                Text(
                    text = lockBadge,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF8BC34A),
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
            // Offered as soon as there is enough to stitch, not only at the end:
            // a user who has covered what they care about should not have to
            // walk the remaining targets to get a sphere out of it.
            if (canStitch) {
                Button(onClick = onFinish) {
                    Text(stringResource(R.string.capture_finish_stitch))
                }
            }
            if (isComplete) {
                Button(onClick = onRestart) {
                    Text(stringResource(R.string.capture_restart))
                }
            }
        }
    }
}

/**
 * The one-shot reminder shown before the first frame.
 *
 * A floating card rather than a modal dialog: it sits over the viewfinder
 * without taking focus or pausing the camera, and a single tap dismisses it for
 * the session. A sphere is only as steady as the pivot it was shot on, so the
 * points are about stance and stillness, not about the controls.
 */
@Composable
private fun CaptureInstructions(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color.Black.copy(alpha = 0.82f),
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.capture_instructions_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            listOf(
                R.string.capture_instructions_1,
                R.string.capture_instructions_2,
                R.string.capture_instructions_3,
                R.string.capture_instructions_4,
            ).forEach { instruction ->
                Text(
                    text = stringResource(instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                )
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.capture_instructions_dismiss))
            }
        }
    }
}

/**
 * Blocks the screen while OpenCV works.
 *
 * Deliberately modal and not dismissable by tapping outside: the stitch owns the
 * frames for its duration, so the capture loop is paused behind it and there is
 * nothing useful to go back to. Cancelling is offered explicitly, and takes
 * effect at the next stage boundary — the native call cannot be interrupted
 * partway.
 */
@Composable
private fun StitchingDialog(
    progress: StitchProgress,
    onCancel: () -> Unit,
) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.stitch_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                val fraction = progress.fraction
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction })
                } else {
                    // Everything inside OpenCV's stitch call is opaque, so the
                    // spinner spins rather than reporting a made-up percentage.
                    CircularProgressIndicator()
                }

                Text(
                    text = progress.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )

                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.stitch_cancel))
                }
            }
        }
    }
}

/** The one line describing what the stitcher is currently doing. */
@Composable
private fun StitchProgress.label(): String = when (stage) {
    StitchStage.Preparing -> stringResource(R.string.stitch_stage_preparing)
    StitchStage.Reading -> stringResource(R.string.stitch_stage_reading, completed, total)
    StitchStage.Stitching -> stringResource(R.string.stitch_stage_stitching)
    StitchStage.Projecting -> stringResource(R.string.stitch_stage_projecting)
}

/** Turns a failed stitch into something worth showing a user. */
private fun Context.stitchFailureMessage(error: Throwable): String {
    val status = (error as? StitchException)?.status ?: StitchStatus.Unknown
    val reason = when (status) {
        StitchStatus.NeedMoreImages,
        StitchStatus.NoInputImages,
        -> getString(R.string.stitch_error_need_more_images)

        StitchStatus.AlignmentFailed -> getString(R.string.stitch_error_alignment)
        StitchStatus.CameraEstimationFailed -> getString(R.string.stitch_error_camera_estimation)
        StitchStatus.OpenCvUnavailable -> getString(R.string.stitch_error_opencv_unavailable)
        StitchStatus.UnreadableInput -> getString(R.string.stitch_error_unreadable_input)
        StitchStatus.OutOfMemory -> getString(R.string.stitch_error_out_of_memory)
        StitchStatus.EmptyResult,
        StitchStatus.Unknown,
        StitchStatus.Ok,
        -> getString(R.string.stitch_error_unknown, status.code)
    }
    return getString(R.string.stitch_failed, reason)
}

/** Picks the single most useful thing to tell the user right now. */
@Composable
private fun captureHint(
    isSensorAvailable: Boolean,
    isCameraReady: Boolean,
    hasPlan: Boolean,
    isComplete: Boolean,
    isHolding: Boolean,
    accuracy: OrientationAccuracy,
): String = when {
    !isSensorAvailable -> stringResource(R.string.capture_orientation_unavailable)
    !isCameraReady -> stringResource(R.string.capture_starting)
    isComplete -> stringResource(R.string.capture_sphere_complete)
    !hasPlan -> stringResource(R.string.capture_waiting_for_orientation)
    accuracy == OrientationAccuracy.Unreliable -> stringResource(R.string.capture_low_accuracy)
    isHolding -> stringResource(R.string.capture_hint_hold)
    else -> stringResource(R.string.capture_hint_search)
}

/**
 * Writes one frame to the session's cache directory and buffers it.
 *
 * The device's attitude at the moment of capture goes into the buffer entry and
 * into the file's EXIF: the stitcher gets a free initial guess at where each
 * frame belongs, which is worth far more than the couple of milliseconds it
 * costs here.
 *
 * With [burstPerTarget] > 1 the target is shot [burstPerTarget] times at the
 * same locked settings and the sharpest of the burst is kept. The losers are
 * deleted in [ImageBufferManager.commitBestFrame], so the session still holds
 * one file per index. A frame halfway through its burst that fails leaves the
 * reserved candidates behind; they are deleted here so the session directory
 * stays clean.
 */
private suspend fun ImageCapture.saveFrame(
    context: Context,
    buffer: ImageBufferManager,
    index: Int,
    orientation: OrientationData,
    burstPerTarget: Int,
): Result<File> {
    val requests = try {
        if (burstPerTarget > 1) {
            buffer.reserveBurstFrames(index, burstPerTarget)
        } else {
            listOf(buffer.reserveFrame(index))
        }
    } catch (e: Exception) {
        return Result.failure(e)
    }

    return try {
        requests.forEach { takePictureTo(context, it.outputOptions) }

        val best = if (burstPerTarget > 1) {
            // Scoring decodes each candidate, which is cheap next to the captures
            // that just ran but is still real work — keep it off the main thread.
            withContext(Dispatchers.Default) {
                SharpnessSelection.pickSharpest(requests.map { it.file })
            }
        } else {
            requests.first().file
        }

        buffer.commitBestFrame(best, index, orientation)
        Result.success(best)
    } catch (e: CancellationException) {
        // Leaving the screen mid-shutter is not a capture failure; let the
        // cancellation travel rather than reporting it to the user.
        throw e
    } catch (e: Exception) {
        // Half a burst written, none of it buffered: clear the candidates so the
        // session directory does not accumulate rejected frames.
        withContext(NonCancellable + Dispatchers.IO) {
            requests.forEach { request ->
                if (request.file.exists() && !request.file.delete()) {
                    Log.w(TAG, "Could not delete failed burst file ${request.file.name}")
                }
            }
        }
        Result.failure(e)
    }
}

/**
 * Locks autofocus at the centre of the preview, awaiting convergence.
 *
 * Used on devices without `AF_MODE_OFF`, where focus is the one variable the
 * session cannot pin from the start. AE and AWB are already held by the session
 * request, so the trigger only moves focus — the metering region is the centre
 * of the viewfinder, where the alignment gate insists the next frame be aimed
 * anyway. After the trigger completes in `AF_MODE_AUTO` the lens stays where it
 * is until another trigger comes along, which is what GCam's tap-to-focus lock
 * amounts to.
 */
private suspend fun Camera.lockFocusOnCenter(previewView: PreviewView) {
    val point = previewView.meteringPointFactory.createPoint(
        previewView.width / 2f,
        previewView.height / 2f,
    )
    val future = cameraControl.startFocusAndMetering(
        FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build(),
    )
    suspendCancellableCoroutine { continuation ->
        future.addListener(
            {
                // A failed focus sweep is not worth failing the session over; the
                // lens just stays where auto left it.
                runCatching { future.get() }
                continuation.resume(Unit)
            },
            ContextCompat.getMainExecutor(previewView.context),
        )
    }
}

/** Suspending [ImageCapture.takePicture]; the callback form fits nothing here. */
private suspend fun ImageCapture.takePictureTo(
    context: Context,
    outputOptions: ImageCapture.OutputFileOptions,
): ImageCapture.OutputFileResults = suspendCancellableCoroutine { continuation ->
    takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                continuation.resume(output)
            }

            override fun onError(exception: ImageCaptureException) {
                continuation.resumeWithException(exception)
            }
        },
    )
}

/**
 * Suspending wrapper around [ProcessCameraProvider.getInstance].
 *
 * The provider arrives as a `ListenableFuture`; bridging it here keeps the call
 * site free of callbacks without a coroutines-guava dependency.
 */
private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
