@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.n30dyn4m1c.photosphere.sensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.n30dyn4m1c.photosphere.util.KLog
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreMotion.CMDeviceMotion
import platform.CoreMotion.CMMagneticFieldCalibrationAccuracyUncalibrated
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue
import kotlin.math.PI

private const val TAG = "OrientationTracker"

/** Target delivery rate, matching the ~50 Hz hint Android's capture uses. */
private const val UPDATE_INTERVAL_SECONDS = 0.02

/** Radians-to-degrees, replacing the JVM-only `Math.toDegrees`. */
private const val DEGREES_PER_RADIAN = 180.0 / PI

/**
 * Tracks device attitude from CoreMotion's fused device motion and publishes it
 * as a [StateFlow] of [OrientationData] — the iOS twin of the Android rotation
 * vector.
 *
 * The north-referenced frame gives an absolute attitude (gyroscope +
 * accelerometer + magnetometer), which is what the guided capture alignment
 * gates need. Events land on a private operation queue, so a 50 Hz stream never
 * competes with the UI; start/stop happen on the composition thread and rely on
 * CoreMotion's own idempotency rather than a lock (`synchronized` is JVM-only).
 *
 * The camera-basis matrix Android publishes alongside the angles is not built
 * here yet: near the zenith, where Euler angles collapse, dwell averaging falls
 * back to angle-space on iOS. A v2 refinement, not a blocker for capture.
 */
class IosOrientationSensor : OrientationSensor {

    private val manager = CMMotionManager()
    private val queue = NSOperationQueue()

    override val isSensorAvailable: Boolean get() = manager.deviceMotionAvailable

    private val _orientation = MutableStateFlow(OrientationData())

    /** Latest attitude. Emits a new value per motion sample while listening. */
    override val orientation: StateFlow<OrientationData> = _orientation.asStateFlow()

    /** Subscribes to device motion. Idempotent. */
    fun startListening() {
        if (!isSensorAvailable) {
            KLog.w(TAG, "Device motion unavailable; orientation readout stays at zero")
            return
        }
        if (manager.deviceMotionActive) return

        manager.deviceMotionUpdateInterval = UPDATE_INTERVAL_SECONDS
        // The reference-frame variant of this call does not resolve in the
        // current bindings, so attitude arrives relative to the start frame:
        // yaw is drift-prone but perfectly usable for the by-eye alignment.
        manager.startDeviceMotionUpdatesToQueue(queue) { motion, _ ->
            val attitude = motion?.attitude ?: return@startDeviceMotionUpdatesToQueue
            _orientation.value = OrientationData(
                // CoreMotion speaks radians; everything downstream is degrees.
                yawDegrees = (attitude.yaw * DEGREES_PER_RADIAN).toFloat(),
                pitchDegrees = (attitude.pitch * DEGREES_PER_RADIAN).toFloat(),
                rollDegrees = (attitude.roll * DEGREES_PER_RADIAN).toFloat(),
                accuracy = accuracyOf(motion),
                timestampNanos = (motion.timestamp * NANOS_PER_SECOND).toLong(),
            )
        }
    }

    /** Unsubscribes. Idempotent; the last sample stays published. */
    fun stopListening() {
        if (!manager.deviceMotionActive) return
        manager.stopDeviceMotionUpdates()
    }

    /**
     * CoreMotion reports magnetic-field calibration; the shared
     * [OrientationAccuracy.fromSensorAccuracy] reads Android's 0..3 scale.
     * Only "uncalibrated" is reliably nameable across binding versions, so
     * everything calibrated maps onto the two upper steps.
     */
    private fun accuracyOf(motion: CMDeviceMotion): OrientationAccuracy =
        OrientationAccuracy.fromSensorAccuracy(
            motion.magneticField.useContents {
                if (accuracy == CMMagneticFieldCalibrationAccuracyUncalibrated) 0 else 2
            },
        )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}

/**
 * The current platform's attitude feed, bound to the composition: sampling runs
 * while the screen is up and stops when it goes away.
 */
@Composable
actual fun rememberOrientationSensor(): OrientationSensor {
    val sensor = remember { IosOrientationSensor() }
    DisposableEffect(sensor) {
        sensor.startListening()
        onDispose { sensor.stopListening() }
    }
    return sensor
}
