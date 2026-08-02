package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.n30dyn4m1c.photosphere.R
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CaptureScreen"

/**
 * Live camera preview plus a shutter that writes each frame to the gallery.
 *
 * This is the capture half of the sphere pipeline: it produces the ordered set
 * of overlapping frames that the OpenCV stitcher later consumes. Guidance
 * overlays and the stitch step itself are not wired up yet.
 */
@Composable
fun CaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val previewView = remember { PreviewView(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var frameCount by remember { mutableIntStateOf(0) }

    // Bind the camera once per lifecycle owner; CameraX unbinds automatically
    // when that lifecycle is destroyed.
    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(insets)
                    .padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (imageCapture == null) {
                        stringResource(R.string.capture_starting)
                    } else {
                        stringResource(R.string.capture_frames_taken, frameCount)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                ExtendedFloatingActionButton(
                    onClick = {
                        val capture = imageCapture ?: return@ExtendedFloatingActionButton
                        capture.takeSphereFrame(
                            context = context,
                            index = frameCount,
                            onSaved = { displayName ->
                                frameCount += 1
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.capture_saved, displayName)
                                    )
                                }
                            },
                            onError = { message ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        context.getString(R.string.capture_failed, message)
                                    )
                                }
                            },
                        )
                    },
                    icon = { Icon(Icons.Filled.CameraAlt, contentDescription = null) },
                    text = { Text(stringResource(R.string.capture_shutter)) },
                )
            }
        }
    }
}

/**
 * Suspending wrapper around [ProcessCameraProvider.getInstance].
 *
 * The provider is exposed as a `ListenableFuture`; bridging it here keeps the
 * call site free of callbacks without pulling in a coroutines-guava dependency.
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

private fun ImageCapture.takeSphereFrame(
    context: Context,
    index: Int,
    onSaved: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val request = SphereImageStore.newFrameOutputOptions(context, index)
    takePicture(
        request.outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let { uri ->
                    SphereImageStore.stampSphereMetadata(context, uri, index)
                }
                onSaved(request.displayName)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.e(TAG, "Frame capture failed", exception)
                onError(exception.message.orEmpty())
            }
        },
    )
}
