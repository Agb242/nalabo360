package com.n30dyn4m1c.photosphere.sensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Desktops in this project run tests, not capture, so the attitude feed reports
 * itself unavailable and never moves; the debug screen renders its
 * "sensor unavailable" branch instead of a frozen dial.
 */
private object DesktopOrientationSensor : OrientationSensor {
    override val orientation: StateFlow<OrientationData> = MutableStateFlow(OrientationData())
    override val isSensorAvailable: Boolean = false
}

@Composable
actual fun rememberOrientationSensor(): OrientationSensor =
    remember { DesktopOrientationSensor }
