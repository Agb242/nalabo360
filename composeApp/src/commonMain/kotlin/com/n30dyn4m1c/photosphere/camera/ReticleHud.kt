package com.n30dyn4m1c.photosphere.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.settings.ReticleStyle
import com.n30dyn4m1c.photosphere.ui.theme.SphereAccent
import kotlin.math.min

/** How far the reticle ring's radius reaches out from the centre of the preview. */
const val RETICLE_RADIUS_FRACTION = 0.16f

/**
 * The capture reticle, drawn the same on every platform: a large ring the aim
 * has to hold inside, four ticks reaching outward, and a dwell arc that fills
 * the rim while the aim is held steady.
 *
 * The ring warms from [color] toward [alignedColor] as the target closes,
 * which gives a continuous read on "am I getting closer" instead of a binary
 * that only resolves at the threshold — and once it closes, holding still is
 * what spins the arc around and fires the shutter.
 *
 * [scale] multiplies the ring's default radius (a user setting: daylight and
 * screen size both argue with the default), and the halo around the ring
 * swells with closeness so the ring stays readable over a bright scene.
 */
fun DrawScope.drawAlignmentReticle(
    alignment: AlignmentState,
    color: Color,
    alignedColor: Color,
    scale: Float,
) {
    // Sized to the viewport rather than fixed so the reticle reads as a large
    // thing to aim rather than a fine crosshair to finesse, and stays
    // proportional on any display.
    val radius = min(size.width, size.height) * RETICLE_RADIUS_FRACTION * scale
    val strokeWidth = 3.dp.toPx()
    val tickLength = 10.dp.toPx()

    // Fully warmed at the threshold, cold from ~4x the threshold outward.
    val closeness = if (!alignment.hasDistance) {
        0f
    } else {
        (1f - (alignment.distanceDegrees - AlignmentGate.DEFAULT_THRESHOLD_DEGREES) / 6f)
            .coerceIn(0f, 1f)
    }
    val ringColor = lerp(color, alignedColor, closeness)

    drawCircle(
        color = alignedColor.copy(alpha = 0.10f + 0.18f * closeness),
        radius = radius + 8.dp.toPx(),
        center = center,
        style = Stroke(width = 1.5.dp.toPx()),
    )

    drawCircle(color = ringColor, radius = radius, center = center, style = Stroke(width = strokeWidth))
    drawCircle(color = ringColor, radius = 2.5.dp.toPx(), center = center)

    // Four ticks reaching outward, so the centre stays readable over busy scenes.
    listOf(0f, 90f, 180f, 270f).forEach { angle ->
        rotate(degrees = angle, pivot = center) {
            drawLine(
                color = ringColor,
                start = Offset(center.x, center.y - radius - 2.dp.toPx()),
                end = Offset(center.x, center.y - radius - 2.dp.toPx() - tickLength),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }

    // The dwell arc: while the aim holds inside the threshold, it fills the
    // ring's rim, and the shutter fires the moment it completes. Drawn on top
    // of the ring so the fill and the target are the same circle.
    if (alignment.dwellProgress > 0f) {
        drawArc(
            color = alignedColor,
            startAngle = -90f,
            sweepAngle = 360f * alignment.dwellProgress,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokeWidth * 1.5f, cap = StrokeCap.Round),
        )
    }
}

/**
 * A standalone reticle over any viewfinder.
 *
 * The Android guided screen embeds the same drawing inside its richer
 * [TargetOverlay]; this composable exists for the simpler iOS capture, where
 * the reticle is the only aiming affordance. It observes [style] through the
 * caller so a settings change lands on the next frame.
 */
@Composable
fun ReticleOverlay(
    style: ReticleStyle,
    modifier: Modifier = Modifier,
    alignment: () -> AlignmentState = { AlignmentState() },
) {
    val color = Color(style.colorArgb)
    Canvas(modifier = modifier.fillMaxSize()) {
        drawAlignmentReticle(
            alignment = alignment(),
            color = color,
            alignedColor = SphereAccent,
            scale = style.scale,
        )
    }
}
