package com.n30dyn4m1c.photosphere.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import com.n30dyn4m1c.photosphere.util.KLog
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.jetbrains.skia.Image
import org.jetbrains.skia.toComposeImageBitmap
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSLocale
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Photos.PHAccessLevel
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryAuthorizationStatus
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIImage
import platform.UIKit.UIImageWriteToSavedPhotosAlbum
import platform.UIKit.UIWindow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

private const val TAG = "PlatformUi"

/**
 * The iOS half of [PlatformUi]: no back gesture, a photo-library export, the
 * system share sheet, and Skia-backed preview decoding.
 */

/** The desktop window close has an iOS analogue in nothing; nothing to hook. */
@Composable
actual fun BackPressHandler(onBack: () -> Unit) {
    // Intentionally empty — see the expect doc.
}

actual suspend fun decodeSpherePreview(path: Path, maxLongEdge: Int): ImageBitmap? =
    // Dispatchers.IO lives in coroutines' JVM fragment; Default carries this.
    withContext(Dispatchers.Default) {
        val bytes = FileSystem.SYSTEM.read(path) { readByteArray() }
        // A scaled redraw could honour maxLongEdge here; the preview is one
        // image on screen and iOS evicts decoded bitmaps under pressure, so the
        // straightforward decode is fine for now.
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    }

actual suspend fun exportSphereToGallery(sphere: StitchedSphere): Result<ExportedSphere> =
    withContext(Dispatchers.Default) {
        runCatching {
            val displayName = "sphere_${timestamp()}.jpg"

            // PHAssetChangeRequest works from a file URL, so park the bytes in
            // a temporary file for the duration of the copy.
            val tempPath = (NSTemporaryDirectory() + displayName).toPath()
            FileSystem.SYSTEM.write(tempPath) { write(FileSystem.SYSTEM.read(sphere.file) { readByteArray() }) }

            when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevel.AddOnly)) {
                PHPhotoLibraryAuthorizationStatus.Authorized -> Unit
                PHPhotoLibraryAuthorizationStatus.NotDetermined ->
                    if (!requestAddOnlyAccess()) {
                        throw IllegalStateException("Photo library access denied")
                    }
                // Denied and restricted both mean the library will refuse.
                else -> throw IllegalStateException("Photo library access denied")
            }

            // The legacy UIKit save path: permission was settled above, and a
            // refusal surfaces through the system rather than an exception we
            // could show — the sphere stays safe in the cache either way.
            UIImageWriteToSavedPhotosAlbum(
                /* image = */ UIImage.imageWithContentsOfFile(tempPath.toString()),
                /* completionTarget = */ null,
                /* completionSelector = */ null,
                /* contextInfo = */ null,
            )
            FileSystem.SYSTEM.delete(tempPath, mustExist = false)

            ExportedSphere(
                displayName = displayName,
                locationLabel = "Photos",
            )
        }.onFailure { error ->
            KLog.e(TAG, "Could not export sphere to the photo library", error)
        }
    }

/**
 * Waits for the add-only photo permission. Denied counts as a failure rather
 * than a crash: the sphere is intact in the cache either way.
 */
private suspend fun requestAddOnlyAccess(): Boolean =
    suspendCancellableCoroutine { continuation ->
        PHPhotoLibrary.requestAuthorizationForAccessLevel(
            /* accessLevel = */ PHAccessLevel.AddOnly,
            /* handler = */ { status ->
                continuation.resume(status == PHPhotoLibraryAuthorizationStatus.Authorized)
            },
        )
    }

/**
 * Hands the JPEG to the share sheet; every receiving app understands a file.
 * The sheet must be presented from the main thread, so hop over and report
 * success optimistically — cancelling the sheet is the user's business, not a
 * failure this function could meaningfully return false over.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun shareSphere(sphere: StitchedSphere, title: String): Boolean {
    val url = NSURL.fileURLWithPath(sphere.file.toString())
    dispatch_async(dispatch_get_main_queue()) {
        val root = keyWindow()?.rootViewController ?: return@dispatch_async
        val sheet = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null,
        )
        sheet.title = title
        root.presentViewController(sheet, true, null)
    }
    return true
}

/** The app's key window, wherever scene plumbing left it (iOS 13+ scenes). */
private fun keyWindow(): UIWindow? {
    val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
    return windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
}

/** `yyyyMMdd_HHmmss`, matching the Android gallery names sort-for-sort. */
private fun timestamp(): String {
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyyMMdd_HHmmss"
        locale = NSLocale("en_US_POSIX")
    }
    return formatter.stringFromDate(NSDate())
}
