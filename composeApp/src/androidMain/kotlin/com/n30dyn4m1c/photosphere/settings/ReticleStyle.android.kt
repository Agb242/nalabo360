package com.n30dyn4m1c.photosphere.settings

import com.n30dyn4m1c.photosphere.ui.AppContextHolder
import okio.Path
import okio.Path.Companion.toPath

/** The app's own files directory — survives restarts, wiped on uninstall. */
actual fun appDataDirectory(): Path =
    AppContextHolder.get().filesDir.resolve("settings").absolutePath.toPath()
