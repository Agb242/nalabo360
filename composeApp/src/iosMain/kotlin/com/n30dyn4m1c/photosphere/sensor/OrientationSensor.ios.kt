package com.n30dyn4m1c.photosphere.sensor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.n30dyn4m1c.photosphere.util.KLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.CoreMotion.CMAttitudeReferenceFrame.xMagneticNorthZVertical
import platform.CoreMotion.CMMagneticFieldAccuracy
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSOperationQueue

private const val TAG = "OrientationTracker"

/** Target delivery rate, matching the ~50 Hz hint Android's capture uses. */
private const val UPDATE_INTERVAL_SECONDS = 0.02

/**
 * Tracks device attitude from CoreMotion's fused device motion and publishes it
 * as a [StateFlow] of [OrientationData] — the iOS twin of the Android rotation
 * vector.
 *
 * The `xMagneticNorthZVertical` reference frame gives an absolute,
 * north-referenced attitude (gyroscope + accelerometer + magnetometer), which is
 * what the guided capture alignment gates need. Events land on a private
 * operation queue, so a 50 Hz stream never competes with the UI.
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
        synchronized(this) {
            if (manager.deviceMotionActive) return

            manager.deviceMotionUpdateInterval = UPDATE_INTERVAL_SECONDS
            manager.startDeviceMotionUpdatesUsingReferenceFrameToQueueWithHandler(
                /* referenceFrame = */ xMagneticNorthZVertical,
                /* toQueue = */ queue,
                /* withHandler = */) { motion, _ ->
                val attitude = motion?.attitude ?: return@startDeviceMotionUpdatesUsingReferenceFrameToQueueWithHandler
                _orientation.value = OrientationData(
                    // CoreMotion speaks radians; everything downstream is degrees.
                    yawDegrees = Math.toDegrees(attitude.yaw).toFloat(),
                    pitchDegrees = Math.toDegrees(attitude.pitch).toFloat(),
                    rollDegrees = Math.toDegrees(attitude.roll).toFloat(),
                    accuracy = accuracyOf(motion),
                    timestampNanos = (motion.timestamp * NANOS_PER_SECOND).toLong(),
                )
            }
        }
    }

    /** Unsubscribes. Idempotent; the last sample stays published. */
    fun stopListening() {
        synchronized(this) {
            if (!manager.deviceMotionActive) return
            manager.stopDeviceMotionUpdates()
        }
    }

    /**
     * CoreMotion reports magnetic-field accuracy as −1..2 (uncalibrated..high);
     * the shared [OrientationAccuracy.fromSensorAccuracy] reads Android's
     * 0..3 scale — uncalibrated shifts down one step onto "unreliable".
     */
    private fun accuracyOf(motion: platform.CoreMotion.CMDeviceMotion): OrientationAccuracy =
        OrientationAccuracy.fromSensorAccuracy(
            magneticAccuracyToSensorAccuracy(motion.magneticField.accuracy),
        )

    private fun magneticAccuracyToSensorAccuracy(accuracy: CMMagneticFieldAccuracy): Int =
        accuracy.value.toInt() + 1

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
