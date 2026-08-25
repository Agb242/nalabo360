package com.n30dyn4m1c.photosphere.util

import platform.Foundation.NSLog

/** Forwards to the system log, preserving the levels the pipeline logs at. */
actual object KLog {

    // The message goes through as an argument, never as the format string: a
    // diagnostic containing a stray `%` would otherwise be read as a specifier.
    actual fun i(tag: String, message: String) = log("I", tag, message)

    actual fun w(tag: String, message: String) = log("W", tag, message)

    actual fun w(tag: String, message: String, error: Throwable) =
        log("W", tag, "$message — $error")

    actual fun e(tag: String, message: String) = log("E", tag, message)

    actual fun e(tag: String, message: String, error: Throwable) =
        log("E", tag, "$message — $error")

    private fun log(level: String, tag: String, message: String) {
        NSLog("%@[Nalabo360/%@] %@", level, tag, message)
    }
}
