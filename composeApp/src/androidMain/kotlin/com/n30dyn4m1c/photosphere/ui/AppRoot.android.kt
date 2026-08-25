package com.n30dyn4m1c.photosphere.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * The Android half of the app shell: the runtime-permission gate and the list
 * of permissions it asks for.
 */

/** Runtime (dangerous) permissions this app needs. */
internal actual fun requiredRuntimePermissions(): List<String> = buildList {
    add(Manifest.permission.CAMERA)
    // Storage is only requested on API 28 and below. From API 29 the app writes
    // through MediaStore, which needs no permission for its own media.
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
        add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}

/** Where the user currently stands with a set of runtime permissions. */
private enum class PermissionStatus {
    /** Not asked yet, or the system will show the dialog on the next request. */
    Unknown,

    /** Every permission in the set is granted. */
    Granted,

    /** Denied once; the system will still show the dialog, so explain why first. */
    ShowRationale,

    /** Denied with "don't ask again" (or blocked by policy) — only Settings helps. */
    PermanentlyDenied,
}

/**
 * Gates [content] behind the given runtime permissions.
 *
 * The request is fired once when the gate first appears. If the user denies it,
 * a rationale with a retry button is shown; if the OS will no longer surface the
 * dialog, the button deep-links into the app's settings page instead. Returning
 * from Settings re-checks the grant, so the UI recovers without a restart.
 */
@Composable
actual fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()

    var status by remember {
        mutableStateOf(
            if (context.hasAllPermissions(permissions)) {
                PermissionStatus.Granted
            } else {
                PermissionStatus.Unknown
            }
        )
    }
    var hasRequested by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        status = when {
            // Asked of the system, not inferred from `results`. When the dialog
            // is dismissed without an answer — a swipe away, a phone call taking
            // the foreground, the screen locking — the contract delivers an
            // *empty* map, and `emptyMap().values.all { }` is vacuously true. Read
            // straight from the map, that cancellation would have counted as a
            // grant and dropped the user into a camera screen with no camera
            // permission, where the bind fails and the viewfinder is simply black.
            context.hasAllPermissions(permissions) -> PermissionStatus.Granted

            // A real denial the system will still show a dialog for, or that same
            // cancellation. Both leave the user somewhere they can retry from,
            // which is what matters: `shouldShowRationale` reads false after a
            // cancel, so trusting it here would send someone who never answered
            // the dialog off to the Settings app to fix a setting they had not
            // touched. Only an explicit "deny" that the system will no longer
            // surface a dialog for earns that trip.
            results.isEmpty() -> PermissionStatus.ShowRationale

            activity != null && permissions.any(activity::shouldShowRationale) ->
                PermissionStatus.ShowRationale

            else -> PermissionStatus.PermanentlyDenied
        }
    }

    // Ask once, automatically, the first time the gate is shown.
    LaunchedEffect(permissions) {
        if (status != PermissionStatus.Granted && !hasRequested) {
            hasRequested = true
            launcher.launch(permissions.toTypedArray())
        }
    }

    // Re-check on resume: the user may have granted the permission in Settings.
    LifecycleResumeEffect(permissions) {
        if (context.hasAllPermissions(permissions)) {
            status = PermissionStatus.Granted
        }
        onPauseOrDispose { }
    }

    when (status) {
        PermissionStatus.Granted -> content()

        PermissionStatus.Unknown -> PermissionMessage(
            modifier = modifier,
            message = Strings.PERMISSION_CAMERA_RATIONALE,
            actionLabel = null,
            onAction = null,
        )

        PermissionStatus.ShowRationale -> PermissionMessage(
            modifier = modifier,
            message = Strings.PERMISSION_CAMERA_RATIONALE,
            actionLabel = Strings.PERMISSION_GRANT,
            onAction = { launcher.launch(permissions.toTypedArray()) },
        )

        PermissionStatus.PermanentlyDenied -> PermissionMessage(
            modifier = modifier,
            message = Strings.PERMISSION_CAMERA_DENIED_PERMANENTLY,
            actionLabel = Strings.PERMISSION_OPEN_SETTINGS,
            onAction = { context.openAppSettings() },
        )
    }
}

private fun Context.hasAllPermissions(permissions: List<String>): Boolean =
    permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

private fun Activity.shouldShowRationale(permission: String): Boolean =
    shouldShowRequestPermissionRationale(permission)

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
