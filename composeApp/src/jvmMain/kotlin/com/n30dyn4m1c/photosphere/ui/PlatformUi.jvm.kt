package com.n30dyn4m1c.photosphere.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path
import java.io.File

/**
 * The desktop host's take on the platform UI surface: no back convention, no
 * permission model, and no photo library to export into.
 *
 * The jvm target exists to compile the shared UI and run the common test suite
 * on machines without Xcode or an Android SDK; these stubs keep that honest by
 * being visibly inert rather than silently pretending.
 */

/** The desktop window close is not a per-screen gesture; nothing to hook. */
@Composable
actual fun BackPressHandler(onBack: () -> Unit) {
    // Intentionally empty — see the expect doc.
}

actual suspend fun decodeSpherePreview(path: Path, maxLongEdge: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        // Desktop hosts have memory to spare, so the maxLongEdge budget is not
        // honoured here; a development preview never ships to a phone.
        runCatching { File(path.toString()).readBytes().decodeToImageBitmap() }.getOrNull()
    }

actual suspend fun exportSphereToGallery(sphere: StitchedSphere): Result<ExportedSphere> =
    Result.failure(IllegalStateException("The desktop host has no photo library to export into"))

actual fun shareSphere(sphere: StitchedSphere, title: String): Boolean = false

/** No runtime permissions on a desktop JVM: the gate passes everything through. */
internal actual fun requiredRuntimePermissions(): List<String> = emptyList()

@Composable
actual fun RequirePermissions(
    permissions: List<String>,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    content()
}
