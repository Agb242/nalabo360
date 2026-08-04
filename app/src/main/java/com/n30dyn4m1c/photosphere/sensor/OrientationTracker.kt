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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "OrientationTracker"

/** `getRotationMatrixFromVector` works with a 3x3 row-major matrix. */
private const val MATRIX_SIZE = 9

/** The rotation vector is a quaternion: `[x, y, z, w]`. */
private const val ROTATION_VECTOR_SIZE = 4

private const val SENSOR_THREAD_NAME = "orientation-sensor"

/**
 * A single attitude sample, in degrees.
 *
 * The signs follow [SensorManager.getOrientation], interpreted through the
 * tracker's [OrientationReference]. With the default
 * [OrientationReference.Camera] frame, and the phone held upright with the rear
 * camera aimed at the horizon:
 *
 * - [yawDegrees] is the compass bearing the **camera** points at: 0° = magnetic
 *   north, +90° = east, ±180° = south, -90° = west.
 * - [pitchDegrees] is 0° at the horizon and goes **negative as the camera aims
 *   upward** (-90° = straight up, +90° = straight down at your feet).
 * - [rollDegrees] is 0° when the display's up axis points at the sky, and turns
 *   positive as the device is rolled clockwise from the photographer's view.
 *
 * See [OrientationReference.Screen] for the plain Android interpretation.
 */
data class OrientationData(
    /** Azimuth, -180°..180°. */
    val yawDegrees: Float = 0f,
    /** Vertical tilt, -90°..90° (an `asin`, so it never wraps). */
    val pitchDegrees: Float = 0f,
    /** Side tilt, -180°..180°. */
    val rollDegrees: Float = 0f,
    /** How much the fused sensor currently trusts itself. */
    val accuracy: OrientationAccuracy = OrientationAccuracy.Unknown,
    /** `SensorEvent.timestamp` of the sample, in nanoseconds of uptime. */
    val timestampNanos: Long = 0L,
) {
    /** False for the initial placeholder, before the first sensor event lands. */
    val hasFix: Boolean get() = timestampNanos > 0L
}

/** [SensorEvent.accuracy] as something readable. */
enum class OrientationAccuracy {
    /** No sample yet, or the sensor never reported its accuracy. */
    Unknown,

    /** Readings cannot be trusted at all — usually a magnetometer that needs a figure-eight. */
    Unreliable,
    Low,
    Medium,
    High,
    ;

    /** Medium is the lowest accuracy worth feeding into capture guidance. */
    val isUsable: Boolean get() = this == Medium || this == High

    internal companion object {
        fun fromSensorAccuracy(accuracy: Int): OrientationAccuracy = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> High
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> Medium
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> Low
            SensorManager.SENSOR_STATUS_UNRELIABLE -> Unreliable
            else -> Unknown
        }
    }
}

/**
 * Which physical axis the reported angles describe.
 *
 * Both frames are corrected for display rotation; they differ in what counts as
 * "level".
 */
enum class OrientationReference {
    /**
     * Android's own convention: the angles describe the **screen** lying flat,
     * face up, with its top edge pointing north.
     *
     * Convenient for a levelling UI, but degenerate for sphere capture: a phone
     * held upright sits at pitch ≈ -90°, which is exactly the gimbal-lock pose
     * where yaw and roll collapse into each other and jitter wildly.
     */
    Screen,

    /**
     * The angles describe where the **rear camera** points: pitch ≈ 0° when the
     * phone is held upright and aimed at the horizon.
     *
     * This is the useful frame for a photo sphere — yaw is the bearing of the
     * frame being captured, pitch is how far above or below the horizon it sits
     * — and it keeps the singularity at straight-up/straight-down instead of at
     * the app's normal shooting pose.
     */
    Camera,
}

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
) : SensorEventListener {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)

    private val rotationVectorSensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * False on devices without a fused rotation vector (no gyroscope, or a
     * stripped sensor HAL). Callers should degrade gracefully rather than
     * assume orientation is available.
     */
    val isSensorAvailable: Boolean get() = rotationVectorSensor != null

    private val _orientation = MutableStateFlow(OrientationData())

    /** Latest attitude. Emits a new value per sensor event while listening. */
    val orientation: StateFlow<OrientationData> = _orientation.asStateFlow()

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

        // Some vendors append a fifth element (estimated heading accuracy); only
        // the leading quaternion is the rotation itself.
        val values = if (event.values.size > ROTATION_VECTOR_SIZE) {
            event.values.copyInto(
                destination = rotationVectorValues,
                endIndex = ROTATION_VECTOR_SIZE,
            )
        } else {
            event.values
        }

        // Quaternion -> rotation matrix mapping device coordinates to the world
        // frame (X east, Y north, Z up).
        SensorManager.getRotationMatrixFromVector(rotationMatrix, values)

        val angles = when (reference) {
            OrientationReference.Screen ->
                screenAnglesDegrees(rotationMatrix) ?: return
            OrientationReference.Camera ->
                cameraAnglesDegrees(rotationMatrix, displayRotation, anglesRadians)
        }

        // getOrientation's screen frame reports azimuth/pitch/roll about the
        // -Z/X/Y axes; the camera frame is read straight off the matrix instead.
        // Yaw and roll come from atan2 and are normalised defensively; pitch
        // comes from an asin and is already bounded to ±90°.
        _orientation.value = OrientationData(
            yawDegrees = angles[0],
            pitchDegrees = angles[1],
            rollDegrees = angles[2],
            accuracy = accuracy,
            timestampNanos = event.timestamp,
        )
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

/**
 * Axis pairs handed to [SensorManager.remapCoordinateSystem], one per display
 * rotation.
 *
 * Each entry answers "where do the chassis X and Y axes point once the display
 * has been rotated by this much?" — e.g. at [Surface.ROTATION_90] the display
 * has turned 90° counter-clockwise, so the chassis X axis now runs along the
 * display's Y axis.
 */
internal enum class DisplayAxes(val axisX: Int, val axisY: Int) {
    Rotation0(SensorManager.AXIS_X, SensorManager.AXIS_Y),
    Rotation90(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X),
    Rotation180(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y),
    Rotation270(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X),
    ;

    internal companion object {
        fun forDisplayRotation(displayRotation: Int): DisplayAxes = when (displayRotation) {
            Surface.ROTATION_90 -> Rotation90
            Surface.ROTATION_180 -> Rotation180
            Surface.ROTATION_270 -> Rotation270
            else -> Rotation0
        }
    }
}

private fun Float.toDegrees(): Float = Math.toDegrees(toDouble()).toFloat()

/** Wraps [degrees] into `[-180, 180)`, so yaw crossing north stays continuous. */
internal fun normalizeDegrees(degrees: Float): Float {
    var value = degrees % 360f
    if (value >= 180f) value -= 360f
    if (value < -180f) value += 360f
    return value
}

/**
 * The rear camera's yaw/pitch/roll in this app's conventions, read straight off
 * the device→world rotation matrix [deviceToWorld].
 *
 * This is the *inverse* of the axis construction `SphereProjection` and
 * `CameraBasis.of` use: those build a camera basis from a yaw/pitch/roll, and
 * this recovers the angles from the sensor's matrix so that both halves of the
 * pipeline describe the same frame. It has to be an exact inverse, or every
 * frame lands on the sphere rotated — which is exactly the kind of "aligned but
 * all at odd angles" failure that no amount of stitching can rescue.
 *
 * The angles are read from the geometry directly rather than through
 * `getOrientation`'s azimuth/pitch/roll (which describe a flat screen, not a
 * camera, and are what a `remapCoordinateSystem` shortcut gets wrong):
 *
 * - **forward** is where the lens points: the device's -Z axis in world. Yaw is
 *   its compass bearing, elevation its height above the horizon (pitch is
 *   negated to match the app's "negative is up" convention).
 * - **up** is the top of the captured, display-upright image — the device axis
 *   that the current display rotation points at the sky. Roll is the angle
 *   between that up and the unrolled-up for the same yaw/elevation.
 *
 * [displayRotation] decides which device axis is the image's up/right, so the
 * angles describe the camera rather than the chassis and a phone turned
 * landscape keeps reporting where it is pointing.
 *
 * The result is written into [out] (a scratch buffer) to keep a 50 Hz stream
 * allocation-free.
 */
internal fun cameraAnglesDegrees(
    deviceToWorld: FloatArray,
    displayRotation: Int,
    out: FloatArray,
): FloatArray {
    // The matrix is row-major with the device axes as its columns: column c is
    // the world coordinates of device axis c, so the camera looks along -Z.
    val fx = -deviceToWorld[2].toDouble()
    val fy = -deviceToWorld[5].toDouble()
    val fz = -deviceToWorld[8].toDouble()

    // Image right/up in world, mapped from whichever device axes the display
    // rotation points to the right and the top of the screen.
    val rx: Double
    val ry: Double
    val rz: Double
    val ux: Double
    val uy: Double
    val uz: Double
    when (displayRotation) {
        Surface.ROTATION_90 -> {
            rx = deviceToWorld[1].toDouble(); ry = deviceToWorld[4].toDouble(); rz = deviceToWorld[7].toDouble()
            ux = -deviceToWorld[0].toDouble(); uy = -deviceToWorld[3].toDouble(); uz = -deviceToWorld[6].toDouble()
        }
        Surface.ROTATION_180 -> {
            rx = -deviceToWorld[0].toDouble(); ry = -deviceToWorld[3].toDouble(); rz = -deviceToWorld[6].toDouble()
            ux = -deviceToWorld[1].toDouble(); uy = -deviceToWorld[4].toDouble(); uz = -deviceToWorld[7].toDouble()
        }
        Surface.ROTATION_270 -> {
            rx = -deviceToWorld[1].toDouble(); ry = -deviceToWorld[4].toDouble(); rz = -deviceToWorld[7].toDouble()
            ux = deviceToWorld[0].toDouble(); uy = deviceToWorld[3].toDouble(); uz = deviceToWorld[6].toDouble()
        }
        else -> {
            rx = deviceToWorld[0].toDouble(); ry = deviceToWorld[3].toDouble(); rz = deviceToWorld[6].toDouble()
            ux = deviceToWorld[1].toDouble(); uy = deviceToWorld[4].toDouble(); uz = deviceToWorld[7].toDouble()
        }
    }

    val yaw = Math.toDegrees(atan2(fx, fy)).toFloat()
    val elevation = asin(fz.coerceIn(-1.0, 1.0))
    val pitch = -Math.toDegrees(elevation).toFloat()

    // The unrolled pair `CameraBasis.of` would build for this yaw/elevation,
    // measured against the actual up to recover the roll (see its `toPose`).
    val yawRad = Math.toRadians(yaw.toDouble())
    val sinYaw = sin(yawRad)
    val cosYaw = cos(yawRad)
    val sinElevation = sin(elevation)
    val cosElevation = cos(elevation)
    val up0X = -sinYaw * sinElevation
    val up0Y = -cosYaw * sinElevation
    val up0Z = cosElevation
    val right0X = cosYaw
    val right0Y = -sinYaw
    val sinRoll = -(rx * up0X + ry * up0Y + rz * up0Z)
    val cosRoll = ux * up0X + uy * up0Y + uz * up0Z
    val roll = Math.toDegrees(atan2(sinRoll, cosRoll)).toFloat()

    out[0] = normalizeDegrees(yaw)
    out[1] = pitch
    out[2] = normalizeDegrees(roll)
    return out
}
