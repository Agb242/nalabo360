package com.n30dyn4m1c.photosphere

import kotlin.math.PI

/**
 * java.lang.Math replacements that compile on Kotlin/Native (iOS) as well as
 * the JVM. The original Android-only sources called `Math.toRadians` and
 * `Math.toDegrees` directly; these are the same conversions with names kept
 * close to the originals so the ported call sites stay readable.
 */
internal fun toRadiansSafe(degrees: Double): Double = degrees * PI / 180.0

internal fun toDegreesSafe(radians: Double): Double = radians * 180.0 / PI
