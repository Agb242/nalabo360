package com.n30dyn4m1c.photosphere.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.n30dyn4m1c.photosphere.sensor.OrientationData
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

/** How long the marker takes to hand over to the next target. */
private const val FOCUS_TRANSITION_MILLIS = 350

/** Period of the "this one is live" pulse on the active marker. */
private const val PULSE_PERIOD_MILLIS = 1600

/**
 * Colours of the overlay.
 *
 * Fixed rather than themed: the overlay is drawn on top of an arbitrary camera
 * image, so it needs contrast against whatever the user happens to be pointing
 * at rather than agreement with the app's surfaces.
 */
data class TargetOverlayColors(
    val reticle: Color,
    val reticleAligned: Color,
    val active: Color,
    val completed: Color,
    val pending: Color,
    val guide: Color,
) {
    companion object {
        val Default = TargetOverlayColors(
            reticle = Color.White.copy(alpha = 0.9f),
            reticleAligned = Color(0xFF3DDC84),
            active = Color(0xFFFFC24B),
            completed = Color(0xFF3DDC84),
            pending = Color.White.copy(alpha = 0.32f),
            guide = Color.White.copy(alpha = 0.24f),
        )
    }
}

/**
 * The alignment HUD drawn over the viewfinder.
 *
 * A fixed reticle marks the centre of the frame; the sphere's targets are
 * projected onto the preview around it. Aiming is therefore a matter of moving
 * the phone until the two coincide, with no numbers to read.
 *
 * [orientation] and [alignment] are passed as lambdas on purpose. Both change at
 * the sensor's rate — around 50 Hz — and reading them inside the draw lambda
 * confines that to the draw phase, so a moving phone never triggers
 * recomposition.
 */
@Composable
fun TargetOverlay(
    orientation: () -> OrientationData,
    alignment: () -> AlignmentState,
    plan: SphereTargetPlan?,
    activeIndex: Int,
    fieldOfView: FieldOfView,
    modifier: Modifier = Modifier,
    colors: TargetOverlayColors = TargetOverlayColors.Default,
) {
    // Runs on every hand-over, so the incoming marker grows in rather than
    // appearing somewhere new in the same frame the last one turned green.
    val focus = remember { Animatable(0f) }
    LaunchedEffect(activeIndex) {
        focus.snapTo(0f)
        focus.animateTo(1f, animationSpec = tween(FOCUS_TRANSITION_MILLIS, easing = FastOutSlowInEasing))
    }

    val pulse = rememberInfiniteTransition(label = "target-pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(PULSE_PERIOD_MILLIS, easing = LinearEasing)),
        label = "target-pulse-phase",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val currentOrientation = orientation()
        val currentAlignment = alignment()
        val focalPx = SphereProjection.focalLengthPx(size.width, size.height, fieldOfView)
        val targets = plan?.targets.orEmpty()

        drawTargets(
            targets = targets,
            activeIndex = activeIndex,
            // The camera's axes are the same for every marker, so they are built
            // once here rather than rebuilt inside each projection: a full plan
            // is a few hundred markers and this runs at display rate.
            camera = SphereProjection.cameraFrame(currentOrientation),
            focalPx = focalPx,
            fieldOfView = fieldOfView,
            colors = colors,
            focus = focus.value,
            pulse = pulse.value,
        )

        drawReticle(alignment = currentAlignment, colors = colors)

        if (currentAlignment.isCapturing) {
            // A frame of white where the shutter fired: the same cue a viewfinder
            // gives, and the only feedback visible while the preview stalls.
            drawRect(color = Color.White.copy(alpha = 0.18f))
        }
    }
}

/**
 * Where the user has aimed focus, and whether the lens has locked there.
 *
 * Positions are normalised to the preview: [x] runs 0..1 from the left and [y]
 * runs 0..1 from the top, which is what the camera's metering point factory
 * wants. The reticle stays visible after the lock so it is clear what the
 * session is focused on, and a second tap simply moves the lock.
 */
data class FocusReticle(
    /** Horizontal aim within the preview, 0..1 from the left. */
    val x: Float,
    /** Vertical aim within the preview, 0..1 from the top. */
    val y: Float,
    /** A focus sweep is in flight and has not converged yet. */
    val isWorking: Boolean,
    /** The lens has converged and is holding this distance. */
    val isLocked: Boolean,
)

/**
 * The tap-to-focus square drawn where the user aimed focus.
 *
 * A single rounded square with corner brackets, like a phone camera's focus
 * box: warm amber while the sweep runs (growing slightly, so the sweep reads
 * as alive), green once the lens has locked. It is drawn on top of everything
 * else so it never hides behind the target markers.
 */
@Composable
fun FocusReticleOverlay(
    focus: FocusReticle?,
    modifier: Modifier = Modifier,
) {
    if (focus == null) return

    val appear = remember { Animatable(0f) }
    LaunchedEffect(focus.x, focus.y) {
        appear.snapTo(0f)
        appear.animateTo(1f, animationSpec = tween(240, easing = FastOutSlowInEasing))
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val color = when {
            focus.isWorking -> Color(0xFFFFC24B)
            focus.isLocked -> Color(0xFF3DDC84)
            else -> Color.White.copy(alpha = 0.9f)
        }

        val resting = 44.dp.toPx()
        val sizePx = if (focus.isWorking) {
            resting * (1f + 0.35f * (1f - appear.value))
        } else {
            resting
        }
        val half = sizePx / 2f
        val cx = focus.x * size.width
        val cy = focus.y * size.height
        val corner = 14.dp.toPx()
        val strokeWidth = 2.5.dp.toPx()

        // A soft wash over the focused region while the sweep is deciding.
        if (focus.isWorking) {
            drawRect(
                color = color.copy(alpha = 0.10f),
                topLeft = Offset(cx - half, cy - half),
                size = Size(sizePx, sizePx),
            )
        }

        drawFocusCorners(
            cx = cx,
            cy = cy,
            half = half,
            corner = corner,
            color = color,
            strokeWidth = strokeWidth,
        )
    }
}

/** Draws the four corner brackets of the focus square. */
private fun DrawScope.drawFocusCorners(
    cx: Float,
    cy: Float,
    half: Float,
    corner: Float,
    color: Color,
    strokeWidth: Float,
) {
    val path = Path()
    // Top-left.
    path.moveTo(cx - half, cy - half + corner)
    path.lineTo(cx - half, cy - half)
    path.lineTo(cx - half + corner, cy - half)
    // Top-right.
    path.moveTo(cx + half - corner, cy - half)
    path.lineTo(cx + half, cy - half)
    path.lineTo(cx + half, cy - half + corner)
    // Bottom-right.
    path.moveTo(cx + half, cy + half - corner)
    path.lineTo(cx + half, cy + half)
    path.lineTo(cx + half - corner, cy + half)
    // Bottom-left.
    path.moveTo(cx - half + corner, cy + half)
    path.lineTo(cx - half, cy + half)
    path.lineTo(cx - half, cy + half - corner)
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
    )
}

/**
 * Draws every marker: the ones already shot, the ones still to come, and the live one.
 *
 * Each marker is a rectangle the size of one frame's capture footprint, not a
 * dot: a filled one means "this area is already covered", an outlined one is
 * "this is the next space to cover". Because the rectangles show the full
 * sensor field of view they can reach past the edge of the preview — the
 * preview is a crop of the capture, and the overlap between neighbouring
 * rectangles is exactly the overlap between neighbouring shots.
 */
private fun DrawScope.drawTargets(
    targets: List<SphereTarget>,
    activeIndex: Int,
    camera: CameraFrame,
    focalPx: Float,
    fieldOfView: FieldOfView,
    colors: TargetOverlayColors,
    focus: Float,
    pulse: Float,
) {
    if (targets.isEmpty()) return

    val margin = EDGE_MARGIN.toPx()
    val strokeWidth = 1.5.dp.toPx()

    targets.forEachIndexed { index, target ->
        if (index == activeIndex) return@forEachIndexed

        val position = camera.project(target).screenOffset(center, focalPx)
            ?: return@forEachIndexed
        if (!position.isInside(size, margin)) return@forEachIndexed

        val isShot = index < activeIndex
        drawFrameRect(
            target = target,
            camera = camera,
            focalPx = focalPx,
            fieldOfView = fieldOfView,
            focus = 1f,
            color = if (isShot) colors.completed else colors.pending,
            fillAlpha = if (isShot) 0.14f else 0f,
            strokeWidth = strokeWidth,
        )
    }

    val activeTarget = targets.getOrNull(activeIndex) ?: return
    drawActiveTarget(
        target = activeTarget,
        camera = camera,
        colors = colors,
        focalPx = focalPx,
        fieldOfView = fieldOfView,
        focus = focus,
        pulse = pulse,
    )
}

/**
 * Draws the target the user is being sent to.
 *
 * On screen it is a pulsing rectangle the size of the frame that shot will
 * capture, with a guide line back to the reticle; off screen — behind the
 * camera, or past the edge of the preview — it collapses to a chevron on the
 * border pointing the way to turn. Without that fallback the first frame of a
 * new target would simply leave the user with an empty viewfinder and no idea
 * which way to go.
 */
private fun DrawScope.drawActiveTarget(
    target: SphereTarget,
    camera: CameraFrame,
    colors: TargetOverlayColors,
    focalPx: Float,
    fieldOfView: FieldOfView,
    focus: Float,
    pulse: Float,
) {
    val margin = EDGE_MARGIN.toPx()
    val view = camera.project(target)
    val position = view.screenOffset(center, focalPx)

    if (position == null || !position.isInside(size, margin)) {
        drawEdgeChevron(
            direction = view.screenDirection(),
            color = colors.active.copy(alpha = 0.55f + 0.45f * focus),
            margin = margin,
        )
        return
    }

    val strokeWidth = 2.5.dp.toPx()

    // A dashed line from the reticle to the marker: at a glance it reads as
    // "move this way", and it disappears once the two are on top of each other.
    val separation = (position - center).getDistance()
    if (separation > 32.dp.toPx()) {
        drawLine(
            color = colors.guide.copy(alpha = colors.guide.alpha * focus),
            start = center,
            end = position,
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(6.dp.toPx(), 8.dp.toPx()),
            ),
        )
    }

    // Two passes: a soft glowing fill that breathes with the pulse, then the
    // crisp border that carries the colour.
    drawFrameRect(
        target = target,
        camera = camera,
        focalPx = focalPx,
        fieldOfView = fieldOfView,
        focus = focus,
        color = colors.active.copy(alpha = (1f - pulse) * 0.25f * focus),
        fillAlpha = 0.10f,
        strokeWidth = strokeWidth,
    )
    drawFrameRect(
        target = target,
        camera = camera,
        focalPx = focalPx,
        fieldOfView = fieldOfView,
        focus = focus,
        color = colors.active.copy(alpha = focus),
        fillAlpha = 0f,
        strokeWidth = strokeWidth,
    )
    drawCircle(
        color = colors.active.copy(alpha = focus),
        radius = 3.dp.toPx(),
        center = position,
    )
}

/**
 * Draws the rectangle of sky one frame shot at [target] will cover.
 *
 * The corners are the four directions a camera aimed at [target] sees at the
 * edges of its field of view; projecting them to the screen gives the capture
 * footprint. Drawn only when all four project in front of the camera — close to
 * the edge of the preview some fall behind it, and a clipped quadrilateral
 * would mislead more than a missing marker.
 */
private fun DrawScope.drawFrameRect(
    target: SphereTarget,
    camera: CameraFrame,
    focalPx: Float,
    fieldOfView: FieldOfView,
    focus: Float,
    color: Color,
    fillAlpha: Float,
    strokeWidth: Float,
) {
    val projected = frameCorners(target, fieldOfView).mapNotNull { corner ->
        camera.project(corner).screenOffset(center, focalPx)
    }
    if (projected.size < 4) return

    // Grow in from the centre on a hand-over rather than appearing in place.
    val scaled = projected.map { point ->
        if (focus >= 1f) point else center + (point - center) * focus
    }

    val path = Path().apply {
        moveTo(scaled[0].x, scaled[0].y)
        lineTo(scaled[1].x, scaled[1].y)
        lineTo(scaled[3].x, scaled[3].y)
        lineTo(scaled[2].x, scaled[2].y)
        close()
    }
    if (fillAlpha > 0f) drawPath(path, color = color.copy(alpha = fillAlpha))
    drawPath(path, color = color, style = Stroke(width = strokeWidth))
}

/**
 * The four directions that bound a frame shot at [target]: its field of view
 * swung out by half the horizontal angle to either side and half the vertical
 * angle above and below. An approximation near the poles, exact enough for a
 * guide the capture threshold is measured against in degrees.
 */
private fun frameCorners(target: SphereTarget, fieldOfView: FieldOfView): List<SphereTarget> {
    val halfWidth = fieldOfView.horizontalDegrees / 2f
    val halfHeight = fieldOfView.verticalDegrees / 2f
    val elevation = target.elevationDegrees
    return listOf(
        SphereTarget.atElevation(target.yawDegrees - halfWidth, elevation + halfHeight),
        SphereTarget.atElevation(target.yawDegrees + halfWidth, elevation + halfHeight),
        SphereTarget.atElevation(target.yawDegrees - halfWidth, elevation - halfHeight),
        SphereTarget.atElevation(target.yawDegrees + halfWidth, elevation - halfHeight),
    )
}

/**
 * The fixed crosshair, plus the dwell arc that fills while the aim is held.
 *
 * The reticle warms from white toward green as the target closes, which gives
 * the user a continuous read on "am I getting closer" instead of a binary that
 * only resolves at the threshold.
 */
private fun DrawScope.drawReticle(
    alignment: AlignmentState,
    colors: TargetOverlayColors,
) {
    val radius = 26.dp.toPx()
    val strokeWidth = 2.dp.toPx()
    val tickLength = 8.dp.toPx()

    // Fully warmed at the threshold, cold from ~4x the threshold outward.
    val closeness = if (!alignment.hasDistance) {
        0f
    } else {
        (1f - (alignment.distanceDegrees - AlignmentGate.DEFAULT_THRESHOLD_DEGREES) / 6f)
            .coerceIn(0f, 1f)
    }
    val color = lerp(colors.reticle, colors.reticleAligned, closeness)

    drawCircle(color = color, radius = radius, center = center, style = Stroke(width = strokeWidth))
    drawCircle(color = color, radius = 2.dp.toPx(), center = center)

    // Four ticks reaching outward, so the centre stays readable over busy scenes.
    listOf(0f, 90f, 180f, 270f).forEach { angle ->
        rotate(degrees = angle, pivot = center) {
            drawLine(
                color = color,
                start = Offset(center.x, center.y - radius - tickLength),
                end = Offset(center.x, center.y - radius - 2.dp.toPx()),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }

    if (alignment.dwellProgress > 0f) {
        val arcRadius = radius + 7.dp.toPx()
        val arcStroke = 3.dp.toPx()
        drawArc(
            color = colors.reticleAligned,
            startAngle = -90f,
            sweepAngle = 360f * alignment.dwellProgress,
            useCenter = false,
            topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
            size = Size(arcRadius * 2f, arcRadius * 2f),
            style = Stroke(width = arcStroke, cap = StrokeCap.Round),
        )
    }
}

/** Arrow pinned to the border, pointing the shortest way toward an off-screen target. */
private fun DrawScope.drawEdgeChevron(
    direction: Offset,
    color: Color,
    margin: Float,
) {
    val position = edgePosition(direction, margin)
    val length = 14.dp.toPx()
    val angle = Math.toDegrees(atan2(direction.y.toDouble(), direction.x.toDouble())).toFloat()

    rotate(degrees = angle, pivot = position) {
        val path = Path().apply {
            moveTo(position.x + length, position.y)
            lineTo(position.x - length * 0.55f, position.y - length * 0.8f)
            lineTo(position.x - length * 0.55f, position.y + length * 0.8f)
            close()
        }
        drawPath(path = path, color = color)
    }
}

/** Where a ray leaving the centre along [direction] meets the inset viewport border. */
private fun DrawScope.edgePosition(direction: Offset, margin: Float): Offset {
    val halfWidth = (size.width / 2f - margin).coerceAtLeast(1f)
    val halfHeight = (size.height / 2f - margin).coerceAtLeast(1f)
    val scaleX = if (abs(direction.x) < 1e-4f) Float.MAX_VALUE else halfWidth / abs(direction.x)
    val scaleY = if (abs(direction.y) < 1e-4f) Float.MAX_VALUE else halfHeight / abs(direction.y)
    return center + direction * min(scaleX, scaleY)
}

/**
 * Pixel position of a target, or null when it is behind the camera.
 *
 * This is the pinhole divide: a direction's screen offset from the centre is its
 * sideways component over its depth, scaled by the focal length. Y is negated
 * because the camera's up is the screen's down.
 */
private fun TargetView.screenOffset(center: Offset, focalPx: Float): Offset? =
    if (!isInFront) null else Offset(center.x + x / z * focalPx, center.y - y / z * focalPx)

/**
 * Unit vector on screen pointing toward this target.
 *
 * Valid whether or not the target is in front: behind the camera the depth flips
 * the projection, but the sideways components still say which way to turn.
 */
private fun TargetView.screenDirection(): Offset {
    val raw = Offset(x, -y)
    val length = raw.getDistance()
    // Dead ahead or dead behind has no direction to speak of; pick one.
    return if (length < 1e-4f) Offset(1f, 0f) else raw / length
}

private fun Offset.isInside(size: Size, margin: Float): Boolean =
    x >= margin && x <= size.width - margin && y >= margin && y <= size.height - margin

private val EDGE_MARGIN = 28.dp

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1B, widthDp = 360, heightDp = 640)
@Composable
private fun TargetOverlaySearchingPreview() {
    val plan = SphereTargetPlan.create(startYawDegrees = 0f)
    TargetOverlay(
        orientation = { OrientationData(yawDegrees = 14f, pitchDegrees = -6f, rollDegrees = 3f) },
        alignment = { AlignmentState(distanceDegrees = 15.4f) },
        plan = plan,
        activeIndex = 3,
        fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF1B1B1B, widthDp = 360, heightDp = 640)
@Composable
private fun TargetOverlayHoldingPreview() {
    val plan = SphereTargetPlan.create(startYawDegrees = 0f)
    TargetOverlay(
        orientation = { OrientationData(yawDegrees = 89f, pitchDegrees = 0.4f) },
        alignment = { AlignmentState(distanceDegrees = 1.2f, dwellProgress = 0.6f, isAligned = true) },
        plan = plan,
        activeIndex = 3,
        fieldOfView = FieldOfView(horizontalDegrees = 52f, verticalDegrees = 66f),
    )
}
