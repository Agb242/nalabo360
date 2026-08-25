package com.n30dyn4m1c.photosphere.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.n30dyn4m1c.photosphere.storage.StitchedSphere
import okio.Path

/**
 * The handful of UI behaviours that genuinely need the platform: the back
 * gesture, and everything a finished sphere does on its way out of the app.
 *
 * Each has one actual per target; the shared screens stay free of any
 * `android.*` or UIKit import.
 */

/**
 * Invokes [onBack] when the user performs the system back gesture — the button,
 * the edge swipe, or nothing at all where no such convention exists.
 *
 * Compose Multiplatform has no common `BackHandler`; Android routes this to
 * `androidx.activity.compose.BackHandler`, other hosts ignore it (the desktop
 * window close is not a per-screen gesture).
 */
@Composable
expect fun BackPressHandler(onBack: () -> Unit)

/**
 * Decodes the JPEG at [path] down to something a screen can hold — long edge at
 * most [maxLongEdge] — for the result screen's flat preview. Null when the file
 * cannot be read as an image.
 *
 * Runs on a background dispatcher; call from a coroutine, not composition.
 */
expect suspend fun decodeSpherePreview(path: Path, maxLongEdge: Int): ImageBitmap?

/** What a completed gallery export reports. */
data class ExportedSphere(
    /** File name the sphere landed under in the gallery. */
    val displayName: String,
    /** Human-readable location, e.g. `Pictures/PhotoSphere`. */
    val locationLabel: String,
)

/**
 * Copies a finished sphere into the platform's photo library ("Export to
 * gallery"). Fails rather than throwing when the library refuses — the photo
 * itself is intact in the cache either way.
 */
expect suspend fun exportSphereToGallery(sphere: StitchedSphere): Result<ExportedSphere>

/**
 * Hands the sphere to the system share sheet. The JPEG goes out as it is,
 * GPano metadata and all, so a receiving app that understands 360 photos gets
 * one. Returns false when the host has nothing that can receive an image.
 */
expect fun shareSphere(sphere: StitchedSphere, title: String): Boolean
