package com.n30dyn4m1c.photosphere.storage

import com.n30dyn4m1c.photosphere.util.KLog
import okio.FileSystem
import okio.Path

/**
 * A stitched sphere on its way to the user: a GPano-tagged JPEG in the cache,
 * and the dimensions written into that metadata.
 *
 * Held as a path rather than decoded pixels because it is what both
 * destinations want — the gallery export copies its bytes and the share sheet
 * hands out a reference to it — and because a 4096×2048 bitmap is 32 MB of
 * heap to carry across a screen change for no reason.
 */
data class StitchedSphere(
    val file: Path,
    val width: Int,
    val height: Int,
    /** Debug-only text describing the frames and geometry that made it. */
    val diagnostics: String? = null,
)

/**
 * Cache bookkeeping every platform shares: the finished sphere of the current
 * run lives in the app's cache until the user exports or discards it.
 *
 * Session directories and gallery export are platform jobs (CameraX writes
 * frames; MediaStore and the photo library are where kept spheres go); this is
 * only what the shell around the screens needs to stay correct on all of them.
 */
object SphereCache {

    private const val TAG = "SphereCache"
    private val fileSystem: FileSystem = FileSystem.SYSTEM

    /** Discards a cached sphere the user has finished with. */
    fun deleteCachedSphere(file: Path) {
        runCatching {
            if (fileSystem.exists(file)) {
                fileSystem.delete(file, mustExist = false)
            }
        }.onFailure { error ->
            KLog.w(TAG, "Could not delete cached sphere ${file.name}", error)
        }
    }
}
