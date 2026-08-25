package com.n30dyn4m1c.photosphere.camera

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitView
import com.n30dyn4m1c.photosphere.metadata.GPanoMetadata
import com.n30dyn4m1c.photosphere.metadata.GPanoXmpInjector
import com.n30dyn4m1c.photosphere.sensor.rememberOrientationSensor
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.CameraPose
import com.n30dyn4m1c.photosphere.stitching.PhotoSphereStitcher
import com.n30dyn4m1c.photosphere.stitching.RgbImage
import com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile
import com.n30dyn4m1c.photosphere.ui.Strings
import com.n30dyn4m1c.photosphere.stitching.SphereFrame
import com.n30dyn4m1c.photosphere.stitching.StitchException
import com.n30dyn4m1c.photosphere.stitching.StitchProgress
import com.n30dyn4m1c.photosphere.stitching.StitchStage
import com.n30dyn4m1c.photosphere.stitching.StitchStatus
import com.n30dyn4m1c.photosphere.stitching.platformImageCodec
import com.n30dyn4m1c.photosphere.ui.theme.PillShape
import com.n30dyn4m1c.photosphere.util.KLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory

private const val TAG = "SphereCaptureScreen"

/**
 * Portrait frames are assumed to cover this much of the sphere.
 *
 * Android queries the sensor traits; ImageIO exposes the same numbers, but
 * behind a per-device walk that v1 skips. 64° × 80° is a hair *over* the wide
 * lens of recent iPhones — over-covering blends frames that agree a little
 * less, while under-covering leaves holes, so err wide until optics are queried
 * properly.
 */
private const val ASSUMED_HORIZONTAL_FOV = 64f
private const val ASSUMED_VERTICAL_FOV = 80f

/** Encode quality of the cached master, matching Android's store. */
private const val SPHERE_JPEG_QUALITY = 95

/**
 * The guided capture screen on iOS: AVFoundation viewfinder, one shutter, live
 * attitude from CoreMotion, and the shared stitcher on Finish.
 *
 * Deliberately simpler than the Android screen's automatic target plan: the
 * user aims by eye against the yaw readout and taps when they like. Frames land
 * in the same cache layout, carry the same pose records, and feed the same
 * pure-Kotlin pipeline, so what comes out is a real GPano JPEG — the
 * plan-driven automation can be layered on once capture itself is proven on
 * hardware.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SphereCaptureScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val sensor = rememberOrientationSensor()
    val orientation by sensor.orientation.collectAsState()

    val fileSystem = FileSystem.SYSTEM
    val sessionDirectory = remember { newSessionDirectory() }
    val frames = remember { mutableStateListOf<CapturedFrame>() }
    var stitchError by remember { mutableStateOf<String?>(null) }
    var stitchJob by remember { mutableStateOf<Job?>(null) }
    val stitchProgress = remember { MutableStateFlow<StitchProgress?>(null) }
    val stitchState by stitchProgress.collectAsState()

    val controller = remember { IosCameraController() }

    UIKitView(
        factory = { controller.createPreviewView() },
        update = { controller.layoutPreview(it) },
        modifier = modifier.fillMaxSize(),
    )
    DisposableEffect(controller) {
        controller.start()
        onDispose {
            controller.stop()
            // A screen torn down mid-session takes its unexported frames with
            // it; only the stitched master outlives the capture run.
            frames.forEach { fileSystem.delete(it.file, mustExist = false) }
        }
    }

    fun undoLastFrame() {
        frames.removeLastOrNull()?.let { last ->
            fileSystem.delete(last.file, mustExist = false)
        }
    }

    fun cancelStitch() {
        stitchJob?.cancel()
        stitchJob = null
        stitchProgress.value = null
    }

    fun startStitch() {
        if (stitchJob != null) return
        val profile = SphereDeviceProfile.forDevice()
        val stitchFrames = frames.map { frame ->
            SphereFrame(
                file = frame.file,
                pose = CameraPose(
                    yawDegrees = frame.yawDegrees,
                    pitchDegrees = frame.pitchDegrees,
                    rollDegrees = frame.rollDegrees,
                ),
            )
        }
        stitchJob = scope.launch(Dispatchers.Default) {
            try {
                PhotoSphereStitcher.stitchPhotos(
                    frames = stitchFrames,
                    horizontalFovDegrees = ASSUMED_HORIZONTAL_FOV,
                    verticalFovDegrees = ASSUMED_VERTICAL_FOV,
                    maxInputDimension = profile.stitchMaxInputDimension,
                    maxOutputWidth = profile.stitchMaxOutputWidth,
                    unsharpAmount = profile.unsharpAmount,
                    pivot = profile.pivot,
                ) { stitchProgress.value = it }
                    .onSuccess { image ->
                        // The write survives the result-screen handover that
                        // cancels this coroutine the moment this returns.
                        // NonCancellable alone keeps the launch dispatcher:
                        // Dispatchers.IO is a JVM fragment of coroutines.
                        val stitched = withContext(NonCancellable) {
                            writeStitchedSphere(image)
                        }
                        frames.forEach { fileSystem.delete(it.file, mustExist = false) }
                        frames.clear()
                        onSphereReady(stitched)
                    }
                    .onFailure { error ->
                        KLog.e(TAG, "Stitch failed", error)
                        // Frames are deliberately kept: the usual fix is to
                        // capture more and try again.
                        stitchError = Strings.stitchFailed(stitchFailureReason(error))
                        stitchProgress.value = null
                    }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                KLog.e(TAG, "Stitch crashed", error)
                stitchError = Strings.stitchFailed(Strings.stitchErrorUnknown(StitchStatus.Unknown.code))
                stitchProgress.value = null
            } finally {
                stitchJob = null
            }
        }
    }

    // -- Chrome --------------------------------------------------------------

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "${frames.size} ${Strings.CAPTURE_PROGRESS_FRAMES}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = if (sensor.isSensorAvailable) {
                    "${Strings.ORIENTATION_YAW} ${Strings.orientationDegrees(orientation.yawDegrees)}"
                } else {
                    Strings.CAPTURE_ORIENTATION_UNAVAILABLE
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = ::undoLastFrame, enabled = frames.isNotEmpty()) {
                Text(Strings.CAPTURE_UNDO, color = Color.White)
            }

            // The shutter: a plain white ring, big enough for a thumb while
            // walking. Disabled until the camera and sensor can back it up.
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(5.dp, Color.Black.copy(alpha = 0.35f), CircleShape)
                    .clickable(enabled = controller.isRunning && sensor.isSensorAvailable) {
                        captureFrame(controller, sensor.orientation.value, frames, sessionDirectory)
                    },
            )

            Button(
                onClick = ::startStitch,
                enabled = frames.size >= PhotoSphereStitcher.MIN_STITCHABLE_FRAMES,
                shape = PillShape,
            ) {
                Text(Strings.CAPTURE_FINISH_STITCH)
            }
        }
    }

    // Stitch overlay: stage line plus cancel, mirroring the Android dialog.
    AnimatedVisibility(
        visible = stitchState != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        AlertDialog(
            onDismissRequest = ::cancelStitch,
            title = { Text(Strings.STITCH_TITLE) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stitchState?.stageLabel().orEmpty(),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = ::cancelStitch) {
                    Text(Strings.STITCH_CANCEL)
                }
            },
        )
    }

    // Stitch failure, as a plain dialog: there is no snackbar host here yet.
    stitchError?.let { message ->
        AlertDialog(
            onDismissRequest = { stitchError = null },
            title = { Text(Strings.APP_NAME) },
            text = { Text(message) },
            confirmButton = {
                OutlinedButton(onClick = { stitchError = null }) {
                    Text("OK")
                }
            },
        )
    }
}

/** Buffers one photo plus its shutter-time attitude into the session cache. */
@OptIn(ExperimentalForeignApi::class)
private fun captureFrame(
    controller: IosCameraController,
    pose: com.n30dyn4m1c.photosphere.sensor.OrientationData,
    frames: MutableList<CapturedFrame>,
    sessionDirectory: Path,
) {
    controller.capture { data ->
        if (data == null) {
            KLog.w(TAG, "Shutter returned no image")
            return@capture
        }
        // AVFoundation delivers captures on one serial queue, so plain
        // bookkeeping is safe — `synchronized` does not exist off the JVM.
        val index = frames.size
        val file = sessionDirectory / ("frame_" + index.toString().padStart(3, '0') + ".jpg")
        try {
            FileSystem.SYSTEM.write(file) { write(data.toByteArray()) }
        } catch (error: Exception) {
            KLog.e(TAG, "Could not buffer frame $index", error)
            return@capture
        }
        frames.add(
            CapturedFrame(
                file = file,
                yawDegrees = pose.yawDegrees,
                pitchDegrees = pose.pitchDegrees,
                rollDegrees = pose.rollDegrees,
            ),
        )
    }
}

/** The one line describing what the stitcher is currently doing. */
private fun StitchProgress.stageLabel(): String = when (stage) {
    StitchStage.Preparing -> Strings.STITCH_STAGE_PREPARING
    StitchStage.Reading -> Strings.stitchStageReading(completed, total)
    StitchStage.Seaming -> Strings.STITCH_STAGE_SEAMING
    StitchStage.Stitching -> Strings.STITCH_STAGE_STITCHING
    StitchStage.Projecting -> Strings.STITCH_STAGE_PROJECTING
}

/** Turns a failed stitch into something worth showing a user. */
internal fun stitchFailureReason(error: Throwable): String =
    when ((error as? StitchException)?.status) {
        StitchStatus.NeedMoreImages, StitchStatus.NoInputImages ->
            Strings.STITCH_ERROR_NEED_MORE_IMAGES
        StitchStatus.AlignmentFailed -> Strings.STITCH_ERROR_ALIGNMENT
        StitchStatus.CameraEstimationFailed -> Strings.STITCH_ERROR_CAMERA_ESTIMATION
        StitchStatus.UnreadableInput -> Strings.STITCH_ERROR_UNREADABLE_INPUT
        else -> Strings.stitchErrorUnknown(
            (error as? StitchException)?.status?.code ?: StitchStatus.Unknown.code,
        )
    }

/** One buffered frame plus the attitude it was shot at. */
data class CapturedFrame(
    val file: Path,
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
)

/** `yyyyMMdd_HHmmss_SSS` — same convention, same sort order as Android. */
private fun newSessionId(): String {
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyyMMdd_HHmmss_SSS"
        locale = NSLocale("en_US_POSIX")
    }
    return formatter.stringFromDate(NSDate())
}

private fun newSessionDirectory(): Path =
    (NSTemporaryDirectory() + "sphere_sessions/" + newSessionId()).toPath()

/**
 * Encodes the finished canvas into the app cache as a GPano-tagged JPEG.
 *
 * The iOS twin of Android's SphereImageStore.writeStitchedSphere: encode via
 * the platform codec, tag with GPano (pure okio), swap into place under a fresh
 * name, then clear every older sphere so only one waits for its owner at a
 * time.
 */
private fun writeStitchedSphere(image: RgbImage): StitchedSphere {
    val directory = (NSTemporaryDirectory() + "spheres/").toPath()
    FileSystem.SYSTEM.createDirectory(directory)

    val timestamp = NSDateFormatter().apply {
        dateFormat = "yyyyMMdd_HHmmss"
        locale = NSLocale("en_US_POSIX")
    }.stringFromDate(NSDate())
    val file = directory / "sphere_$timestamp.jpg"
    val temp = directory / (file.name + ".tmp")

    try {
        val jpeg = platformImageCodec().encodeJpeg(image, SPHERE_JPEG_QUALITY)
            ?: throw IllegalStateException("Could not encode the stitched sphere")
        FileSystem.SYSTEM.write(temp) { write(jpeg) }
        GPanoXmpInjector.inject(temp, gpanoFor(image))
            .onFailure { error ->
                // Recoverable: the file is a valid 2:1 JPEG either way, it will
                // simply open flat instead of as a sphere.
                KLog.w(TAG, "Could not write GPano metadata", error)
            }
        FileSystem.SYSTEM.atomicMove(temp, file)
    } finally {
        FileSystem.SYSTEM.delete(temp, mustExist = false)
    }

    // Only one sphere is ever in play; drop the previous one after the swap.
    FileSystem.SYSTEM.list(directory).forEach { stale ->
        if (stale != file) {
            runCatching { FileSystem.SYSTEM.delete(stale, mustExist = false) }
        }
    }

    return StitchedSphere(
        file = file,
        width = image.width,
        height = image.height,
        diagnostics = null,
    )
}

private fun gpanoFor(image: RgbImage): GPanoMetadata =
    GPanoMetadata.forFullPano(image.width, image.height)
