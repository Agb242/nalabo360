package com.n30dyn4m1c.photosphere.settings

import okio.Path
import okio.Path.Companion.toPath
import java.io.File

/** The app's own files directory — survives restarts, wiped on uninstall. */
actual fun appDataDirectory(): Path =
    File(AppContextHolder.get().filesDir, "settings").apply { mkdirs() }.toPath()
