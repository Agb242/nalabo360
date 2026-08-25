package com.n30dyn4m1c.photosphere.camera

/**
 * How one sphere is captured, decided once per device.
 *
 * A photosphere's seams are exposure and colour seams: if every frame re-runs
 * the camera's auto-exposure and auto-white-balance on its own, three frames of
 * a room and one of a window come back in three different brightnesses and
 * colour temperatures, and no amount of blending can hide that. So the session
 * holds them all — AE lock pins the ISO and shutter speed the HAL converged on
 * when the preview started, AWB lock pins the colour temperature, and focus is
 * locked on the scene (by tap, or the first scene's centre) so nothing
 * re-focuses mid-sweep. This is the same model GCam's Photosphere uses: lock
 * exposure and focus, then sweep.
 *
 * The one rule the locks observe: they are applied only *after* the 3A has
 * converged on the scene the phone is pointed at. Locking from the stream's
 * first frame pins the exposure at the HAL's stream-start defaults — a short
 * exposure and low ISO chosen for a bright scene — which leaves a dark scene
 * permanently black: the viewfinder never brightens and every frame records a
 * black image. The session starts unlocked, waits for AE/AWB to converge (the
 * exposure ramps up over the first moments in low light), and locks to the
 * values they settled on.
 *
 * The one thing that varies per device is *how* focus is fixed:
 *
 * - [FocusMode.FOCUS_POINT] — the default for any lens that can focus. The
 *   session runs auto-focus, and the user taps the viewfinder to aim focus at a
 *   scene region; the sweep converges there and the lens holds that distance for
 *   the rest of the session, exactly like a regular camera's tap-to-focus lock.
 *   Until the first tap the lens holds the centre of the first scene it saw.
 *   This is what keeps frames sharp: focus is *locked on what the scene actually
 *   is* rather than parked at infinity, which reads as blur on any scene with
 *   something nearer than the horizon.
 * - [FocusMode.FIXED_FOCUS] — the lens physically has no focus mechanism; AF off
 *   is all there is.
 *
 * The platform half — reading these decisions out of each camera stack's
 * characteristics and writing them onto capture requests — lives beside this
 * file on Android.
 */
data class SphereCaptureProfile(
    val focusMode: FocusMode,
    val aeLockSupported: Boolean,
    val awbLockSupported: Boolean,
    val opticalStabilizationSupported: Boolean,
)

/**
 * What focus does across a session.
 *
 * Kept top-level rather than nested so the screen can pattern-match on it
 * without qualification.
 */
enum class FocusMode {
    /** The lens has no focus mechanism; autofocus-off is mandatory. */
    FIXED_FOCUS,

    /**
     * Tap-to-focus: autofocus triggered where the user taps and then held.
     *
     * The lens stays locked on that distance until the next tap — no frame
     * re-focuses mid-sweep, so a sphere's frames keep one focal plane, and the
     * shots are sharp on the scene instead of parked at infinity.
     */
    FOCUS_POINT,
}

/**
 * The focus strategy for a lens.
 *
 * [minimumFocusDistance] is the lens's minimum focus distance: 0 means the lens
 * is fixed-focus and has nothing to lock; anything else means it can focus, and
 * tap-to-focus is the way to lock it on the scene. Unknown falls through to
 * tap-to-focus, which is what nearly every phone lens wants.
 */
internal fun resolveFocusMode(minimumFocusDistance: Float?): FocusMode =
    if (minimumFocusDistance != null && minimumFocusDistance <= 0f) {
        FocusMode.FIXED_FOCUS
    } else {
        FocusMode.FOCUS_POINT
    }

/**
 * Whether AE/AWB locks are worth setting.
 *
 * The HAL reports support via its lock-available capabilities. Missing means the
 * capability is unknown, not absent — every shipping camera supports these, so
 * an unknown characteristic is treated as supported (an unsupported key is
 * simply ignored by the HAL).
 */
internal fun resolveLockSupport(available: Boolean?): Boolean = available ?: true

/**
 * `LENS_OPTICAL_STABILIZATION_MODE_ON` in camera2's metadata: stabilisation
 * engaged. The pure resolver matches against it by value so it stays testable
 * without the framework jar.
 */
internal const val OIS_MODE_ON = 1

/** Whether the lens offers hardware optical stabilisation. */
internal fun resolveOpticalStabilization(stabilizationModes: IntArray?): Boolean =
    stabilizationModes?.contains(OIS_MODE_ON) == true
