package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Angular extent of the preview, as it appears on screen.
 *
 * These are *screen* axes, not sensor axes: on a portrait phone the vertical
 * field of view is the wider of the two because the sensor's long edge runs down
 * the display.
 */
data class FieldOfView(
    val horizontalDegrees: Float,
    val verticalDegrees: Float,
) {
    init {
        require(horizontalDegrees in 1f..179f) { "horizontal fov out of range: $horizontalDegrees" }
        require(verticalDegrees in 1f..179f) { "vertical fov out of range: $verticalDegrees" }
    }

    /** Swaps the axes, for turning a sensor-frame reading into a screen-frame one. */
    fun transposed(): FieldOfView = FieldOfView(verticalDegrees, horizontalDegrees)
}

/**
 * A target seen from the camera: its direction expressed in the camera's own
 * frame, where +X is to the right of the frame, +Y is up, and +Z is straight
 * down the lens.
 *
 * The vector is a unit vector, so [z] is the cosine of the angle off the optical
 * axis — which is all [angularDistanceDegrees] is.
 */
data class TargetView(
    val x: Float,
    val y: Float,
    val z: Float,
    val angularDistanceDegrees: Float,
) {
    /** False when the target sits behind the camera and cannot be projected. */
    val isInFront: Boolean get() = z > MIN_FORWARD_COMPONENT

    internal companion object {
        /**
         * Directions closer to the image plane than this are treated as behind
         * the camera: their projection races off to infinity and is useless as a
         * screen position.
         */
        const val MIN_FORWARD_COMPONENT = 1e-3f
    }
}

/**
 * Maps sphere targets onto the viewfinder.
 *
 * The model is a plain rectilinear (pinhole) camera: a target's direction is
 * rotated out of the world frame into the camera frame, then divided through by
 * its depth. That is the same projection the lens performs, so a marker drawn
 * this way lands on the pixel the target will actually occupy — which is what
 * makes "centre the reticle on the dot" a meaningful instruction.
 *
 * Everything here is pure maths over [OrientationData]; there is no Android or
 * Compose dependency, so it is exercised directly by unit tests.
 */
object SphereProjection {

    /**
     * Focal length, in pixels, of a viewport that shows [fieldOfView].
     *
     * One number covers both axes because the preview is scaled uniformly
     * (`FILL_CENTER`). The larger of the two candidates wins: it is the one that
     * keeps *both* axes inside the available field of view, with the other axis
     * cropped — exactly what filling the viewport does to the frame.
     */
    fun focalLengthPx(widthPx: Float, heightPx: Float, fieldOfView: FieldOfView): Float {
        val horizontal = (widthPx / 2.0) / tanHalf(fieldOfView.horizontalDegrees)
        val vertical = (heightPx / 2.0) / tanHalf(fieldOfView.verticalDegrees)
        return max(horizontal, vertical).toFloat()
    }

    /** Where [target] sits relative to the camera at [orientation]. */
    fun project(orientation: OrientationData, target: SphereTarget): TargetView =
        cameraFrame(orientation).project(target)

    /**
     * The camera's axes for one attitude, ready to project many targets through.
     *
     * The overlay redraws the whole plan every frame — hundreds of markers at
     * display rate — and the axes are the same for all of them. Building them
     * once per draw instead of once per marker takes the per-marker cost down to
     * nine multiplies.
     */
    fun cameraFrame(orientation: OrientationData): CameraFrame {
        val yaw = Math.toRadians(orientation.yawDegrees.toDouble())
        val elevation = Math.toRadians(orientation.elevationDegrees().toDouble())
        val roll = Math.toRadians(orientation.rollDegrees.toDouble())

        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)
        val cosElevation = cos(elevation)

        // Camera axes in the world frame (X east, Y north, Z up).
        // Forward is where the lens points.
        val fx = sinYaw * cosElevation
        val fy = cosYaw * cosElevation
        val fz = sin(elevation)

        // Right and up for an unrolled device: "right" is the bearing 90° off
        // the aim and stays horizontal, "up" completes the triple (r × f = u).
        val r0x = cosYaw
        val r0y = -sinYaw
        val u0x = r0y * fz
        val u0y = -r0x * fz
        val u0z = r0x * fy - r0y * fx

        // Roll turns the pair about the forward axis. Both are perpendicular to
        // it, so Rodrigues collapses to a plain 2D rotation in their plane —
        // f × r = -u and f × u = r.
        val cosRoll = cos(roll)
        val sinRoll = sin(roll)
        return CameraFrame(
            forwardX = fx,
            forwardY = fy,
            forwardZ = fz,
            rightX = r0x * cosRoll - u0x * sinRoll,
            rightY = r0y * cosRoll - u0y * sinRoll,
            rightZ = -u0z * sinRoll,
            upX = u0x * cosRoll + r0x * sinRoll,
            upY = u0y * cosRoll + r0y * sinRoll,
            upZ = u0z * cosRoll,
        )
    }

    /**
     * Angle between where the camera points and [target], in degrees.
     *
     * This is the number the capture trigger thresholds on. Roll is deliberately
     * ignored: turning the phone in its own plane does not change what the lens
     * is aimed at, and demanding a level device would make the sphere far more
     * tedious to shoot than it needs to be.
     */
    fun angularDistanceDegrees(orientation: OrientationData, target: SphereTarget): Float =
        project(orientation, target).angularDistanceDegrees

    private fun tanHalf(degrees: Float): Double = tan(Math.toRadians(degrees / 2.0))
}

/**
 * The camera's orthonormal axes in the world frame (X east, Y north, Z up).
 *
 * Built once per attitude by [SphereProjection.cameraFrame]; the components are
 * held as flat doubles so projecting a target allocates nothing beyond the
 * result.
 */
class CameraFrame internal constructor(
    private val forwardX: Double,
    private val forwardY: Double,
    private val forwardZ: Double,
    private val rightX: Double,
    private val rightY: Double,
    private val rightZ: Double,
    private val upX: Double,
    private val upY: Double,
    private val upZ: Double,
) {
    /** Where [target] sits relative to this camera. */
    fun project(target: SphereTarget): TargetView {
        val dx = target.directionX
        val dy = target.directionY
        val dz = target.directionZ
        val z = dx * forwardX + dy * forwardY + dz * forwardZ
        return TargetView(
            x = (dx * rightX + dy * rightY + dz * rightZ).toFloat(),
            y = (dx * upX + dy * upY + dz * upZ).toFloat(),
            z = z.toFloat(),
            angularDistanceDegrees = Math.toDegrees(acos(z.coerceIn(-1.0, 1.0))).toFloat(),
        )
    }
}

/** Height above the horizon, positive up. See [SphereTarget.elevationDegrees]. */
internal fun OrientationData.elevationDegrees(): Float = -pitchDegrees
