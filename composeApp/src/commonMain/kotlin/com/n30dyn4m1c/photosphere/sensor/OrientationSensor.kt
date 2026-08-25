package com.n30dyn4m1c.photosphere.sensor

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/**
 * The live attitude feed a platform's motion hardware provides.
 *
 * The Android implementation wraps the `SensorManager` listener; iOS backs it
 * with CoreMotion; hosts without a rotation sensor report it through
 * [isSensorAvailable] so the UI can say so instead of showing a frozen dial.
 */
interface OrientationSensor {
    /** Latest attitude, updated as samples arrive. */
    val orientation: StateFlow<OrientationData>

    /** False when the host has no usable rotation source at all. */
    val isSensorAvailable: Boolean
}

/**
 * The current platform's attitude feed, bound to the composition.
 *
 * Implementations start sampling when the host lifecycle reaches STARTED and
 * stop on STOP or disposal, so the sensor is never left running behind a
 * backgrounded screen.
 */
@Composable
expect fun rememberOrientationSensor(): OrientationSensor
