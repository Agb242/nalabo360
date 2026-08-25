package com.n30dyn4m1c.photosphere.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.camera.SphereCaptureScreen
import com.n30dyn4m1c.photosphere.isDebugBuild
import com.n30dyn4m1c.photosphere.result.PanoramaResultScreen
import com.n30dyn4m1c.photosphere.sensor.OrientationDebugScreen
import com.n30dyn4m1c.photosphere.storage.SphereCache
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import com.n30dyn4m1c.photosphere.ui.theme.PillShape

/**
 * Gates [content] behind the platform's runtime permissions.
 *
 * Android asks for CAMERA (and legacy storage below API 29) with the full
 * rationale/retry/Settings dance; hosts without a permission model simply show
 * the content.
 */
@Composable
expect fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
)

/**
 * The whole app: capture, then what to do with what came out.
 *
 * There are only two destinations, so this is a `when` over one piece of state
 * rather than a navigation graph. The sphere held here is the app's single
 * source of truth for "there is a finished photo waiting" — non-null puts the
 * result screen up, and clearing it deletes the cached JPEG and returns to
 * capture with a fresh buffer.
 *
 * The camera screen sits behind the permission gate; the result screen is not
 * separately gated, since the only way to reach it is through a capture that
 * already passed.
 */
@Composable
fun PhotoSphereApp(modifier: Modifier = Modifier) {
    // The orientation readout needs no permissions, so it sits beside the
    // permission gate rather than behind it.
    var showOrientationDebug by rememberSaveable { mutableStateOf(false) }

    // Deliberately not `rememberSaveable`: after a process death or window
    // recreate the file may still be in the cache, but re-showing a stale result
    // would be worse than starting over.
    var sphere by remember { mutableStateOf<StitchedSphere?>(null) }

    /** Throws away the finished sphere and goes back to capture. */
    fun discardSphere() {
        val finished = sphere ?: return
        sphere = null
        SphereCache.deleteCachedSphere(finished.file)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            showOrientationDebug -> OrientationDebugScreen()

            else -> RequirePermissions(
                permissions = requiredRuntimePermissions(),
            ) {
                val finished = sphere
                if (finished == null) {
                    SphereCaptureScreen(onSphereReady = { sphere = it })
                } else {
                    PanoramaResultScreen(
                        sphere = finished,
                        onTakeAnother = { discardSphere() },
                    )
                }
            }
        }

        // Debug-only entry point. [isDebugBuild] is a release-time constant on
        // every target, so dead-code elimination drops this from release builds.
        if (isDebugBuild) {
            FilledTonalButton(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .systemBarsPadding()
                    .padding(12.dp),
                onClick = { showOrientationDebug = !showOrientationDebug },
            ) {
                Text(
                    if (showOrientationDebug) {
                        Strings.ORIENTATION_DEBUG_CLOSE
                    } else {
                        Strings.ORIENTATION_DEBUG_OPEN
                    }
                )
            }
        }
    }
}

/** The runtime permissions this host wants before showing a camera. */
internal expect fun requiredRuntimePermissions(): List<String>

/**
 * The permission gate's own screen.
 *
 * This is the app's front door — for a first-time user it is the whole app
 * until they say yes — so it is composed rather than dumped: the lens glyph
 * gives the request a subject, the title carries the ask, and the body explains
 * why a camera app that never uploads anything still needs the camera. The
 * action, when there is one, is full-width at the bottom where a thumb is.
 */
@Composable
internal fun PermissionMessage(
    message: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // A ring around the lens glyph, echoing the capture reticle the user
            // is about to spend the next few minutes aiming.
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(38.dp),
                )
            }

            Text(
                modifier = Modifier.padding(top = 28.dp),
                text = Strings.PERMISSION_CAMERA_TITLE,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier.padding(top = 12.dp),
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(
                    modifier = Modifier
                        .padding(top = 32.dp)
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = PillShape,
                    onClick = onAction,
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}
