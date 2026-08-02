package com.n30dyn4m1c.photosphere.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes captured frames to the shared image collection through MediaStore.
 *
 * MediaStore is used on every supported API level, which means no storage
 * permission is needed from API 29 up. On API 26–28 the insert still resolves to
 * a legacy file path, so `WRITE_EXTERNAL_STORAGE` must be granted first (see
 * `MainActivity.REQUIRED_PERMISSIONS`).
 */
object SphereImageStore {

    private const val TAG = "SphereImageStore"
    private const val ALBUM = "PhotoSphere"
    private const val MIME_TYPE = "image/jpeg"

    /** Output options plus the display name they will produce. */
    data class FrameOutputRequest(
        val outputOptions: ImageCapture.OutputFileOptions,
        val displayName: String,
    )

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
