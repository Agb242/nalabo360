package com.n30dyn4m1c.photosphere.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.n30dyn4m1c.photosphere.storage.StitchedSphere

/**
 * Android's capture screen is the original CameraX implementation, kept behind
 * the shared signature; the app shell neither knows nor cares what drives the
 * viewfinder.
 */
@Composable
actual fun SphereCaptureScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier,
) {
    PhotoSphereCameraScreen(onSphereReady = onSphereReady, modifier = modifier)
}
