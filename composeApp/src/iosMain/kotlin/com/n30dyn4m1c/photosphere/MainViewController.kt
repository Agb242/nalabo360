package com.n30dyn4m1c.photosphere

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.n30dyn4m1c.photosphere.ui.PhotoSphereApp
import com.n30dyn4m1c.photosphere.ui.theme.PhotoSphereTheme
import platform.UIKit.UIViewController

/**
 * The iOS entry point, exported to Swift as `IosEntry.createRootViewController()`.
 *
 * Kotlin/Native exposes top-level functions through generated package facades
 * whose names shift with the package layout; a named object keeps the Swift
 * side stable. The host app (see the CI workflow that assembles the unsigned
 * .ipa) makes this the root of its UIWindow — the same role MainActivity plays
 * on Android. Everything below this line is the shared app.
 */
object IosEntry {
    fun createRootViewController(): UIViewController = ComposeUIViewController {
        PhotoSphereTheme {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                PhotoSphereApp()
            }
        }
    }
}
