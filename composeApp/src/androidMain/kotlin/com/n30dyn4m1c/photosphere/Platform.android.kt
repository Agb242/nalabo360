package com.n30dyn4m1c.photosphere

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.n30dyn4m1c.photosphere.ui.AppContextHolder

/**
 * Generated `BuildConfig.DEBUG` — the same signal the Android-only pipeline
 * read, so debug affordances (verbose stitch logs) track the build type.
 */
actual val isDebugBuild: Boolean
    get() = BuildConfig.DEBUG

/** `Build.MANUFACTURER` / `Build.MODEL` — what device profiles key against. */
actual val deviceIdentity: Pair<String, String>?
    get() = Build.MANUFACTURER to Build.MODEL

/**
 * `ActivityManager.largeMemoryClass` — what `android:largeHeap` buys. With
 * largeHeap in the manifest this is the ceiling the stitch may actually use;
 * a device that ignores the flag (some Go-edition handsets) still reports its
 * small class here, which is exactly the signal to downscale the canvas by.
 */
actual val heapBudgetMegabytes: Int?
    get() {
        val activityManager = AppContextHolder.get()
            .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return activityManager.largeMemoryClass
    }
