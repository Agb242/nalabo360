package com.n30dyn4m1c.photosphere.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.n30dyn4m1c.photosphere.storage.StitchedSphere

/**
 * The guided capture screen, backed by each platform's camera stack.
 *
 * Android drives CameraX behind this signature; iOS will drive AVFoundation;
 * hosts with no camera render an explanation instead. Either way the contract
 * is what the app shell sees: show the viewfinder, run the guided plan, and
 * hand back one finished sphere when the stitch completes.
 */
@Composable
expect fun SphereCaptureScreen(
    onSphereReady: (StitchedSphere) -> Unit,
    modifier: Modifier = Modifier,
)
