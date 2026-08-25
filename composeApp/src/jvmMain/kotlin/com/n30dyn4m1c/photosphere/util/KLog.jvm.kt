package com.n30dyn4m1c.photosphere.util

/** Prints to stdout/stderr in a logcat-like shape so test output stays readable. */
actual object KLog {
    actual fun i(tag: String, message: String) = println("I/$tag: $message")
    actual fun w(tag: String, message: String) = println("W/$tag: $message")
    actual fun w(tag: String, message: String, error: Throwable) {
        System.err.println("W/$tag: $message")
        error.printStackTrace()
    }

    actual fun e(tag: String, message: String) = System.err.println("E/$tag: $message")

    actual fun e(tag: String, message: String, error: Throwable) {
        System.err.println("E/$tag: $message")
        error.printStackTrace()
    }
}
