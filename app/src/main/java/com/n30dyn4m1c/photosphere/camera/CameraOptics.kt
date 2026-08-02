package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.n30dyn4m1c.photosphere.sensor.currentDisplayRotation
import kotlin.math.atan

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
 * The field of view of the rear camera as it appears on this screen.
 *
 * Re-read whenever the configuration changes, since a display rotation swaps
 * which sensor axis runs across the screen.
 */
@Composable
fun rememberCameraFieldOfView(
    lensFacing: Int = CameraCharacteristics.LENS_FACING_BACK,
): FieldOfView {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration, lensFacing) {
        estimateFieldOfView(context, lensFacing, context.currentDisplayRotation())
    }
}

/**
 * Derives the on-screen field of view from the camera's optics.
 *
 * The sensor reports its physical size and focal length, which give the angle it
 * sees along each edge. Two corrections then land it on the screen: the sensor's
 * mounting rotation relative to the display decides which of those angles runs
 * across the viewport, and the preview's `FILL_CENTER` scaling crops whichever
 * axis has room to spare — the crop is handled downstream, by
 * [SphereProjection.focalLengthPx], which takes the field of view as the
 * *uncropped* maximum.
 *
 * This is an estimate. It reads the default focal length of a possibly
 * multi-focal lens, and ignores the difference between the physical sensor and
 * the active pixel array. Both are small next to the 2° capture threshold, and
 * an error here shifts markers slightly off-centre without ever changing which
 * direction a target is in.
 */
internal fun estimateFieldOfView(
    context: Context,
    lensFacing: Int,
    displayRotation: Int,
): FieldOfView {
    var sensorFieldOfView = DEFAULT_SENSOR_FIELD_OF_VIEW
    // Portrait-first phones mount the sensor rotated a quarter turn; assume that
    // when the camera cannot be queried, since it matches nearly all hardware.
    var sensorOrientation = 90

    val manager = context.getSystemService(CameraManager::class.java)
    if (manager != null) {
        try {
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == lensFacing
            }
            if (cameraId != null) {
                val characteristics = manager.getCameraCharacteristics(cameraId)
                sensorOrientation =
                    characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

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
        } catch (e: Exception) {
            // A camera that will not describe itself still previews fine; the
            // markers just ride on the default optics.
            Log.w(TAG, "Falling back to default field of view", e)
        }
    }

    val relativeRotation = ((sensorOrientation - displayRotation.toDegrees()) % 360 + 360) % 360
    return if (relativeRotation % 180 == 90) sensorFieldOfView.transposed() else sensorFieldOfView
}

/** Angle subtended by a sensor edge of [extentMm] behind a [focalLengthMm] lens. */
private fun fieldOfViewDegrees(extentMm: Float, focalLengthMm: Float): Float =
    Math.toDegrees(2.0 * atan(extentMm / (2.0 * focalLengthMm))).toFloat().coerceIn(1f, 179f)

/** `Surface.ROTATION_*` as the number of degrees the display is turned by. */
private fun Int.toDegrees(): Int = when (this) {
    Surface.ROTATION_90 -> 90
    Surface.ROTATION_180 -> 180
    Surface.ROTATION_270 -> 270
    else -> 0
}
