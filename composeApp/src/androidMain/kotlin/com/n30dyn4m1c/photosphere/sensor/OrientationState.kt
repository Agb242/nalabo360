package com.n30dyn4m1c.photosphere.sensor

import android.content.Context
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Creates an [OrientationTracker] bound to the current composition.
 *
 * The tracker starts listening when the host lifecycle reaches STARTED and stops
 * on STOP or disposal, so the sensor is never left running behind a backgrounded
 * screen. [OrientationTracker.displayRotation] is kept in sync with the real
 * display, including across configuration changes.
 */
@Composable
fun rememberOrientationTracker(
    samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    reference: OrientationReference = OrientationReference.Camera,
): OrientationTracker {
    val context = LocalContext.current
    val tracker = remember(context, samplingPeriodUs, reference) {
        OrientationTracker(context, samplingPeriodUs, reference)
    }

    // Configuration is a new instance after a rotation, so this re-reads the
    // display exactly when it can have changed.
    val configuration = LocalConfiguration.current
    val displayRotation = remember(context, configuration) { context.currentDisplayRotation() }
    SideEffect { tracker.displayRotation = displayRotation }

    LifecycleStartEffect(tracker) {
        tracker.startListening()
        onStopOrDispose { tracker.stopListening() }
    }

    return tracker
}

/**
 * Live device attitude as Compose [State], lifecycle-aware end to end.
 *
 * ```
 * val orientation by rememberOrientationData()
 * Text("yaw ${orientation.yawDegrees}")
 * ```
 *
 * Use [rememberOrientationTracker] directly when the caller also needs
 * [OrientationTracker.isSensorAvailable] or manual start/stop control.
 */
@Composable
fun rememberOrientationData(
    samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    reference: OrientationReference = OrientationReference.Camera,
): State<OrientationData> =
    rememberOrientationTracker(samplingPeriodUs, reference)
        .orientation
        .collectAsStateWithLifecycle()

/**
 * The rotation the display is currently drawn at, as a `Surface.ROTATION_*`.
 *
 * Falls back to [Surface.ROTATION_0] when the context has no display to speak of
 * — an application context on API 30+, or the Compose preview renderer.
 */
internal fun Context.currentDisplayRotation(): Int = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        display?.rotation
    } else {
        @Suppress("DEPRECATION")
        getSystemService(WindowManager::class.java)?.defaultDisplay?.rotation
    }
}.getOrNull() ?: Surface.ROTATION_0
