package com.n30dyn4m1c.photosphere.sensor

import androidx.compose.runtime.Composable

/**
 * The Android attitude feed is the existing [OrientationTracker], which already
 * does everything the shared interface asks: lifecycle-bound listening and a
 * [StateFlow] of samples.
 */
@Composable
actual fun rememberOrientationSensor(): OrientationSensor = rememberOrientationTracker()
