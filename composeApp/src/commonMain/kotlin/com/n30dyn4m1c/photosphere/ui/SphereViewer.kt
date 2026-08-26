package com.n30dyn4m1c.photosphere.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.n30dyn4m1c.photosphere.stitching.RgbImage
import com.n30dyn4m1c.photosphere.stitching.view.SphereProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Long edge the viewer renders at, in pixels.
 *
 * Frames are projected into a plain buffer, so this is the whole cost of a
 * drag frame: at 840 the inner loop touches ~0.6 M pixels — fast enough on a
 * low-RAM phone to keep up with a finger — and the result upscales to the pane
 * without the softness being noticeable in motion.
 */
private const val RENDER_LONG_EDGE_PX = 840

/** One camera pose: where the view points and how wide the lens is. */
private data class ViewPose(val yawDegrees: Float, val pitchDegrees: Float, val fovDegrees: Float)

/**
 * An interactive window into an equirectangular panorama: drag to look around,
 * pinch to zoom.
 *
 * The pane owns no pixels of its own. Every frame it asks [SphereProjector] to
 * re-project the source into a viewport-sized ARGB buffer on a background
 * dispatcher and swaps the resulting bitmap in; [snapshotFlow] plus
 * `collectLatest` means a fast drag simply cancels renders for poses the
 * finger has already moved past, so the view never queues up work behind
 * itself. The last completed frame stays on screen during that, which is what
 * makes the gesture feel continuous rather than flickery.
 *
 * While [source] is null (still decoding) only a spinner shows; the caller
 * decides what an undecodable file looks like.
 */
@Composable
fun SphereViewer(
    source: RgbImage?,
    modifier: Modifier = Modifier,
    initialYawDegrees: Float = 0f,
    initialPitchDegrees: Float = 0f,
) {
    // Plain remember rather than saveable: a pose is not worth surviving a
    // process death, and the sphere itself does not either.
    var yawDegrees by remember { mutableFloatStateOf(initialYawDegrees) }
    var pitchDegrees by remember {
        mutableFloatStateOf(initialPitchDegrees.coerceIn(-SphereProjector.MAX_PITCH_DEGREES, SphereProjector.MAX_PITCH_DEGREES))
    }
    var fovDegrees by remember { mutableFloatStateOf(SphereProjector.DEFAULT_FOV_DEGREES) }

    var paneSize by remember { mutableStateOf(IntSize.Zero) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { paneSize = it }
            .pointerInput(source) {
                if (source == null) return@pointerInput
                // Degrees per dragged pixel scales with the lens: zoomed in,
                // the same swipe turns less of the scene, like a real camera.
                val heightPx = size.height.toFloat().coerceAtLeast(1f)
                detectTransformGestures { _, pan, zoom, _ ->
                    val degreesPerPx = fovDegrees / heightPx
                    yawDegrees = wrapDegrees(yawDegrees - pan.x * degreesPerPx)
                    pitchDegrees = (pitchDegrees + pan.y * degreesPerPx)
                        .coerceIn(-SphereProjector.MAX_PITCH_DEGREES, SphereProjector.MAX_PITCH_DEGREES)
                    fovDegrees = (fovDegrees / zoom)
                        .coerceIn(SphereProjector.MIN_FOV_DEGREES, SphereProjector.MAX_FOV_DEGREES)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val currentFrame = frame
        when {
            currentFrame != null -> Image(
                bitmap = currentFrame,
                contentDescription = Strings.VIEWER_DESCRIPTION,
                modifier = Modifier.fillMaxSize(),
                // Nearest sampling was good enough for the projection; the
                // upscale can be cheap too.
                filterQuality = FilterQuality.Low,
            )

            source != null -> CircularProgressIndicator(color = Color.White)

            else -> Unit // No source yet and nothing to say about it — caller's call.
        }
    }

    LaunchedEffect(source, paneSize) {
        if (source == null || paneSize.width == 0 || paneSize.height == 0) return@LaunchedEffect

        val scale = min(1f, RENDER_LONG_EDGE_PX / max(paneSize.width, paneSize.height).toFloat())
        val renderWidth = (paneSize.width * scale).roundToInt().coerceAtLeast(1)
        val renderHeight = (paneSize.height * scale).roundToInt().coerceAtLeast(1)
        val projector = SphereProjector(source)
        val buffer = IntArray(renderWidth * renderHeight)

        snapshotFlow { ViewPose(yawDegrees, pitchDegrees, fovDegrees) }
            .distinctUntilChanged()
            .collectLatest { pose ->
                withContext(Dispatchers.Default) {
                    projector.render(buffer, renderWidth, renderHeight, pose.yawDegrees, pose.pitchDegrees, pose.fovDegrees)
                    frame = argbBufferToImageBitmap(buffer, renderWidth, renderHeight)
                }
            }
    }
}

/** Wraps any angle into (−180°, 180°] so the yaw never grows unbounded. */
private fun wrapDegrees(degrees: Float): Float {
    val shifted = (degrees + 180f) % 360f
    return if (shifted < 0f) shifted + 360f - 180f else shifted - 180f
}
