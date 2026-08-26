package com.n30dyn4m1c.photosphere.settings

import okio.Path
import okio.Path.Companion.toPath

/** The desktop host keeps its settings beside the user's other dotfiles. */
actual fun appDataDirectory(): Path =
    (System.getProperty("user.home") + "/.nalabo360").toPath()
