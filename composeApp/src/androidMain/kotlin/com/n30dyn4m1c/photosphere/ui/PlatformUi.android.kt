package com.n30dyn4m1c.photosphere.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.n30dyn4m1c.photosphere.storage.MediaExporter
import com.n30dyn4m1c.photosphere.storage.SphereImageStore
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import com.n30dyn4m1c.photosphere.stitching.sampleSizeFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import java.io.File

/**
 * The Android half of [PlatformUi]: system back, gallery export, the share
 * sheet, and downscaled preview decoding.
 */

private const val TAG = "PlatformUi"

/**
 * The application context, parked where code without a composition can reach
 * it.
 *
 * Gallery export and sharing are fired from coroutine bodies rather than
 * composables, so there is no `LocalContext` to read there; a singleton beats
 * threading a `Context` through every expect signature on every platform that
 * has no such type. Initialised in [com.n30dyn4m1c.photosphere.MainActivity]
 * before any UI exists.
 */
internal object AppContextHolder {

    @Volatile
    private var context: Context? = null

    /** Called once from `onCreate`, before any screen can need it. */
    fun init(context: Context) {
        this.context = context.applicationContext
    }

    /**
     * The stored application context.
     *
     * @throws IllegalStateException if accessed before [init] — which would mean
     *   UI code is running outside the activity that was supposed to set it up.
     */
    fun get(): Context =
        context ?: throw IllegalStateException(
            "AppContextHolder.init() has not run yet; no UI call should be possible before onCreate"
        )
}

/** Routes to the activity library's handler; nothing else to bridge. */
@Composable
actual fun BackPressHandler(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
}

actual suspend fun decodeSpherePreview(path: Path, maxLongEdge: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val file = File(path.toString())

        // A 4096×2048 sphere is 32 MB decoded, on a screen a tenth of that wide,
        // so decode with a power-of-two sample size chosen off the bounds probe.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxLongEdge)
        }
        BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
    }

/** The framework bitmap factory ingests ARGB ints directly and copies them. */
actual fun argbBufferToImageBitmap(buffer: IntArray, width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(buffer, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

actual suspend fun exportSphereToGallery(sphere: StitchedSphere): Result<ExportedSphere> =
    MediaExporter.export(
        context = AppContextHolder.get(),
        source = File(sphere.file.toString()),
        width = sphere.width,
        height = sphere.height,
    ).map { exported ->
        ExportedSphere(
            displayName = exported.displayName,
            locationLabel = exported.relativePath,
        )
    }

actual fun shareSphere(sphere: StitchedSphere, title: String): Boolean {
    val context = AppContextHolder.get()
    val file = File(sphere.file.toString())

    // The cache directory is private, so the JPEG travels as a FileProvider URI;
    // a file:// URI would trip FileUriExposedException on anything since API 24.
    val uri = try {
        SphereImageStore.shareUri(context, file)
    } catch (e: IllegalArgumentException) {
        // Thrown when the file sits outside every path in file_paths.xml.
        Log.e(TAG, "No FileProvider path covers ${file.name}", e)
        return false
    }

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        // Some targets read the grant off the ClipData rather than the extra.
        clipData = ClipData.newRawUri(null, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    return try {
        context.startActivity(
            Intent.createChooser(send, title)
                // Started from a Context that may not be an Activity.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "Nothing on this device can receive an image", e)
        false
    }
}
