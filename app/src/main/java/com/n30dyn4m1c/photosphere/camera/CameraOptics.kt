package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import com.n30dyn4m1c.photosphere.stitching.RadialDistortion
import kotlin.math.atan
import kotlin.math.max

private const val TAG = "CameraOptics"

/**
 * Field of view of a mid-range phone's main camera, in the sensor's own
 * (landscape) frame. Used when the camera refuses to describe itself.
 */
private val DEFAULT_SENSOR_FIELD_OF_VIEW = FieldOfView(
    horizontalDegrees = 66f,
    verticalDegrees = 52f,
)

/**
 * The optics of the rear camera, as this device reports them.
 *
 * Carries both halves the pipeline needs: the on-screen [FieldOfView] for
 * laying out targets and placing markers, and the lens's radial
 * [RadialDistortion] so the stitcher can correct frame edges. `null` distortion
 * means the camera gave no calibration — the stitcher then assumes a pinhole.
 */
data class SphereOptics(
    val fieldOfView: FieldOfView,
    val radialDistortion: RadialDistortion?,
)

/**
 * The field of view of the rear camera as it appears on this screen.
 *
 * Re-read whenever the configuration changes, since a display rotation swaps
 * which sensor axis runs across the screen.
 */
@Composable
fun rememberCameraFieldOfView(
    lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
): FieldOfView = rememberSphereOptics(lensFacing).fieldOfView

/**
 * The full optics estimate for the rear camera, cached per configuration.
 *
 * The capture and stitch screens both want this: the capture loop lays out its
 * targets from [SphereOptics.fieldOfView] and hands the whole thing to the
 * stitcher so it can correct distortion.
 */
@Composable
fun rememberSphereOptics(
    lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
): SphereOptics {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, lensFacing) {
        estimateSphereOptics(
            context,
            context.currentDisplayRotation(),
            SphereDeviceProfile.forDevice(),
        )
    }
}

/**
 * Derives the on-screen field of view from the camera's optics.
 *
 * The preferred source is the camera's own calibration:
 * `LENS_INTRINSIC_CALIBRATION` reports the focal length in pixels directly and
 * `SENSOR_INFO_ACTIVE_ARRAY_SIZE` the region of the sensor the frames are read
 * from, which together give the exact angle each edge subtends — no physical
 * size to guess at, and no systematic bias from ignoring the active-array crop.
 * When the camera will not calibrate, the estimate falls back to physical
 * sensor size and focal length, then to typical phone values.
 *
 * Two corrections then land it on the screen: the sensor's mounting rotation
 * relative to the display decides which of those angles runs across the
 * viewport, and the preview's `FILL_CENTER` scaling crops whichever axis has
 * room to spare — the crop is handled downstream, by
 * [SphereProjection.focalLengthPx], which takes the field of view as the
 * *uncropped* maximum.
 *
 * The same query also reads `LENS_DISTORTION` (API 28+), the lens's Brown-Conrady
 * coefficients at the active-array scale, so the stitcher can straighten frame
 * edges instead of treating the lens as a pinhole.
 */
internal fun estimateSphereOptics(
    context: Context,
    displayRotation: Int,
    profile: SphereDeviceProfile,
): SphereOptics {
    var sensorFieldOfView = DEFAULT_SENSOR_FIELD_OF_VIEW
    // Portrait-first phones mount the sensor rotated a quarter turn; assume that
    // when the camera cannot be queried, since it matches nearly all hardware.
    var sensorOrientation = 90
    var radialDistortion: RadialDistortion? = null

    val manager = context.getSystemService(CameraManager::class.java)
    if (manager != null) {
        try {
            // The same camera capture binds, so the field of view and distortion
            // the stitcher is handed match the frames it is handed.
            val cameraId = captureBackCameraId(context, profile)
            if (cameraId != null) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                sensorOrientation =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

                val activeArray =
                    characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val intrinsic =
                    characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)

                if (activeArray != null && intrinsic != null && intrinsic.size >= 2) {
                    val focalX = intrinsic[0]
                    val focalY = intrinsic[1]
                    if (focalX > 0f && focalY > 0f) {
                        sensorFieldOfView = FieldOfView(
                            horizontalDegrees = fieldOfViewDegrees(activeArray.width().toFloat(), focalX),
                            verticalDegrees = fieldOfViewDegrees(activeArray.height().toFloat(), focalY),
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val kappa = characteristics.get(CameraCharacteristics.LENS_DISTORTION)
                            if (kappa != null && kappa.size >= 3) {
                                radialDistortion = RadialDistortion(
                                    coefficients = doubleArrayOf(
                                        kappa[0].toDouble(),
                                        kappa[1].toDouble(),
                                        kappa[2].toDouble(),
                                    ),
                                    calibrationLongestEdgePx =
                                        max(activeArray.width(), activeArray.height()),
                                )
                            }
                        }
                    }
                } else {
                    val physicalSize =
                        characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val focalLengthMm = characteristics
                        .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        ?.firstOrNull()
                    if (physicalSize != null && focalLengthMm != null && focalLengthMm > 0f) {
                        sensorFieldOfView = FieldOfView(
                            horizontalDegrees = fieldOfViewDegrees(physicalSize.width, focalLengthMm),
                            verticalDegrees = fieldOfViewDegrees(physicalSize.height, focalLengthMm),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // A camera that will not describe itself still previews fine; the
            // markers just ride on the default optics.
            Log.w(TAG, "Falling back to default field of view", e)
        }
    }

    val relativeRotation = ((sensorOrientation - displayRotation.toDegrees()) % 360 + 360) % 360
    val fieldOfView =
        if (relativeRotation % 180 == 90) sensorFieldOfView.transposed() else sensorFieldOfView
    return SphereOptics(fieldOfView = fieldOfView, radialDistortion = radialDistortion)
}

/**
 * Angle subtended by an extent of [extent] (pixels or millimetres) behind a lens
 * of [focal] in the same units.
 */
private fun fieldOfViewDegrees(extent: Float, focal: Float): Float =
    Math.toDegrees(2.0 * atan(extent / (2.0 * focal))).toFloat().coerceIn(1f, 179f)

/** `Surface.ROTATION_*` as the number of degrees the display is turned by. */
private fun Int.toDegrees(): Int = when (this) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
