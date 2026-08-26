package com.n30dyn4m1c.photosphere.ui

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Every user-facing string in the app, in the one place every target reads.
 *
 * The Android sources pulled these out of `res/values/strings.xml`; a shared UI
 * cannot reach that resource machinery from Kotlin/Native, and the app ships
 * English-only anyway. Parameterised strings become functions with typed
 * parameters — which also makes a wrong argument count a compile error instead
 * of a runtime `MissingFormatArgumentException`.
 */
object Strings {

    const val APP_NAME = "Photo Sphere"

    // -- Permissions ---------------------------------------------------------

    const val PERMISSION_CAMERA_TITLE = "Camera access needed"
    const val PERMISSION_CAMERA_RATIONALE =
        "Photo Sphere stitches a 360° panorama from frames it captures as you pan " +
            "the device, so it needs access to the camera."
    const val PERMISSION_CAMERA_DENIED_PERMANENTLY =
        "Camera access is turned off for this app. Enable it in Settings to capture a sphere."
    const val PERMISSION_STORAGE_RATIONALE =
        "Storage access is needed to save finished spheres to your gallery on this version of Android."
    const val PERMISSION_GRANT = "Grant access"
    const val PERMISSION_OPEN_SETTINGS = "Open settings"

    // -- Capture -------------------------------------------------------------

    const val CAPTURE_STARTING = "Starting camera…"
    fun captureFailed(reason: String) = "Capture failed: $reason"

    /** Guided capture HUD. */
    const val CAPTURE_SCOPE_SPHERE = "Sphere"
    const val CAPTURE_SCOPE_RING = "Horizon ring"
    const val CAPTURE_PROGRESS_FRAMES = "frames"

    /** Spoken by a screen reader in place of the progress pill's separate numbers. */
    fun captureProgressDescription(done: Int, total: Int) = "$done of $total frames captured"
    fun captureRings(done: Int, total: Int) = "$done of $total bands"
    const val CAPTURE_LOCK_FOCUSING = "Focusing…"
    const val CAPTURE_LOCK_LOCKED = "Focus locked · AE & WB locked"
    const val CAPTURE_LOCK_HINT = "Tap the viewfinder to focus"
    const val CAPTURE_LOCK_FIXED = "Fixed-focus lens"
    const val CAPTURE_HINT_SEARCH = "Move the reticle onto the marker"
    const val CAPTURE_HINT_HOLD = "Hold still…"
    fun captureHintRingDone(band: Int, total: Int) =
        "Band $band of $total covered — stitch now, or keep going"
    const val CAPTURE_WAITING_FOR_ORIENTATION = "Finding your bearing…"
    const val CAPTURE_LOW_ACCURACY =
        "Compass roughly calibrated — a figure eight will sharpen it"
    const val CAPTURE_ACCURACY_BLOCKED =
        "Compass unreliable — wave the phone in a figure eight"
    const val CAPTURE_ORIENTATION_UNAVAILABLE =
        "This device has no rotation vector sensor, so guided capture is unavailable."
    const val CAPTURE_SPHERE_COMPLETE = "Sphere complete"
    const val CAPTURE_RESTART = "Start a new sphere"
    const val CAPTURE_UNDO = "Undo last shot"
    const val CAPTURE_FINISH_STITCH = "Finish & stitch"

    const val CAPTURE_INSTRUCTIONS_TITLE = "Capture your sphere"
    const val CAPTURE_INSTRUCTIONS_SUBTITLE = "Aim · Steady · Shoot"
    const val CAPTURE_STEP_1 = "Stay put"
    const val CAPTURE_STEP_2 = "Aim & hold"
    const val CAPTURE_STEP_3 = "Tap to focus"
    const val CAPTURE_STEP_4 = "Good scene"
    const val CAPTURE_INSTRUCTIONS_1 =
        "Stand in one spot and turn your body — don't walk."
    const val CAPTURE_INSTRUCTIONS_2 =
        "Line the reticle up with each marker and hold still — the shutter fires on its own."
    const val CAPTURE_INSTRUCTIONS_3 =
        "Tap the viewfinder to focus on the scene, just like a regular camera."
    const val CAPTURE_INSTRUCTIONS_4 =
        "Keep the phone at the same height and avoid blank walls and bright skies."
    const val CAPTURE_INSTRUCTIONS_DISMISS = "Start capturing"

    // -- Stitching -----------------------------------------------------------

    const val STITCH_TITLE = "Building your sphere"
    const val STITCH_STAGE_PREPARING = "Getting ready…"
    fun stitchStageReading(done: Int, total: Int) = "Reading frames ($done / $total)"
    fun stitchStageRefining(done: Int, total: Int) = "Aligning frames ($done / $total)"
    const val STITCH_STAGE_SEAMING = "Carving seams…"
    const val STITCH_STAGE_STITCHING = "Blending frames…"
    const val STITCH_STAGE_PROJECTING = "Shaping the equirectangular image…"
    const val STITCH_CANCEL = "Cancel"
    fun stitchFailed(reason: String) = "Stitch failed. $reason"
    const val STITCH_ERROR_NEED_MORE_IMAGES =
        "Too little of the scene was captured. Take at least three frames on neighbouring " +
            "markers and try again."
    const val STITCH_ERROR_ALIGNMENT =
        "The frames don't line up. Turn the phone on the spot rather than walking around, " +
            "and avoid blank walls or open sky."
    const val STITCH_ERROR_CAMERA_ESTIMATION =
        "The frames couldn't be resolved into one camera view. Try capturing the sphere again."
    const val STITCH_ERROR_OPENCV_UNAVAILABLE =
        "Stitching is unavailable on this device — the OpenCV library did not load."
    const val STITCH_ERROR_UNREADABLE_INPUT =
        "A captured frame could not be read. Start a new sphere."
    const val STITCH_ERROR_OUT_OF_MEMORY =
        "Ran out of memory. Close other apps and try again with fewer frames."
    fun stitchErrorUnknown(code: Int) = "Something went wrong (code $code)."
    const val STITCH_ERROR_WRITE_FAILED =
        "The sphere couldn't be written to storage. Free up some space and try again."

    // -- Result preview ------------------------------------------------------

    const val RESULT_TITLE = "Your 360° photo"
    const val RESULT_BADGE = "360°"
    fun resultDimensions(width: Int, height: Int) = "$width × $height · equirectangular"
    const val RESULT_PREVIEW_DESCRIPTION =
        "Preview of the stitched 360° photo, flattened"
    const val RESULT_PREVIEW_FAILED =
        "The preview couldn't be shown, but the photo is intact — export or share it to check."
    const val RESULT_EXPORT = "Export to gallery"
    const val RESULT_EXPORTING = "Saving…"
    const val RESULT_EXPORTED = "Saved to gallery"
    fun resultExportSuccess(relativePath: String) = "Saved to $relativePath"
    fun resultExportLocation(path: String, name: String) = "$path/$name"
    fun resultExportFailed(reason: String) = "Couldn't save to the gallery: $reason"
    const val RESULT_SHARE = "Share"
    const val RESULT_SHARE_FAILED = "No app on this device can receive a photo."
    const val RESULT_TAKE_ANOTHER = "New photo"
    const val RESULT_DISCARD_TITLE = "Discard this photo?"
    const val RESULT_DISCARD_MESSAGE =
        "It hasn't been saved to your gallery yet, so starting a new one deletes it for good."
    const val RESULT_DISCARD_CONFIRM = "Discard"
    const val RESULT_DISCARD_CANCEL = "Keep it"

    // -- 360° viewer ---------------------------------------------------------

    const val RESULT_EXPLORE_360 = "Explore in 360°"
    const val RESULT_VIEWER_CLOSE_DESCRIPTION = "Leave the 360° view"
    const val VIEWER_HINT = "Drag to look around · Pinch to zoom"
    const val VIEWER_DESCRIPTION =
        "Interactive 360° view of the stitched photo — drag to look around"

    // -- Settings ------------------------------------------------------------

    const val SETTINGS_TITLE = "Settings"
    const val SETTINGS_BACK_DESCRIPTION = "Back to capture"
    const val SETTINGS_RETICLE_COLOR = "Reticle colour"
    const val SETTINGS_RETICLE_PREVIEW_DESCRIPTION =
        "Preview of the capture reticle with the chosen colour and size"
    fun settingsReticleSize(percent: Int) = "Reticle size · $percent %"

    // -- Orientation debug screen --------------------------------------------

    const val ORIENTATION_DEBUG_TITLE = "Orientation tracker"
    const val ORIENTATION_SENSOR_UNAVAILABLE =
        "This device has no rotation vector sensor, so device orientation cannot be tracked."
    const val ORIENTATION_YAW = "Yaw"
    const val ORIENTATION_YAW_CAPTION =
        "Compass bearing of the camera, −180° to 180° (0° = north)"
    const val ORIENTATION_PITCH = "Pitch"
    const val ORIENTATION_PITCH_CAPTION =
        "Vertical tilt, −90° to 90° (negative = aimed above the horizon)"
    const val ORIENTATION_ROLL = "Roll"
    const val ORIENTATION_ROLL_CAPTION =
        "Side tilt, −180° to 180° (0° = display upright)"

    /** One decimal place, the way `%1$.1f°` formatted it on Android. */
    fun orientationDegrees(value: Float) = "${oneDecimal(value)}°"
    const val ORIENTATION_ACCURACY = "Sensor accuracy"
    const val ORIENTATION_SAMPLES = "Samples received"
    const val ORIENTATION_TIMESTAMP = "Last event"
    fun orientationTimestamp(millis: Double) = "${oneDecimal(millis.toFloat())} ms uptime"
    const val ORIENTATION_WAITING = "waiting…"
    const val ORIENTATION_DEBUG_OPEN = "Orientation debug"
    const val ORIENTATION_DEBUG_CLOSE = "Back to capture"

    // -- Desktop host (development only) --------------------------------------

    const val DESKTOP_CAPTURE_TITLE = "No camera on this host"
    const val DESKTOP_CAPTURE_BODY =
        "Capture runs on Android and iOS. The desktop target exists to run the " +
            "shared test suite; build and install a mobile app to shoot a sphere."

    /**
     * A float as `%1$.1f` would print it: exactly one digit after the point,
     * sign attached, no locale separator — the same string on every platform.
     */
    private fun oneDecimal(value: Float): String {
        val scaled = (value * 10f).roundToInt()
        val sign = if (scaled < 0) "-" else ""
        val magnitude = abs(scaled)
        return "$sign${magnitude / 10}.${magnitude % 10}"
    }
}
