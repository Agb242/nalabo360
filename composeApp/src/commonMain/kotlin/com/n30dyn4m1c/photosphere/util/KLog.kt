package com.n30dyn4m1c.photosphere.util

/**
 * The three log levels the pipeline actually uses, routed to the platform's
 * usual sink: logcat on Android, stdout elsewhere. `android.util.Log` is not
 * available from shared code, and stitching diagnostics are worth keeping on
 * every platform.
 */
expect object KLog {
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable)
    fun e(tag: String, message: String)
    fun e(tag: String, message: String, error: Throwable)
}
