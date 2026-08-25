package com.n30dyn4m1c.photosphere

/**
 * Whether this build carries debug affordances (the orientation debug screen,
 * verbose stitch diagnostics). Stands in for `BuildConfig.DEBUG`, which does
 * not exist off the JVM: release builds on every platform report false.
 */
expect val isDebugBuild: Boolean

/**
 * The device's manufacturer and model, e.g. `"samsung" to "SM-S918B"` — the
 * key [com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile] tunes against.
 * Null when the host cannot say (tests, desktop).
 */
expect val deviceIdentity: Pair<String, String>?

/**
 * The heap budget the platform grants this process for heavy allocations, in
 * megabytes — on Android the memory class actually in force (`largeHeap`
 * included), null where no such cap exists (iOS, desktop).
 *
 * The stitch sizes its output canvas against it; see
 * [com.n30dyn4m1c.photosphere.camera.SphereDeviceProfile] for the tiers.
 */
expect val heapBudgetMegabytes: Int?
