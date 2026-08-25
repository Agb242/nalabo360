package com.n30dyn4m1c.photosphere.sensor

import com.n30dyn4m1c.photosphere.toDegreesSafe
import com.n30dyn4m1c.photosphere.toRadiansSafe
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The attitude model every platform shares: what a sample looks like, how much
 * the sensor trusts itself, and the matrix maths that turns a device-to-world
 * rotation into the camera pose the stitcher consumes.
 *
 * The numeric constants below mirror the platform ones they replace
 * (`SensorManager.SENSOR_STATUS_*`, `AXIS_*`, and the display-rotation ordinals)
 * so the decisions stay byte-identical across targets without dragging a
 * framework onto the shared source set.
 */

/** `SENSOR_STATUS_ACCURACY_*`: unreliable, low, medium, high, in that order. */
private const val SENSOR_STATUS_ACCURACY_HIGH = 3
private const val SENSOR_STATUS_ACCURACY_MEDIUM = 2
private const val SENSOR_STATUS_ACCURACY_LOW = 1
private const val SENSOR_STATUS_UNRELIABLE = 0

/**
 * A single attitude sample, in degrees.
 *
 * The signs follow the platform's orientation convention, interpreted through
 * the tracker's [OrientationReference]. With the default
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
 * See [OrientationReference.Screen] for the plain platform interpretation.
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
    /** Timestamp of the sample, in nanoseconds of uptime. */
    val timestampNanos: Long = 0L,
    /**
     * The camera's basis as a rotation matrix — laid out exactly as
     * `CameraBasis.toRotationMatrix` (stitching) arranges them (the
     * `[right, −up, forward]` columns in the world frame), so it can be handed
     * to `CameraBasis.fromRotationMatrix` without a transpose.
     *
     * Carried alongside the angles because a pose is averaged and
     * reconstructed as a *rotation*: the Euler components collapse into each
     * other at the zenith, where yaw alone cannot say which way is up in the
     * frame. Null for a sample that never saw the sensor.
     */
    val cameraBasis: FloatArray? = null,
) {
    /** False for the initial placeholder, before the first sensor event lands. */
    val hasFix: Boolean get() = timestampNanos > 0L
}

/** The fused sensor's own confidence in its latest sample. */
enum class OrientationAccuracy {
    /** No sample yet, or the sensor never reported its accuracy. */
    Unknown,

    /** Readings cannot be trusted at all — usually a magnetometer that needs a figure-eight. */
    Unreliable,
    Low,
    Medium,
    High,
    ;

    /**
     * Medium is the lowest accuracy whose *absolute* bearing is worth trusting
     * — the reading that would make a sphere's compass heading meaningful.
     */
    val isUsable: Boolean get() = this == Medium || this == High

    /**
     * Whether guided capture should fire the shutter at this accuracy.
     *
     * Deliberately weaker than [isUsable]. What the capture loop needs is that
     * the aim be *consistent between frames*, and that comes from the gyroscope
     * half of the fused sensor: the plan is anchored on whatever bearing the
     * user was facing when the first fix landed, so a magnetometer that is
     * merely uncalibrated shifts every target together and costs the sphere
     * nothing. Only [Unreliable] — the state where the fusion says its own
     * output is not to be believed — is worth refusing to shoot at.
     *
     * Blocking on [isUsable] instead is a dead end the user cannot see their
     * way out of: indoors, near steel, a phone can sit at [Low] indefinitely
     * while the reticle tracks the scene perfectly and the shutter never fires.
     */
    val allowsCapture: Boolean get() = this != Unreliable

    internal companion object {
        fun fromSensorAccuracy(accuracy: Int): OrientationAccuracy = when (accuracy) {
            SENSOR_STATUS_ACCURACY_HIGH -> High
            SENSOR_STATUS_ACCURACY_MEDIUM -> Medium
            SENSOR_STATUS_ACCURACY_LOW -> Low
            SENSOR_STATUS_UNRELIABLE -> Unreliable
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
     * The platform's own convention: the angles describe the **screen** lying
     * flat, face up, with its top edge pointing north.
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

/** `AXIS_*` identifiers as remapCoordinateSystem spells them. */
private const val AXIS_X = 1
private const val AXIS_Y = 2
private const val AXIS_MINUS_X = -1
private const val AXIS_MINUS_Y = -2

/** Display-rotation ordinals: `Surface.ROTATION_{0,90,180,270}` are 0..3. */
private const val DISPLAY_ROTATION_90 = 1
private const val DISPLAY_ROTATION_180 = 2
private const val DISPLAY_ROTATION_270 = 3

/**
 * Axis pairs handed to the coordinate remap, one per display rotation.
 *
 * Each entry answers "where do the chassis X and Y axes point once the display
 * has been rotated by this much?" — e.g. at rotation 90 the display has turned
 * 90° counter-clockwise, so the chassis X axis now runs along the display's Y
 * axis.
 */
internal enum class DisplayAxes(val axisX: Int, val axisY: Int) {
    Rotation0(AXIS_X, AXIS_Y),
    Rotation90(AXIS_Y, AXIS_MINUS_X),
    Rotation180(AXIS_MINUS_X, AXIS_MINUS_Y),
    Rotation270(AXIS_MINUS_Y, AXIS_X),
    ;

    internal companion object {
        fun forDisplayRotation(displayRotation: Int): DisplayAxes = when (displayRotation) {
            DISPLAY_ROTATION_90 -> Rotation90
            DISPLAY_ROTATION_180 -> Rotation180
            DISPLAY_ROTATION_270 -> Rotation270
            else -> Rotation0
        }
    }
}

/** Wraps [degrees] into `[-180, 180)`, so yaw crossing north stays continuous. */
internal fun normalizeDegrees(degrees: Float): Float {
    var value = degrees % 360f
    if (value >= 180f) value -= 360f
    if (value < -180f) value += 360f
    return value
}

/**
 * The rear camera's basis as a rotation matrix, read straight off the
 * device→world matrix [deviceToWorld].
 *
 * The columns are the camera's axes expressed in the world frame —
 * `[right, −up, forward]` — laid out exactly as `CameraBasis.toRotationMatrix`
 * (stitching) arranges them, so a basis written here can be handed to
 * `CameraBasis.fromRotationMatrix` without a transpose. The camera's own
 * (right, up, forward) triple is left-handed, so the up column is mirrored to
 * make the matrix a proper rotation: the dwell is averaged as rotations and
 * the stitching pipeline assumes proper ones, which an unmirrored column
 * would silently corrupt (a 90° error through the quaternion conversion).
 * The matrix is row-major with the device axes as its columns: column c is
 * the world coordinates of device axis c, so the camera looks along -Z.
 *
 * [displayRotation] decides which device axis is the image's right/up, exactly
 * as in [cameraAnglesDegrees] — a phone turned landscape keeps reporting where
 * it is pointing. [anglesFromCameraBasis] is the inverse of the axis
 * construction `SphereProjection` and `CameraBasis.of` use, so both halves of
 * the pipeline describe the same frame.
 *
 * The result is written into [out] (a scratch buffer) to keep a 50 Hz stream
 * allocation-free.
 */
internal fun cameraBasisMatrix(
    deviceToWorld: FloatArray,
    displayRotation: Int,
    out: FloatArray,
): FloatArray {
    val fx = -deviceToWorld[2]
    val fy = -deviceToWorld[5]
    val fz = -deviceToWorld[8]

    // Image right/up in world, mapped from whichever device axes the display
    // rotation points to the right and the top of the screen.
    val rx: Float
    val ry: Float
    val rz: Float
    val ux: Float
    val uy: Float
    val uz: Float
    when (displayRotation) {
        DISPLAY_ROTATION_90 -> {
            rx = deviceToWorld[1]; ry = deviceToWorld[4]; rz = deviceToWorld[7]
            ux = -deviceToWorld[0]; uy = -deviceToWorld[3]; uz = -deviceToWorld[6]
        }
        DISPLAY_ROTATION_180 -> {
            rx = -deviceToWorld[0]; ry = -deviceToWorld[3]; rz = -deviceToWorld[6]
            ux = -deviceToWorld[1]; uy = -deviceToWorld[4]; uz = -deviceToWorld[7]
        }
        DISPLAY_ROTATION_270 -> {
            rx = -deviceToWorld[1]; ry = -deviceToWorld[4]; rz = -deviceToWorld[7]
            ux = deviceToWorld[0]; uy = deviceToWorld[3]; uz = deviceToWorld[6]
        }
        else -> {
            rx = deviceToWorld[0]; ry = deviceToWorld[3]; rz = deviceToWorld[6]
            ux = deviceToWorld[1]; uy = deviceToWorld[4]; uz = deviceToWorld[7]
        }
    }

    out[0] = rx; out[1] = -ux; out[2] = fx
    out[3] = ry; out[4] = -uy; out[5] = fy
    out[6] = rz; out[7] = -uz; out[8] = fz
    return out
}

/**
 * The rear camera's yaw/pitch/roll from a camera basis matrix in the
 * [cameraBasisMatrix] layout, in this app's conventions.
 *
 * The angles are read from the geometry directly rather than through the
 * platform's azimuth/pitch/roll helper (which describes a flat screen, not a
 * camera, and is what a naive remap shortcut gets wrong):
 *
 * - **forward** is where the lens points. Yaw is its compass bearing, elevation
 *   its height above the horizon (pitch is negated to match the app's
 *   "negative is up" convention).
 * - **up** is the top of the captured, display-upright image. Roll is the angle
 *   between that up and the unrolled-up for the same yaw/elevation.
 *
 * This is the *inverse* of the axis construction `CameraBasis.of` performs:
 * feeding it the basis that class builds from a pose must hand the same angles
 * back, or every frame lands on the sphere rotated — which is exactly the kind
 * of "aligned but all at odd angles" failure that no amount of stitching can
 * rescue. The result is written into [out] (a scratch buffer).
 */
internal fun anglesFromCameraBasis(basis: FloatArray, out: FloatArray): FloatArray {
    val fx = basis[2].toDouble()
    val fy = basis[5].toDouble()
    val fz = basis[8].toDouble()

    val yaw = toDegreesSafe(atan2(fx, fy)).toFloat()
    val elevation = asin(fz.coerceIn(-1.0, 1.0))
    val pitch = -toDegreesSafe(elevation).toFloat()

    // The unrolled pair `CameraBasis.of` would build for this yaw/elevation,
    // measured against the actual up to recover the roll (see its `toPose`).
    val yawRad = toRadiansSafe(yaw.toDouble())
    val sinYaw = sin(yawRad)
    val cosYaw = cos(yawRad)
    val sinElevation = sin(elevation)
    val cosElevation = cos(elevation)
    val up0X = -sinYaw * sinElevation
    val up0Y = -cosYaw * sinElevation
    val up0Z = cosElevation
    val rx = basis[0].toDouble()
    val ry = basis[3].toDouble()
    val rz = basis[6].toDouble()
    // The stored matrix mirrors the up axis (`[right, −up, forward]` columns,
    // so rotation algebra sees a proper rotation); un-mirror it back to the
    // camera's own up before measuring the roll against it.
    val ux = -basis[1].toDouble()
    val uy = -basis[4].toDouble()
    val uz = -basis[7].toDouble()
    val sinRoll = -(rx * up0X + ry * up0Y + rz * up0Z)
    val cosRoll = ux * up0X + uy * up0Y + uz * up0Z

    out[0] = normalizeDegrees(yaw)
    out[1] = pitch
    out[2] = normalizeDegrees(toDegreesSafe(atan2(sinRoll, cosRoll)).toFloat())
    return out
}

/**
 * The rear camera's yaw/pitch/roll in this app's conventions, read straight off
 * the device→world rotation matrix [deviceToWorld].
 *
 * The composition of [cameraBasisMatrix] and [anglesFromCameraBasis]; kept
 * whole because the round-trip contract with `CameraBasis.of` is tested
 * against it directly. The result is written into [out] (a scratch buffer).
 */
internal fun cameraAnglesDegrees(
    deviceToWorld: FloatArray,
    displayRotation: Int,
    out: FloatArray,
): FloatArray {
    val basis = FloatArray(MATRIX_SIZE)
    cameraBasisMatrix(deviceToWorld, displayRotation, basis)
    return anglesFromCameraBasis(basis, out)
}

/** `getRotationMatrixFromVector` works with a 3x3 row-major matrix. */
private const val MATRIX_SIZE = 9
