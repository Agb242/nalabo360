package com.n30dyn4m1c.photosphere

/**
 * The JVM target only runs the pipeline's unit tests, never ships, so there is
 * no build type to speak of. False keeps the debug affordances off; tests that
 * care exercise both paths explicitly.
 */
actual val isDebugBuild: Boolean
    get() = false

/** No device to identify on the desktop; profiles fall back to their defaults. */
actual val deviceIdentity: Pair<String, String>?
    get() = null

/** The desktop JVM has no per-app heap class; tests pass budgets explicitly. */
actual val heapBudgetMegabytes: Int?
    get() = null
