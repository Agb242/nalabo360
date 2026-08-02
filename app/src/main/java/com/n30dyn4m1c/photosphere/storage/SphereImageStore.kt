package com.n30dyn4m1c.photosphere.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
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

    /**
     * Encode quality for the finished sphere. High, because this is the only
     * image of the run that is kept and it has already been through one
     * generation of JPEG on the way in.
     */
    private const val SPHERE_JPEG_QUALITY = 95

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

    /** A finished sphere, as it now exists in the gallery. */
    data class SavedSphere(
        val uri: Uri,
        val displayName: String,
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

    /** Path frame [index] of a session occupies. Zero-padded so it sorts. */
    fun frameFile(directory: File, index: Int): File =
        File(directory, "frame_%03d.jpg".format(index))

    /** Where frame [index] of a session should be written. */
    fun newTempFrameOptions(directory: File, index: Int): TempFrameRequest {
        val file = frameFile(directory, index)
        return TempFrameRequest(
            outputOptions = ImageCapture.OutputFileOptions.Builder(file).build(),
            file = file,
        )
    }

    /**
     * Removes one session's directory and everything in it.
     *
     * Touches the filesystem — call off the main thread.
     */
    fun deleteSession(context: Context, sessionId: String) {
        val session = File(File(context.cacheDir, SESSIONS_DIRECTORY), sessionId)
        if (session.exists() && !session.deleteRecursively()) {
            Log.w(TAG, "Could not clear session $sessionId")
        }
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
     * Writes a finished equirectangular sphere to the gallery.
     *
     * This is the one image of a run the user actually asked for, so it goes to
     * MediaStore rather than the cache. On API 29+ the row is inserted pending
     * and only published once the pixels are down, which keeps a half-written
     * JPEG out of the gallery if the process dies mid-save.
     *
     * The "this is a 360 photo" marker viewers look for is XMP GPano, which
     * [ExifInterface] cannot write — see [stampSphereMetadata]. Until that lands
     * the file is a perfectly good 2:1 image that viewers will show flat.
     *
     * Blocking I/O and a full-size compress — call off the main thread. Throws
     * [IOException] if MediaStore refuses the insert or the encode fails.
     */
    fun saveSphere(
        context: Context,
        bitmap: Bitmap,
        quality: Int = SPHERE_JPEG_QUALITY,
    ): SavedSphere {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "sphere_%s.jpg".format(timestamp)
        val isScopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (isScopedStorage) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore would not accept $displayName")

        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("Could not open $uri for writing")
            stream.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    throw IOException("Could not encode the stitched sphere")
                }
            }
        } catch (e: Exception) {
            // A pending row nobody publishes is invisible but not free; drop it
            // rather than leaving a zero-byte entry behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }

        if (isScopedStorage) {
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        }
        stampSphereDescription(context, uri)

        return SavedSphere(uri = uri, displayName = displayName)
    }

    /** Marks a saved sphere as this app's output, in EXIF. */
    private fun stampSphereDescription(context: Context, uri: Uri) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                ExifInterface(descriptor.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_SOFTWARE, "PhotoSphere")
                    setAttribute(
                        ExifInterface.TAG_IMAGE_DESCRIPTION,
                        "$ALBUM equirectangular panorama",
                    )
                    saveAttributes()
                }
            }
        } catch (e: Exception) {
            // Metadata is a nice-to-have; never lose the sphere over it.
            Log.w(TAG, "Could not write EXIF for $uri", e)
        }
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
