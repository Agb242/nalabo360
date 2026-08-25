package com.n30dyn4m1c.photosphere.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "OrientationTracker"

/** `getRotationMatrixFromVector` works with a 3x3 row-major matrix. */
private const val MATRIX_SIZE = 9

/** The rotation vector is a quaternion: `[x, y, z, w]`. */
private const val ROTATION_VECTOR_SIZE = 4

private const val SENSOR_THREAD_NAME = "orientation-sensor"

/**
 * Tracks device attitude from [Sensor.TYPE_ROTATION_VECTOR] and publishes it as
 * a [StateFlow] of [OrientationData].
 *
 * The rotation vector is a *fused* sensor (gyroscope + accelerometer +
 * magnetometer), so it gives an absolute, north-referenced attitude with no
 * drift and no manual filtering — the right input for stitching frames into a
 * sphere.
 *
 * Events are delivered on a private background thread, so a high sampling rate
 * never competes with the UI. [orientation] is a `StateFlow`, which is safe to
 * collect from any thread, and always holds the latest sample: a subscriber
 * arriving mid-capture immediately sees the current attitude rather than
 * waiting for the next event.
 *
 * The tracker holds an OS listener registration, so it must be driven by the
 * screen's lifecycle: call [startListening] when the UI becomes visible and
 * [stopListening] when it does not. Leaving the rotation vector registered in
 * the background keeps the gyro powered and drains the battery for nothing.
 * From Compose, prefer `rememberOrientationTracker()`, which wires both calls to
 * the composition's lifecycle.
 *
 * Instances are safe to start and stop from any thread, and can be restarted
 * after a stop.
 *
 * @param context any context; only the application context is retained.
 * @param samplingPeriodUs delivery rate hint, as accepted by
 *   [SensorManager.registerListener]. [SensorManager.SENSOR_DELAY_GAME] (~50 Hz)
 *   is smooth enough for a capture reticle without the cost of
 *   [SensorManager.SENSOR_DELAY_FASTEST].
 * @param reference which physical axis the reported angles describe.
 */
class OrientationTracker(
    context: Context,
    private val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    private val reference: OrientationReference = OrientationReference.Camera,
) : SensorEventListener, OrientationSensor {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)

    private val rotationVectorSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * False on devices without a fused rotation vector (no gyroscope, or a
     * stripped sensor HAL). Callers should degrade gracefully rather than
     * assume orientation is available.
     */
    override val isSensorAvailable: Boolean get() = rotationVectorSensor != null

    private val _orientation = MutableStateFlow(OrientationData())

    /** Latest attitude. Emits a new value per sensor event while listening. */
    override val orientation: StateFlow<OrientationData> = _orientation.asStateFlow()

    /**
     * Current display rotation, one of the `Surface.ROTATION_*` constants.
     *
     * The sensor frame is fixed to the *chassis*, so the angles have to be
     * re-expressed in the frame the user actually sees. Keep this in sync with
     * `Display.getRotation()`; `rememberOrientationTracker()` does it for you.
     */
    @Volatile
    var displayRotation: Int = Surface.ROTATION_0

    /**
     * Scratch buffers. Only ever touched on the sensor thread, which lets a
     * ~50 Hz stream run without allocating per event.
     */
    private val rotationVectorValues = FloatArray(ROTATION_VECTOR_SIZE)
    private val rotationMatrix = FloatArray(MATRIX_SIZE)
    private val displayMatrix = FloatArray(MATRIX_SIZE)
    private val anglesRadians = FloatArray(3)
    private val basisScratch = FloatArray(MATRIX_SIZE)

    @Volatile
    private var accuracy = OrientationAccuracy.Unknown

    private val lock = Any()

    /** Both guarded by [lock]. */
    private var isListening = false
    private var sensorThread: HandlerThread? = null

    /**
     * Subscribes to the rotation vector. Idempotent.
     *
     * @return true if the tracker is listening when this returns; false if the
     *   device has no rotation vector sensor or the framework refused the
     *   registration.
     */
    fun startListening(): Boolean {
        val manager = sensorManager ?: return false
        val sensor = rotationVectorSensor ?: return false

        synchronized(lock) {
            if (isListening) return true

            // A dedicated thread keeps sensor delivery — and the matrix maths —
            // off the main thread, so the sampling rate cannot stutter the UI.
            val thread = HandlerThread(SENSOR_THREAD_NAME).apply { start() }
            val registered = manager.registerListener(
                this,
                sensor,
                samplingPeriodUs,
                Handler(thread.looper),
            )

            if (registered) {
                sensorThread = thread
                isListening = true
            } else {
                Log.w(TAG, "Rotation vector registration refused")
                thread.quitSafely()
            }
            return registered
        }
    }

    /**
     * Unsubscribes and releases the sensor thread. Idempotent.
     *
     * The last [OrientationData] stays published so the UI keeps rendering the
     * final pose instead of snapping back to zero.
     */
    fun stopListening() {
        synchronized(lock) {
            if (!isListening) return

            sensorManager?.unregisterListener(this)
            isListening = false
            // quitSafely, not quit: lets an event already queued finish its
            // matrix conversion instead of tearing the looper out from under it.
            sensorThread?.quitSafely()
            sensorThread = null
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val raw = event.values
        // A rotation vector is three elements plus an optional scalar; anything
        // shorter is not one, and a HAL that hands over a truncated event would
        // otherwise take the matrix helper into an array bound.
        if (raw.size < 3) return

        // Some vendors append a fifth element (estimated heading accuracy); only
        // the leading quaternion is the rotation itself.
        val values = if (raw.size > ROTATION_VECTOR_SIZE) {
            raw.copyInto(
                destination = rotationVectorValues,
                endIndex = ROTATION_VECTOR_SIZE,
            )
        } else {
            raw
        }

        // Quaternion -> rotation matrix mapping device coordinates to the world
        // frame (X east, Y north, Z up). A malformed sample is worth skipping,
        // not crashing the sensor thread over.
        try {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, values)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Rejected a malformed rotation vector", e)
            return
        }

        // The camera's axes in the world frame, published alongside the angles:
        // the stitcher consumes the pose as a rotation, and the dwell averaging
        // in OrientationMean needs the matrix because the angles alone collapse
        // at the zenith.
        val basis = cameraBasisMatrix(rotationMatrix, displayRotation, basisScratch)

        val angles = when (reference) {
            OrientationReference.Screen ->
                screenAnglesDegrees(rotationMatrix) ?: return
            OrientationReference.Camera ->
                anglesFromCameraBasis(basis, anglesRadians)
        }

        // The screen frame reports azimuth/pitch/roll about the -Z/X/Y axes;
        // the camera frame is read straight off the matrix instead. Yaw and roll
        // come from atan2 and are normalised defensively; pitch comes from an
        // asin and is already bounded to ±90°.
        _orientation.value = OrientationData(
            yawDegrees = angles[0],
            pitchDegrees = angles[1],
            rollDegrees = angles[2],
            accuracy = accuracyOf(event),
            timestampNanos = event.timestamp,
            // A copy: the scratch buffer is reused by the next event.
            cameraBasis = basis.copyOf(),
        )
    }

    /**
     * The accuracy to publish with a sample.
     *
     * [SensorEvent.accuracy] carries the current status on every event, while
     * [onAccuracyChanged] only fires on a *change* — and a good many HALs never
     * fire it at all, including the first time. Reading the event is what stops
     * a device that simply never calls back from sitting at
     * [OrientationAccuracy.Unknown] forever, which the capture loop would see as
     * a sensor that never became trustworthy.
     *
     * The callback still wins when it has spoken, because a vendor that bothers
     * to send it is the better authority on its own fusion. And the event can
     * only ever *raise* the reading: a HAL that never calls back and leaves the
     * field at its zero default would otherwise look identical to one declaring
     * its own output unreliable, and the capture loop would refuse to shoot on a
     * phone whose sensor is working perfectly well.
     */
    private fun accuracyOf(event: SensorEvent): OrientationAccuracy {
        val reported = accuracy
        if (reported != OrientationAccuracy.Unknown) return reported
        val fromEvent = OrientationAccuracy.fromSensorAccuracy(event.accuracy)
        return if (fromEvent == OrientationAccuracy.Unreliable) reported else fromEvent
    }

    /**
     * Rotates [source] out of the chassis frame and into the frame the angles
     * are reported in, writing into one of the scratch buffers.
     *
     * Only the [OrientationReference.Screen] path uses this: the display remap
     * plus a plain `getOrientation` *is* Android's own convention. The camera
     * reference is read straight off the device→world matrix instead — see
     * [cameraAnglesDegrees], which is the inverse of the axis construction the
     * capture and stitching code build their bases from.
     *
     * The display remap rotates the chassis frame so the angles describe the
     * display the user is actually looking at: portrait and landscape put the
     * chassis X/Y axes in different places on screen, and without it, tilting a
     * landscape phone "up" would show up as roll.
     *
     * @return the matrix to read angles from, or null if a remap was rejected —
     *   which should not happen for these fixed axis pairs, but a bad matrix is
     *   worse than a skipped frame.
     */
    private fun screenAnglesDegrees(source: FloatArray): FloatArray? {
        val axes = DisplayAxes.forDisplayRotation(displayRotation)
        if (!SensorManager.remapCoordinateSystem(source, axes.axisX, axes.axisY, displayMatrix)) {
            Log.w(TAG, "Display remap rejected for rotation $displayRotation")
            return null
        }
        SensorManager.getOrientation(displayMatrix, anglesRadians)
        anglesRadians[0] = normalizeDegrees(anglesRadians[0].toDegrees())
        anglesRadians[1] = anglesRadians[1].toDegrees()
        anglesRadians[2] = normalizeDegrees(anglesRadians[2].toDegrees())
        return anglesRadians
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type != Sensor.TYPE_ROTATION_VECTOR) return
        val mapped = OrientationAccuracy.fromSensorAccuracy(accuracy)
        this.accuracy = mapped
        // Surface a calibration warning immediately rather than at the next event.
        _orientation.update { it.copy(accuracy = mapped) }
    }
}

private fun Float.toDegrees(): Float = Math.toDegrees(toDouble()).toFloat()
