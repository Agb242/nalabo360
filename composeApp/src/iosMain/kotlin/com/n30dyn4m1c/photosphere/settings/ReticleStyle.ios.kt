package com.n30dyn4m1c.photosphere.settings

import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSHomeDirectory

/**
 * The app sandbox's Documents directory — persistent for as long as the app is
 * installed, which is the contract a user-facing setting implies. Created
 * lazily by the repository on first save.
 */
actual fun appDataDirectory(): Path = (NSHomeDirectory() + "/Documents").toPath()
