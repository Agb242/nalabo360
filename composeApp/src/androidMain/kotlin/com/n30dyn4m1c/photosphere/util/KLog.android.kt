package com.n30dyn4m1c.photosphere.util

import android.util.Log

/** Forwards to logcat, preserving the levels the pipeline logs at. */
actual object KLog {
    // The logcat calls return the entry's id, an Int; the expect declares Unit,
    // so each is wrapped in a body rather than an expression.
    actual fun i(tag: String, message: String) { Log.i(tag, message) }
    actual fun w(tag: String, message: String) { Log.w(tag, message) }
    actual fun w(tag: String, message: String, error: Throwable) { Log.w(tag, message, error) }
    actual fun e(tag: String, message: String) { Log.e(tag, message) }
    actual fun e(tag: String, message: String, error: Throwable) { Log.e(tag, message, error) }
}
