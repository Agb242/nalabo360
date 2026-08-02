package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraSelector
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.sensor.OrientationAccuracy
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationTracker
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * The caller is responsible for the CAMERA permission; see
 * `MainActivity.RequirePermissions`.
 */
@Composable
fun PhotoSphereCameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var plan by remember { mutableStateOf<SphereTargetPlan?>(null) }
    var activeIndex by remember { mutableIntStateOf(0) }
    var sessionId by remember { mutableStateOf(SphereImageStore.newSessionId()) }
    var isHolding by remember { mutableStateOf(false) }
    var accuracy by remember { mutableStateOf(OrientationAccuracy.Unknown) }

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

        val preview = Preview.Builder().build().apply {
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
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
            )
            imageCapture = capture
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
        withContext(Dispatchers.IO) { SphereImageStore.pruneSessions(context, sessionId) }
    }

    // The capture loop. Collecting suspends across the shutter, which is what
    // keeps a second capture from starting while one is still being written —
    // the StateFlow simply conflates the samples that arrive meanwhile.
    LaunchedEffect(imageCapture) {
        val capture = imageCapture ?: return@LaunchedEffect
        val gate = AlignmentGate()

        tracker.orientation.collect { orientation ->
            if (!orientation.hasFix) return@collect
            accuracy = orientation.accuracy

            val currentPlan = plan
                ?: SphereTargetPlan.create(startYawDegrees = orientation.yawDegrees)
                    .also { plan = it }

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
                sessionId = sessionId,
                index = index,
                orientation = orientation,
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
                isComplete = isComplete,
                onRestart = {
                    plan = null
                    activeIndex = 0
                    isHolding = false
                    alignment = AlignmentState()
                    sessionId = SphereImageStore.newSessionId()
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
    isComplete: Boolean,
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
            if (isComplete) {
                Button(onClick = onRestart) {
                    Text(stringResource(R.string.capture_restart))
                }
            }
        }
    }
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
 * Writes one frame to the session's cache directory.
 *
 * The device's attitude at the moment of capture is stamped into EXIF: the
 * stitcher gets a free initial guess at where each frame belongs, which is worth
 * far more than the couple of milliseconds it costs here.
 */
private suspend fun ImageCapture.saveFrame(
    context: Context,
    sessionId: String,
    index: Int,
    orientation: OrientationData,
): Result<File> = try {
    val directory = withContext(Dispatchers.IO) {
        SphereImageStore.sessionDirectory(context, sessionId)
    }
    val request = SphereImageStore.newTempFrameOptions(directory, index)

    takePictureTo(context, request.outputOptions)

    withContext(Dispatchers.IO) {
        SphereImageStore.stampCaptureOrientation(
            file = request.file,
            index = index,
            yawDegrees = orientation.yawDegrees,
            pitchDegrees = orientation.pitchDegrees,
            rollDegrees = orientation.rollDegrees,
        )
    }
    Result.success(request.file)
} catch (e: CancellationException) {
    // Leaving the screen mid-shutter is not a capture failure; let the
    // cancellation travel rather than reporting it to the user.
    throw e
} catch (e: Exception) {
    Result.failure(e)
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
