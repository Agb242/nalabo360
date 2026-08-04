package com.n30dyn4m1c.photosphere.sensor

/**
 * The mean of a run of orientation samples, for a steadier pose at the shutter.
 *
 * The alignment gate only fires after the aim has held on a target for a few
 * hundred milliseconds, which means the pose that finally gets stamped onto the
 * frame is the *last* sensor sample of a deliberately still dwell. Averaging the
 * dwell's samples instead takes out the sample-to-sample sensor jitter, which is
 * exactly the noise that shows up as softness in the stitch overlaps. The mean
 * is equal-weight because the whole window is a single deliberate stop.
 */
internal fun meanOrientation(samples: List<OrientationData>): OrientationData {
    val valid = samples.filter { it.hasFix }
    val reference = valid.lastOrNull() ?: samples.lastOrNull() ?: OrientationData()
    if (valid.isEmpty()) return reference
    return OrientationData(
        yawDegrees = normalizeDegrees(meanAngleDegrees(valid.map { it.yawDegrees })),
        pitchDegrees = meanAngleDegrees(valid.map { it.pitchDegrees }),
        rollDegrees = normalizeDegrees(meanAngleDegrees(valid.map { it.rollDegrees })),
        accuracy = reference.accuracy,
        timestampNanos = reference.timestampNanos,
    )
}

/**
 * The arithmetic mean of angles, unwrapped so a crossing of ±180° does not smear
 * -179° and +179° into 0°.
 *
 * Angles are measured as deviations from the first value, which works for any
 * range — yaw and roll wrap at ±180°, pitch is bounded at ±90° and never wraps.
 */
internal fun meanAngleDegrees(values: List<Float>): Float {
    if (values.isEmpty()) return 0f
    val reference = values.first()
    var sum = 0.0
    values.forEach { value ->
        var deviation = value - reference
        while (deviation > 180f) deviation -= 360f
        while (deviation < -180f) deviation += 360f
        sum += deviation
    }
    return reference + (sum / values.size).toFloat()
}
