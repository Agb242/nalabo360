package com.n30dyn4m1c.photosphere

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.n30dyn4m1c.photosphere.camera.CaptureScreen
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoSphereTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RequirePermissions(permissions = REQUIRED_PERMISSIONS) {
                        CaptureScreen()
                    }
                }
            }
        }
    }

    companion object {
        /**
         * Runtime (dangerous) permissions this app needs.
         *
         * `HIGH_SAMPLING_RATE_SENSORS` is deliberately absent: it is a *normal*
         * permission granted at install time, so requesting it at runtime always
         * fails. Declaring it in the manifest is all that is required.
         *
         * Storage is only requested on API 28 and below. From API 29 the app
         * writes through MediaStore, which needs no permission for its own media.
         */
        val REQUIRED_PERMISSIONS: List<String> = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
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
private fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier = Modifier,
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
            results.values.all { it } -> PermissionStatus.Granted
            // The dialog is still available => the user simply said no this time.
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
            message = stringResource(R.string.permission_camera_rationale),
            actionLabel = null,
            onAction = null,
        )

        PermissionStatus.ShowRationale -> PermissionMessage(
            modifier = modifier,
            message = stringResource(R.string.permission_camera_rationale),
            actionLabel = stringResource(R.string.permission_grant),
            onAction = { launcher.launch(permissions.toTypedArray()) },
        )

        PermissionStatus.PermanentlyDenied -> PermissionMessage(
            modifier = modifier,
            message = stringResource(R.string.permission_camera_denied_permanently),
            actionLabel = stringResource(R.string.permission_open_settings),
            onAction = { context.openAppSettings() },
        )
    }
}

@Composable
private fun PermissionMessage(
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.permission_camera_title),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = onAction,
                ) {
                    Text(actionLabel)
                }
            }
        }
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
    while (current is android.content.ContextWrapper) {
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
