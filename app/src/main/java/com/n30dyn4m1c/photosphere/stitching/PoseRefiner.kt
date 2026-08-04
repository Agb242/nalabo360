package com.n30dyn4m1c.photosphere.stitching

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.tan
import kotlin.random.Random

/**
 * One decoded frame on its way into refinement: the pixels, the lens model, and
 * the measured pose the sensor reported when it was shot.
 */
internal class DecodedFrame(
    val image: Mat,
    val intrinsics: FrameIntrinsics,
    val sensorBasis: CameraBasis,
)

/**
 * What feature-based pose refinement ended with.
 *
 * [bases] holds one refined camera basis per input frame — the sensor pose for
 * any frame the content did not support — and [gains] the per-frame brightness
 * gains that equalise the overlaps. [matchedEdges] is how many pose-graph edges
 * were actually measured against image content; zero means the sensor did all
 * the work and the output is a plain orientation-driven stitch.
 */
internal data class RefinementResult(
    val bases: List<CameraBasis>,
    val gains: FloatArray,
    val matchedEdges: Int,
)

/**
 * Sharpens the measured poses against the image content.
 *
 * The sensor places every frame to within a degree or two, which is good enough
 * for a panorama but leaves the overlaps soft. This pass measures the residual:
 * frames that overlap are matched on ORB features, a pure-rotation RANSAC
 * recovers the *content-observed* relative rotation, and a Gauss–Seidel solve
 * distributes those measurements over the whole pose graph with the sensor
 * rotations as the starting guess. The result is a small per-frame correction on
 * top of the measured pose — the sensor stays the anchor, and the pixels decide
 * the fine alignment.
 *
 * Nothing here is allowed to make a stitch *worse*: every edge is checked
 * against the sensor-relative rotation it should be near, frames with no
 * reliable matches keep their sensor pose, and if nothing matches at all the
 * whole pass hands the sensor rotations straight back.
 */
internal object PoseRefiner {

    /** Long edge frames are downscaled to before ORB runs. */
    const val FEATURE_DETECT_LONG_EDGE = 512

    /** ORB detector settings: plenty of features, tolerant of small pose error. */
    private const val ORB_MAX_FEATURES = 1500

    /** Ratio test: keep a match only if clearly better than the second-best. */
    private const val RATIO_TEST = 0.8f

    /**
     * Below this many matches an edge is not worth solving for.
     *
     * Deliberately low: the RANSAC result still has to agree with the sensor's
     * relative rotation within [MAX_SENSOR_DEVIATION_DEGREES], so a sparse but
     * honest set of matches from a low-texture scene is accepted where a strict
     * count would hand the whole edge back to the sensor alone.
     */
    const val MIN_MATCHES = 16

    /** Below this many inliers a rotation estimate is not trusted. */
    const val MIN_INLIERS = 12

    /** Inlier agreement for the RANSAC, in degrees. */
    const val RANSAC_THRESHOLD_DEGREES = 2.0

    /** How many neighbours each frame is allowed to measure. */
    const val MAX_EDGES_PER_FRAME = 5

    /**
     * An edge whose rotation lands further than this from the sensor's relative
     * rotation is assumed to be a false match on repetitive texture, not a lens
     * that suddenly moved.
     */
    const val MAX_SENSOR_DEVIATION_DEGREES = 6.0

    /** Gauss–Seidel sweeps over the pose graph. */
    private const val AVERAGE_ITERATIONS = 8

    fun refine(
        frames: List<DecodedFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
        pivotRatio: Double = 0.0,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): RefinementResult {
        val candidateEdges = overlapGraph(frames, horizontalFovDegrees, verticalFovDegrees)

        val orb = ORB.create(
            ORB_MAX_FEATURES, 1.2f, 8, 31, 0, 2, ORB.HARRIS_SCORE, 31, 20,
        )
        val emptyMask = Mat()
        val features = frames.map { extractFeatures(it, orb, emptyMask, pivotRatio) }
        val acceptedEdges = ArrayList<RotationMath.RotationEdge>()

        try {
            val total = candidateEdges.size
            var done = 0
            for ((first, second) in candidateEdges) {
                val matches = match(features[first], features[second])
                if (matches.size >= MIN_MATCHES) {
                    val from = Array(matches.size) { features[first].bearings[matches[it].first] }
                    val to = Array(matches.size) { features[second].bearings[matches[it].second] }
                    val estimate = RotationMath.estimateRotation(
                        from = from,
                        to = to,
                        thresholdRadians = Math.toRadians(RANSAC_THRESHOLD_DEGREES),
                        maxIterations = 300,
                        random = Random(seedFor(first, second)),
                        minInliers = MIN_INLIERS,
                    )
                    if (estimate != null && estimate.inlierCount >= MIN_INLIERS) {
                        val sensorRelative = RotationMath.multiply(
                            RotationMath.transpose(frames[second].sensorBasis.toRotationMatrix()),
                            frames[first].sensorBasis.toRotationMatrix(),
                        )
                        val deviation = Math.toDegrees(
                            RotationMath.angle(estimate.rotation, sensorRelative)
                        )
                        if (deviation <= MAX_SENSOR_DEVIATION_DEGREES) {
                            acceptedEdges += RotationMath.RotationEdge(
                                from = first,
                                to = second,
                                rotation = estimate.rotation,
                                weight = estimate.inlierCount.toDouble(),
                            )
                        }
                    }
                }
                done++
                onProgress(done, total)
            }

            val initial = frames.map { it.sensorBasis.toRotationMatrix() }
            val refined = if (acceptedEdges.isEmpty()) {
                initial.map { CameraBasis.fromRotationMatrix(it) }
            } else {
                RotationMath.averageRotations(initial, acceptedEdges, AVERAGE_ITERATIONS)
                    .map { CameraBasis.fromRotationMatrix(it) }
            }

            val meanLuma = DoubleArray(frames.size) { features[it].meanLuma }
            val gains = ExposureCompensation.solveGains(meanLuma, candidateEdges)

            return RefinementResult(refined, gains, acceptedEdges.size)
        } finally {
            features.forEach { it.descriptors.release() }
            orb.clear()
            emptyMask.release()
        }
    }

    private class FrameFeatures(
        val bearings: List<DoubleArray>,
        val descriptors: Mat,
        val meanLuma: Double,
    )

    /**
     * Detects ORB features on a downscaled copy and converts each keypoint to a
     * unit bearing in the frame's camera space.
     *
     * The keypoint is scaled back to the decoded frame's pixels, un-distorted to
     * the ideal pinhole coordinate (the same lens model the renderer samples
     * through), and projected through the decoded intrinsics — so a bearing is
     * directly comparable to a bearing from any other frame.
     *
     * With a [pivotRatio] the bearing is then re-referred to the pivot: the ray
     * is followed out to the nominal scene distance and looked back along from
     * the axis the user turned about, then rotated back into the camera's own
     * frame. Two frames of the same body-swivel translate relative to each
     * other, and a pure-rotation RANSAC cannot describe that; two *pivot*
     * bearings of the same scene point differ by a rotation alone, which it can.
     */
    private fun extractFeatures(
        frame: DecodedFrame,
        orb: ORB,
        mask: Mat,
        pivotRatio: Double,
    ): FrameFeatures {
        val gray = Mat()
        val small = Mat()
        val keypoints = MatOfKeyPoint()
        val descriptors = Mat()
        try {
            Imgproc.cvtColor(frame.image, gray, Imgproc.COLOR_RGB2GRAY)
            val width = frame.image.cols()
            val height = frame.image.rows()
            val longest = max(width, height)
            val scale = FEATURE_DETECT_LONG_EDGE.toDouble() / longest
            val targetWidth = (width * scale).roundToInt().coerceAtLeast(2)
            val targetHeight = (height * scale).roundToInt().coerceAtLeast(2)
            Imgproc.resize(
                gray, small,
                Size(targetWidth.toDouble(), targetHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            orb.detectAndCompute(small, mask, keypoints, descriptors)

            val scaleX = width.toDouble() / targetWidth
            val scaleY = height.toDouble() / targetHeight
            val intrinsics = frame.intrinsics
            val radial = intrinsics.radial
            val cx = intrinsics.centerXPx
            val cy = intrinsics.centerYPx
            val fx = intrinsics.focalXPx
            val fy = intrinsics.focalYPx

            val basis = frame.sensorBasis
            val bearings = ArrayList<DoubleArray>(keypoints.rows())
            for (kp in keypoints.toArray()) {
                val u = kp.pt.x * scaleX
                val v = kp.pt.y * scaleY
                val ideal = if (radial != null) {
                    undistortPixel(u, v, cx, cy, radial)
                } else {
                    doubleArrayOf(u, v)
                }
                var x = (ideal[0] - cx) / fx
                var y = -(ideal[1] - cy) / fy
                val length = Math.sqrt(x * x + y * y + 1.0)
                x /= length
                y /= length
                val z = 1.0 / length
                bearings += if (pivotRatio > 0.0) {
                    pivotReferredBearing(basis, pivotRatio, x, y, z)
                } else {
                    doubleArrayOf(x, y, z)
                }
            }

            return FrameFeatures(bearings, descriptors, Core.mean(small).`val`[0])
        } finally {
            gray.release()
            small.release()
            keypoints.release()
        }
    }

    /**
     * A camera-frame bearing rewritten as the bearing a camera *at the pivot*
     * would have recorded for the same scene point.
     *
     * Out to the world, out to the scene, back to the pivot, back into the
     * camera's axes. The round trip through the sensor pose is what makes the
     * result comparable between frames: the pivot is common to all of them,
     * so the only thing left between two frames' pivot bearings is rotation.
     */
    private fun pivotReferredBearing(
        basis: CameraBasis,
        pivotRatio: Double,
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray {
        val world = basis.toWorld(x, y, z)
        val fromPivot = pivotDirection(basis, pivotRatio, world[0], world[1], world[2])
        return doubleArrayOf(
            basis.lateralOf(fromPivot[0], fromPivot[1], fromPivot[2]),
            basis.verticalOf(fromPivot[0], fromPivot[1], fromPivot[2]),
            basis.depthOf(fromPivot[0], fromPivot[1], fromPivot[2]),
        )
    }

    /** Descriptor matches between two frames, filtered by the ratio test. */
    private fun match(a: FrameFeatures, b: FrameFeatures): List<Pair<Int, Int>> {
        if (a.bearings.isEmpty() || b.bearings.isEmpty()) return emptyList()
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val knn = ArrayList<MatOfDMatch>()
        try {
            matcher.knnMatch(a.descriptors, b.descriptors, knn, 2)
            val matched = ArrayList<Pair<Int, Int>>()
            for (result in knn) {
                val candidates = result.toArray()
                if (candidates.size >= 2 &&
                    candidates[0].distance < RATIO_TEST * candidates[1].distance
                ) {
                    matched += candidates[0].queryIdx to candidates[0].trainIdx
                }
            }
            return matched
        } finally {
            knn.forEach { it.release() }
            matcher.clear()
        }
    }

    /**
     * The pose graph: pairs of frames whose images genuinely overlap, capped at
     * [MAX_EDGES_PER_FRAME] per frame and ordered by how much they overlap so
     * the strongest edges are measured first.
     *
     * A pair is a candidate only when [angularOverlap] says its aims fall
     * within one field of view of each other on *both* axes — a directional
     * test, rather than a single angular-distance threshold, because a portrait
     * frame is far taller than it is wide: two aims 55° apart overlap
     * vertically but not at all horizontally, and a distance-only threshold
     * would waste a match on them.
     */
    private fun overlapGraph(
        frames: List<DecodedFrame>,
        horizontalFovDegrees: Float,
        verticalFovDegrees: Float,
    ): List<Pair<Int, Int>> {
        val n = frames.size
        val candidates = ArrayList<Triple<Double, Int, Int>>()
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val overlap = angularOverlap(
                    a = frames[i].sensorBasis,
                    b = frames[j].sensorBasis,
                    horizontalFovDegrees = horizontalFovDegrees,
                    verticalFovDegrees = verticalFovDegrees,
                )
                if (overlap != null) candidates += Triple(overlap, i, j)
            }
        }
        candidates.sortBy { it.first }
        val degree = IntArray(n)
        val edges = ArrayList<Pair<Int, Int>>()
        for ((_, i, j) in candidates) {
            if (degree[i] >= MAX_EDGES_PER_FRAME || degree[j] >= MAX_EDGES_PER_FRAME) continue
            edges += i to j
            degree[i]++
            degree[j]++
        }
        return edges
    }

    private fun seedFor(a: Int, b: Int): Long = (a.toLong() * 31 + b.toLong() * 17) and 0x7fffffffL
}

/**
 * How much two aims overlap, or null when they do not.
 *
 * The second camera's aim is projected into the first's camera frame and must
 * land within one field of view on both axes — the condition for two
 * axis-aligned field-of-view windows to intersect. The returned value is the
 * squared angular separation (tangent-space), so a smaller value is a stronger
 * shared view, which is how the pose graph orders its edges.
 */
internal fun angularOverlap(
    a: CameraBasis,
    b: CameraBasis,
    horizontalFovDegrees: Float,
    verticalFovDegrees: Float,
): Double? {
    val lateral = a.lateralOf(b.forwardX, b.forwardY, b.forwardZ)
    val vertical = a.verticalOf(b.forwardX, b.forwardY, b.forwardZ)
    val depth = a.depthOf(b.forwardX, b.forwardY, b.forwardZ)
    if (depth <= MIN_DEPTH) return null
    val horizontalSeparation = abs(lateral / depth)
    val verticalSeparation = abs(vertical / depth)
    if (horizontalSeparation >= tan(Math.toRadians(horizontalFovDegrees.toDouble()))) return null
    if (verticalSeparation >= tan(Math.toRadians(verticalFovDegrees.toDouble()))) return null
    return horizontalSeparation * horizontalSeparation + verticalSeparation * verticalSeparation
}
