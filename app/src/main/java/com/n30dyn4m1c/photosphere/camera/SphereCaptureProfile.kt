package com.n30dyn4m1c.photosphere.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2Interop

/**
 * How one sphere is captured, decided once per device.
 *
 * A photosphere's seams are exposure and colour seams: if every frame re-runs
 * the camera's auto-exposure and auto-white-balance on its own, three frames of
 * a room and one of a window come back in three different brightnesses and
 * colour temperatures, and no amount of blending can hide that. So the session
 * holds them all — AE lock pins the ISO and shutter speed the HAL converged on
 * when the preview started, AWB lock pins the colour temperature, and focus is
 * fixed so nothing re-focuses mid-sweep. This is the same model GCam's
 * Photosphere uses: lock exposure and focus to the first frame, then sweep.
 *
 * The one thing that varies per device is *how* focus is fixed, because not
 * every lens advertises `AF_MODE_OFF`:
 *
 * - [FocusMode.FIXED_INFINITY] — the lens supports `AF_MODE_OFF`, so focus is
 *   parked at infinity (0 diopters) for the whole session. Sharpest for the
 *   wide, far scenes a sphere is built from, and completely immune to focus
 *   hunting.
 * - [FocusMode.FIXED_FOCUS] — the lens physically has no focus mechanism
 *   (`LENS_INFO_MINIMUM_FOCUS_DISTANCE` is 0); `AF_MODE_OFF` is all there is.
 * - [FocusMode.LOCK_ON_FIRST] — a lens that cannot be switched off is put in
 *   `AF_MODE_AUTO` and triggered once before the first frame, which leaves it
 *   locked on the first scene for the rest of the session (GCam's tap-to-focus
 *   behaviour).
 */
data class SphereCaptureProfile(
    val focusMode: FocusMode,
    val burstPerTarget: Int,
    val aeLockSupported: Boolean,
    val awbLockSupported: Boolean,
    val opticalStabilizationSupported: Boolean,
) {
    companion object {
        /**
         * How many frames each target is burst with before the sharpest is kept.
         *
         * The burst is shot at identical, locked settings, so it is not an HDR
         * bracket — it is handheld-tremor insurance: at the long shutter speeds
         * AE lock leaves in low light, one of two frames is usually noticeably
         * sharper, and the stitcher never sees the other. Two keeps the per-target
         * cost close to a single capture on the S23's write speed.
         */
        const val DEFAULT_BURST_PER_TARGET: Int = 2
    }
}

/**
 * What focus does across a session.
 *
 * Kept top-level rather than nested so the screen can pattern-match on it
 * without qualification.
 */
enum class FocusMode {
    /** `AF_MODE_OFF` + `LENS_FOCUS_DISTANCE = 0` (infinity), held the whole time. */
    FIXED_INFINITY,

    /** The lens has no focus mechanism; `AF_MODE_OFF` is mandatory. */
    FIXED_FOCUS,

    /** `AF_MODE_AUTO`, triggered once on the first scene and then held. */
    LOCK_ON_FIRST,
}

/**
 * The focus strategy for a lens that advertises the given AF modes.
 *
 * [minimumFocusDistance] is `LENS_INFO_MINIMUM_FOCUS_DISTANCE`: 0 means the
 * lens is fixed-focus and has nothing to lock; anything else means it can
 * focus, and `AF_MODE_OFF` is the deterministic way to hold it still.
 */
internal fun resolveFocusMode(
    afModes: IntArray?,
    minimumFocusDistance: Float?,
): FocusMode = when {
    minimumFocusDistance != null && minimumFocusDistance <= 0f -> FocusMode.FIXED_FOCUS
    afModes?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) == true -> FocusMode.FIXED_INFINITY
    else -> FocusMode.LOCK_ON_FIRST
}

/**
 * Whether AE/AWB locks are worth setting.
 *
 * The HAL reports support via `CONTROL_AE_LOCK_AVAILABLE`/`CONTROL_AWB_LOCK_AVAILABLE`.
 * Missing means the capability is unknown, not absent — every shipping camera
 * supports these, so an unknown characteristic is treated as supported (an
 * unsupported key is simply ignored by the HAL).
 */
internal fun resolveLockSupport(available: Boolean?): Boolean = available ?: true

/** Whether the lens offers hardware optical stabilisation. */
internal fun resolveOpticalStabilization(stabilizationModes: IntArray?): Boolean =
    stabilizationModes?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

/**
 * The capture profile for this device's rear camera.
 *
 * Deliberately does not throw: a camera that will not describe itself gets the
 * most conservative profile (locks everything, locks focus on the first frame)
 * rather than failing capture.
 */
internal fun resolveSphereCaptureProfile(context: Context): SphereCaptureProfile {
    val characteristics = runCatching { backCameraCharacteristics(context) }.getOrNull()
    return SphereCaptureProfile(
        focusMode = resolveFocusMode(
            afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES),
            minimumFocusDistance =
                characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE),
        ),
        burstPerTarget = SphereCaptureProfile.DEFAULT_BURST_PER_TARGET,
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

/** Characteristics of the rear camera, or null if none can be queried. */
private fun backCameraCharacteristics(context: Context): CameraCharacteristics? {
    val manager = context.getSystemService(CameraManager::class.java) ?: return null
    for (id in manager.cameraIdList) {
        val characteristics = manager.getCameraCharacteristics(id)
        if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_BACK
        ) {
            return characteristics
        }
    }
    return null
}

/**
 * Applies the 3A locks to a use case's requests.
 *
 * This is deliberately applied to *both* the Preview and ImageCapture builders:
 * the preview is the session's repeating request, and a lock lives there for
 * the whole session — AE pinned to the exposure it converged on, AWB pinned to
 * the colour temperature, focus parked — so the viewfinder shows exactly what
 * each frame will record and no frame is allowed to re-meter on its own. The
 * still requests carry the same locks so they cannot deviate even if the
 * repeating request were interrupted.
 */
internal fun applySphereCaptureOptions(
    extender: Camera2Interop.Extender<*>,
    profile: SphereCaptureProfile,
) {
    // AE lock holds the ISO and shutter speed the HAL converged on; AWB lock
    // holds the colour temperature. Together they make every frame of the
    // sphere identical in brightness and tint. The HAL still runs its initial
    // convergence, then holds.
    if (profile.aeLockSupported) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
    }
    if (profile.awbLockSupported) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
    }
    when (profile.focusMode) {
        FocusMode.FIXED_INFINITY -> {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
            // 0 diopters is infinity; without it the HAL keeps whatever distance
            // the last AF run left behind.
            extender.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
        }
        FocusMode.FIXED_FOCUS -> {
            extender.setCaptureRequestOption(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF,
            )
        }
        FocusMode.LOCK_ON_FIRST -> {
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
 * preview.
 */
internal fun applyStillImageOptions(
    extender: Camera2Interop.Extender<*>,
    profile: SphereCaptureProfile,
) {
    if (profile.opticalStabilizationSupported) {
        extender.setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
    }
}
