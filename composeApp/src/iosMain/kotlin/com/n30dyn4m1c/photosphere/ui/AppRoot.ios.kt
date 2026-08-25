package com.n30dyn4m1c.photosphere.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

/**
 * The iOS half of the app shell: the camera-permission gate.
 *
 * iOS has one dangerous permission here — camera access — and no storage ask at
 * all (exporting into the photo library uses a separate add-only grant that is
 * requested when export first runs).
 */
internal actual fun requiredRuntimePermissions(): List<String> = emptyList()

private enum class PermissionStatus {
    /** Not asked yet; the system dialog fires on first composition. */
    Unknown,

    /** Camera granted. */
    Granted,

    /** The user said no; only Settings helps now. */
    Denied,
}

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    var status by remember {
        mutableStateOf(currentPermissionStatus())
    }

    // Ask once, automatically, the first time the gate is shown. iOS shows its
    // own rationale inside the alert, so there is no pre-prompt to stage.
    LaunchedEffect(Unit) {
        if (status == PermissionStatus.Unknown &&
            AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo) ==
            AVAuthorizationStatusNotDetermined
        ) {
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { _ ->
                status = currentPermissionStatus()
            }
        }
    }

    when (status) {
        PermissionStatus.Granted -> content()

        PermissionStatus.Unknown -> PermissionMessage(
            modifier = modifier,
            message = Strings.PERMISSION_CAMERA_RATIONALE,
            actionLabel = null,
            onAction = null,
        )

        PermissionStatus.Denied -> PermissionMessage(
            modifier = modifier,
            message = Strings.PERMISSION_CAMERA_DENIED_PERMANENTLY,
            actionLabel = Strings.PERMISSION_OPEN_SETTINGS,
            onAction = { openAppSettings() },
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun currentPermissionStatus(): PermissionStatus =
    when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
        AVAuthorizationStatusAuthorized -> PermissionStatus.Granted
        AVAuthorizationStatusDenied, AVAuthorizationStatusRestricted ->
            PermissionStatus.Denied
        else -> PermissionStatus.Unknown
    }

@OptIn(ExperimentalForeignApi::class)
private fun openAppSettings() {
    // The constant's public string value; the Kotlin bindings do not surface it.
    val url = NSURL.URLWithString("UIApplicationOpenSettingsURLString") ?: return
    UIApplication.sharedApplication.openURL(url)
}
