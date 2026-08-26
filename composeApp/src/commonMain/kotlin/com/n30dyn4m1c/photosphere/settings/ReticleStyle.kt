package com.n30dyn4m1c.photosphere.settings

import com.n30dyn4m1c.photosphere.util.KLog
import okio.FileSystem
import okio.Path
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How the capture reticle is drawn: its colour and how big the ring is.
 *
 * The reticle is the one piece of chrome that has to stay visible over an
 * arbitrary scene — a white ring vanishes against a white summer sky, and a
 * ring sized for a phone screen can be too fine on a small display or too
 * clumsy on a large one. Both are user settings rather than design decisions
 * for exactly that reason.
 *
 * [colorArgb] is packed opaque-ish ARGB (alpha in the top byte); [scale]
 * multiplies the ring's default radius, clamped to [MIN_SCALE]..[MAX_SCALE].
 */
data class ReticleStyle(
    val colorArgb: Long = DEFAULT_COLOR_ARGB,
    val scale: Float = 1f,
) {
    init {
        require(scale in MIN_SCALE..MAX_SCALE) { "scale $scale outside $MIN_SCALE..$MAX_SCALE" }
    }

    companion object {
        /** White at 90% — the original, and still the right default at dusk. */
        const val DEFAULT_COLOR_ARGB = 0xE6FFFFFF

        const val MIN_SCALE = 0.6f
        const val MAX_SCALE = 2f
    }
}

/**
 * The colours the reticle can be set to, in the order the settings screen
 * offers them: the default first, then high-contrast options chosen to hold up
 * against sky, tarmac, foliage and skin — the scenes that actually wash a
 * white ring out.
 */
val ReticleColorChoices: List<Long> = listOf(
    ReticleStyle.DEFAULT_COLOR_ARGB,
    0xFFFFEB3B, // yellow
    0xFF00E5FF, // cyan
    0xFFFF9100, // orange
    0xFF76FF03, // lime
    0xFFFF4081, // pink
    0xFF000000, // black, for shooting into the sun
)

/** In-memory, process-wide current style; screens observe rather than own it. */
object ReticleStyleHub {
    private val _style = MutableStateFlow(ReticleStyle())

    /** The style every capture screen draws with, updated live by settings. */
    val style: StateFlow<ReticleStyle> = _style

    /** Applies [style] everywhere and hands it to [onChanged] for persisting. */
    fun update(style: ReticleStyle, onChanged: (ReticleStyle) -> Unit = {}) {
        _style.value = style
        onChanged(style)
    }
}

/**
 * Loads and saves the reticle style as a two-line file in the app's data
 * directory.
 *
 * A hand-rolled `key=value` file rather than a platform preferences API: the
 * value is two scalars, okio reads it identically on every target, and it
 * round-trips through the JVM test suite with no platform in the loop.
 */
class ReticleSettingsRepository(
    private val directory: Path,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {

    /** The current style, or the default when nothing (or garbage) is stored. */
    fun load(): ReticleStyle {
        val file = directory / FILE_NAME
        if (!fileSystem.exists(file)) return ReticleStyle()
        return runCatching {
            var color = ReticleStyle.DEFAULT_COLOR_ARGB
            var scale = 1f
            fileSystem.read(file) {
                while (!exhausted()) {
                    val line = readUtf8Line() ?: break
                    val value = line.substringAfter('=', "")
                    when (line.substringBefore('=')) {
                        KEY_COLOR -> value.toLongOrNull()?.let { color = it }
                        KEY_SCALE -> value.toFloatOrNull()?.let { scale = it }
                    }
                }
            }
            ReticleStyle(
                colorArgb = color,
                scale = scale.coerceIn(ReticleStyle.MIN_SCALE, ReticleStyle.MAX_SCALE),
            )
        }.getOrElse { error ->
            KLog.w(TAG, "Could not read reticle settings; using defaults", error)
            ReticleStyle()
        }
    }

    /** Persists [style]; a failed write keeps the in-memory value authoritative. */
    fun save(style: ReticleStyle) {
        val file = directory / FILE_NAME
        runCatching {
            fileSystem.createDirectories(directory)
            val temp = directory / (FILE_NAME + ".tmp")
            fileSystem.write(temp) {
                writeUtf8("$KEY_COLOR=${style.colorArgb}\n$KEY_SCALE=${style.scale}\n")
            }
            fileSystem.atomicMove(temp, file)
        }.onFailure { error ->
            KLog.w(TAG, "Could not save reticle settings", error)
        }
    }

    private companion object {
        const val TAG = "ReticleSettings"
        const val FILE_NAME = "reticle_style.ini"
        const val KEY_COLOR = "color"
        const val KEY_SCALE = "scale"
    }
}

/** The per-platform directory that survives reinstalls of the app's own data. */
expect fun appDataDirectory(): Path
