package com.n30dyn4m1c.photosphere.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where captured frames go.
 *
 * Two destinations, for two different kinds of image:
 *
 * - **Cache sessions** ([sessionDirectory], [newTempFrameOptions]) hold the raw
 *   frames of a capture run. They are intermediate data, they are large, and
 *   they are cleared when the next run starts.
 * - **MediaStore** ([newFrameOutputOptions]) is for images the user keeps. It
 *   works on every supported API level, so no storage permission is needed from
 *   API 29 up; on API 26–28 the insert still resolves to a legacy file path, so
 *   `WRITE_EXTERNAL_STORAGE` must be granted first (see
 *   `MainActivity.REQUIRED_PERMISSIONS`).
 */
object SphereImageStore {

    private const val TAG = "SphereImageStore"
    private const val ALBUM = "PhotoSphere"
    private const val MIME_TYPE = "image/jpeg"

    /** Cache subdirectory holding one directory per capture session. */
    private const val SESSIONS_DIRECTORY = "sphere_sessions"

    /** Output options plus the display name they will produce. */
    data class FrameOutputRequest(
        val outputOptions: ImageCapture.OutputFileOptions,
        val displayName: String,
    )

    /** Output options plus the cache file they will write to. */
    data class TempFrameRequest(
        val outputOptions: ImageCapture.OutputFileOptions,
        val file: File,
    )

    /** Identifier for one run of guided capture. Sorts chronologically. */
    fun newSessionId(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /**
     * Directory holding the frames of [sessionId], created if needed.
     *
     * Guided capture writes to the cache rather than the gallery: these frames
     * are stitcher input, and a user who asked for one sphere has not asked for
     * forty-four photos in their camera roll. Only the finished equirectangular
     * image belongs in MediaStore.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun sessionDirectory(context: Context, sessionId: String): File =
        File(File(context.cacheDir, SESSIONS_DIRECTORY), sessionId).apply { mkdirs() }

    /** Where frame [index] of a session should be written. */
    fun newTempFrameOptions(directory: File, index: Int): TempFrameRequest {
        val file = File(directory, "frame_%03d.jpg".format(index))
        return TempFrameRequest(
            outputOptions = ImageCapture.OutputFileOptions.Builder(file).build(),
            file = file,
        )
    }

    /**
     * Deletes every session directory except [keepSessionId].
     *
     * A sphere's worth of full-resolution JPEGs is on the order of a hundred
     * megabytes. The cache is reclaimable by the system, but only under
     * pressure, so an abandoned run is cleared at the start of the next one
     * instead of being left for Android to notice.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun pruneSessions(context: Context, keepSessionId: String) {
        val root = File(context.cacheDir, SESSIONS_DIRECTORY)
        val sessions = root.listFiles() ?: return
        sessions.forEach { session ->
            if (session.name != keepSessionId) {
                if (!session.deleteRecursively()) {
                    Log.w(TAG, "Could not clear stale session ${session.name}")
                }
            }
        }
    }

    /**
     * Records the device attitude a frame was shot at, in EXIF.
     *
     * The stitcher gets a starting guess at each frame's place on the sphere
     * from this, which is the difference between searching for correspondences
     * and merely confirming them. Written as a `UserComment` because EXIF has no
     * standard tag for camera attitude, in a fixed `key=value;` form so it can be
     * parsed back without ambiguity.
     */
    fun stampCaptureOrientation(
        file: File,
        index: Int,
        yawDegrees: Float,
        pitchDegrees: Float,
        rollDegrees: Float,
    ) {
        try {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere")
                setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "$ALBUM frame $index")
                setAttribute(
                    ExifInterface.TAG_USER_COMMENT,
                    "index=%d;yaw=%.3f;pitch=%.3f;roll=%.3f"
                        .format(Locale.US, index, yawDegrees, pitchDegrees, rollDegrees),
                )
                saveAttributes()
            }
        } catch (e: Exception) {
            // Metadata is a nice-to-have; never lose the frame over it.
            Log.w(TAG, "Could not write EXIF for ${file.name}", e)
        }
    }

    fun newFrameOutputOptions(context: Context, index: Int): FrameOutputRequest {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "sphere_%s_%03d.jpg".format(timestamp, index)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped storage: the system picks the real path from this hint.
                // IS_PENDING is intentionally not set here — CameraX's ImageSaver
                // raises and clears it around the write on its own.
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                context.contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values,
            )
            .build()

        return FrameOutputRequest(outputOptions, displayName)
    }

    /**
     * Stamps identifying EXIF tags onto a saved frame.
     *
     * CameraX already writes orientation and the capture timestamp; this adds the
     * album/sequence markers the stitcher uses to group a run of frames.
     *
     * Note: the "this is a 360 photo" marker that Google Photos and other viewers
     * look for is XMP GPano, not EXIF, and [ExifInterface] cannot write XMP.
     * The equirectangular output will need GPano injected separately once the
     * stitch step lands.
     */
    fun stampSphereMetadata(context: Context, uri: Uri, index: Int) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere")
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "$ALBUM frame $index")
                    saveAttributes()
                }
            }
        } catch (e: Exception) {
            // Metadata is a nice-to-have; never lose the frame over it.
            Log.w(TAG, "Could not write EXIF for $uri", e)
        }
    }

    /** Reads back the orientation CameraX recorded for a frame. */
    fun readOrientation(context: Context, uri: Uri): Int =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
}
