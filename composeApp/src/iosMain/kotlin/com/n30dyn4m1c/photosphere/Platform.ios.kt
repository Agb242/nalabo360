package com.n30dyn4m1c.photosphere

import platform.UIKit.UIDevice

/**
 * iOS half of the platform surface.
 *
 * Kotlin/Native has no BuildConfig equivalent, so debug affordances (the
 * orientation debug toggle, verbose stitch diagnostics) stay off on iOS for
 * now — the flag is wired for a future Gradle-injected constant rather than
 * guessed from runtime signals, because a heuristic that flips on under a
 * debugger would ship those affordances to users.
 */
actual val isDebugBuild: Boolean
    get() = false

/**
 * Every iPhone reports the same maker, so the device profiles key on the
 * hardware description (`"iPhone"`, or an iPad running the app) — which always
 * resolves to the default profile, as intended.
 */
actual val deviceIdentity: Pair<String, String>?
    get() = "apple" to UIDevice.currentDevice.model

/** No JVM-style per-app heap cap on iOS — jetsam works on system pressure. */
actual val heapBudgetMegabytes: Int?
    get() = null
