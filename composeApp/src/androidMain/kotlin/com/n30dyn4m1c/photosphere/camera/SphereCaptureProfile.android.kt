@file:OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
@file:SuppressLint("UnsafeOptInUsageError")

package com.n30dyn4m1c.photosphere.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import androidx.camera.camera2.interop.Camera2Interop

/**
 * The Android half of the capture profile: reading [SphereCaptureProfile]'s
 * decisions out of camera2 characteristics and writing them onto CameraX
 * requests. The decisions themselves are pure and tested in isolation.
 */

/**
 * The capture profile for this device's rear camera.
 *
 * Deliberately does not throw: a camera that will not describe itself gets the
 * most conservative profile (locks everything, tap-to-focus on the first scene)
 * rather than failing capture.
 */
internal fun resolveSphereCaptureProfile(
    context: Context,
    profile: SphereDeviceProfile,
): SphereCaptureProfile {
    val characteristics =
        runCatching { backCameraCharacteristics(context, profile) }.getOrNull()
    return SphereCaptureProfile(
        focusMode = resolveFocusMode(
            minimumFocusDistance =
                characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
        ),
        aeLockSupported = resolveLockSupport(
            characteristics?.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
        ),
        awbLockSupported = resolveLockSupport(
            characteristics?.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)
        ),
        opticalStabilizationSupported = resolveOpticalStabilization(
            characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ),
    )
}

/**
 * The back camera that captures the widest field of view, or null if none can
 * be found.
 *
 * A photosphere is captured faster the wider each frame is, so the widest
 * back-facing lens wins — on a Galaxy S23 that is the 12 MP ultrawide rather
 * than the 50 MP main, which roughly halves the number of frames a sphere
 * needs. Logical multi-cameras are skipped (they report their widest physical
 * lens but are not individually bindable), and depth/macro sensors are
 * excluded by requiring a real sensor behind the lens.
 */
internal fun widestBackCameraId(context: Context): String? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    var bestId: String? = null
    var bestFocal = Float.MAX_VALUE
    for (id in manager.cameraIdList) {
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
            ?: continue
        if (characteristics.get(CameraCharacteristics.LENS_FACING) !=
            CameraCharacteristics.LENS_FACING_BACK
        ) {
            continue
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            characteristics.physicalCameraIds.isNotEmpty()
        ) {
            continue
        }
        val focal = characteristics
            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.minOrNull()
            ?: continue
        val array = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            ?: continue
        if (array.width.toLong() * array.height < MIN_WIDE_SENSOR_PIXELS) continue
        if (focal < bestFocal) {
            bestFocal = focal
            bestId = id
        }
    }
    return bestId
}

/** Smallest sensor a "wide" lens may have before it is a depth or macro toy. */
private const val MIN_WIDE_SENSOR_PIXELS = 6_000_000L

/** The ID of the default back camera (the main lens), or null if none. */
internal fun defaultBackCameraId(context: Context): String? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    return manager.cameraIdList.firstOrNull { id ->
        runCatching {
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }.getOrDefault(false)
    }
}

/**
 * The back camera to capture with, honouring the device profile's preference.
 *
 * Sharpness-first profiles pick the main lens; speed-first profiles pick the
 * widest one. Either way, a missing or unbindable preference falls back to the
 * main camera.
 */
internal fun captureBackCameraId(context: Context, profile: SphereDeviceProfile): String? =
    if (profile.preferWidestCamera) {
        widestBackCameraId(context) ?: defaultBackCameraId(context)
    } else {
        defaultBackCameraId(context)
    }

/** Characteristics of the chosen rear camera, or null if none can be queried. */
private fun backCameraCharacteristics(context: Context, profile: SphereDeviceProfile): CameraCharacteristics? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    val id = captureBackCameraId(context, profile) ?: return null
    return runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
}

/**
 * Applies the focus strategy to a use case's requests.
 *
 * This is deliberately applied to *both* the Preview and ImageCapture builders:
 * the preview is the session's repeating request and the stills must agree with
 * it. AE and AWB locks are *not* set here — see [applyStillImageOptions] for
 * where they live and why they are deferred.
 */
internal fun applySphereCaptureOptions(
    extender: Camera2Interop.Extender<*>,
    profile: SphereCaptureProfile,
) {
    when (profile.focusMode) {
        FocusMode.FIXED_FOCUS -> {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
        }
        FocusMode.FOCUS_POINT -> {
            // AUTO + a tap trigger is how a regular camera locks focus on an
            // area: the sweep converges and the lens holds that distance until
            // the next trigger, so no frame re-focuses mid-sweep.
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_AUTO,
            )
        }
    }
}

/**
 * Applies options that belong on the still-image requests only.
 *
 * OIS is the lens steadying the shot; it belongs on the capture, not the
 * preview. AE and AWB locks belong here too, but only as a safety net: the
 * session locks them on the repeating request once the 3A has converged, and a
 * still fired mid-convergence must not re-meter on its own — the lock pins it
 * to whatever the converging repeating request has reached, so a premature
 * capture cannot walk away from the exposure the viewfinder is showing. The
 * initial convergence itself is left to run, because locking the very first
 * request freezes the exposure at the HAL's stream-start defaults and a dark
 * scene would never brighten.
 */
internal fun applyStillImageOptions(
    extender: Camera2Interop.Extender<*>,
    profile: SphereCaptureProfile,
) {
    // AE lock holds the ISO and shutter speed, AWB lock holds the colour
    // temperature; together they keep every frame of the sphere identical in
    // brightness and tint.
    if (profile.aeLockSupported) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
    }
    if (profile.awbLockSupported) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
    }
    if (profile.opticalStabilizationSupported) {
        extender.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
    }
}
