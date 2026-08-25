package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.exp
import kotlin.math.ln

/**
 * Per-frame brightness gains that make the overlap regions agree.
 *
 * AE lock makes every frame of a session share one exposure, which removes the
 * big brightness jumps. What is left is the directional lighting AE cannot fix:
 * a frame shot into the sun and the frame beside it recorded against it, or a
 * frame that caught a window when its neighbour caught a shaded wall. Those
 * differences show up as brightness seams inside the blends.
 *
 * The classic remedy (Brown & Lowe's gain compensation) is a least-squares fit
 * of per-image gains against the pairwise differences in their overlapping
 * regions. The graph of which frames overlap comes from the same pose graph
 * pose refinement builds; the per-frame "brightness" used here is the mean
 * luminance over the whole frame, which tracks the overall level a frame was
 * recorded at without projecting pixels between frames.
 *
 * The equation, in log-gain space, is that along an edge (i, j) the gains must
 * equalise the means: `g_i·m_i ≈ g_j·m_j`, or `l_i − l_j = log m_j − log m_i`.
 * The connected graph fixes gains up to a global factor, which is removed by
 * normalising the geometric mean of the gains to 1 so the stitch neither
 * brightens nor darkens the whole sphere.
 */
internal object ExposureCompensation {

    private const val ITERATIONS = 12

    /**
     * How far a single frame's gain may stray from neutral.
     *
     * The whole-frame mean is a coarse stand-in for the brightness of the
     * *shared* region, so a frame that happens to differ from its neighbour for
     * an honest reason — half of it is sky — pulls its own gain in a direction
     * the overlap never asked for. A long chain of those compounds. Clamping
     * keeps the compensation doing what it is for (shaving a seam) and stops it
     * doing what it is not (blowing out a frame).
     *
     * Set as a safety rail rather than as a policy: two-thirds of a stop is
     * more correction than an AE-locked session of one scene has any business
     * needing, so the clamp is inert on a healthy run and only bites when the
     * solve has run away.
     */
    const val MAX_GAIN: Float = 1.6f

    /** The reciprocal of [MAX_GAIN]; a gain is clamped symmetrically in log space. */
    const val MIN_GAIN: Float = 1f / MAX_GAIN

    /** Below this a frame is blank and takes no part in the solve. */
    private const val MIN_USABLE_LUMA = 1e-6

    /**
     * Gains for frames whose mean luminance is [meanLuma], solved over [edges].
     *
     * [edges] lists which frame pairs overlap; the pairs may be given in either
     * order. Frames with a zero or missing mean (a blank frame) get gain 1 and
     * take no part in the solve.
     */
    fun solveGains(meanLuma: DoubleArray, edges: List<Pair<Int, Int>>): FloatArray {
        val size = meanLuma.size
        if (size == 0) return FloatArray(0)

        // Adjacency, held as flat arrays rather than a map of pairs: the solve
        // sweeps every frame's neighbours a dozen times, and a scan of the whole
        // edge list per frame per sweep is quadratic in the session's size for
        // no reason.
        val degree = IntArray(size)
        val usable = BooleanArray(size) { meanLuma[it] > MIN_USABLE_LUMA }
        edges.forEach { (a, b) ->
            if (a in 0 until size && b in 0 until size && a != b && usable[a] && usable[b]) {
                degree[a]++
                degree[b]++
            }
        }

        val offsets = IntArray(size + 1)
        for (i in 0 until size) offsets[i + 1] = offsets[i] + degree[i]
        val neighbours = IntArray(offsets[size])
        val cursor = offsets.copyOf(size)
        edges.forEach { (a, b) ->
            if (a in 0 until size && b in 0 until size && a != b && usable[a] && usable[b]) {
                neighbours[cursor[a]++] = b
                neighbours[cursor[b]++] = a
            }
        }

        val logLuma = DoubleArray(size) { if (usable[it]) ln(meanLuma[it]) else 0.0 }
        val logGain = DoubleArray(size)

        // Gauss-Seidel over the edge equations: each frame's log-gain is the
        // average of what its neighbours imply, which is the normal equation of
        // the least-squares fit and converges in a dozen sweeps for a graph this
        // small. `d_ij = log m_j − log m_i` makes the edge equation
        // `l_i = l_j + d_ij`.
        repeat(ITERATIONS) {
            for (i in 0 until size) {
                val start = offsets[i]
                val end = offsets[i + 1]
                if (end <= start) continue
                var sum = 0.0
                for (slot in start until end) {
                    val j = neighbours[slot]
                    sum += logGain[j] + logLuma[j] - logLuma[i]
                }
                logGain[i] = sum / (end - start)
            }
        }

        // Remove the gauge freedom among the frames the graph actually ties
        // together: the sphere's overall exposure is not ours to change, so the
        // connected gains are scaled to a geometric mean of one. Frames with no
        // overlap partner have no equation to satisfy and stay exactly neutral.
        var logSum = 0.0
        var connectedCount = 0
        for (i in 0 until size) {
            if (offsets[i + 1] > offsets[i]) {
                logSum += logGain[i]
                connectedCount++
            }
        }
        val offset = if (connectedCount > 0) logSum / connectedCount else 0.0

        val maxLog = ln(MAX_GAIN.toDouble())
        return FloatArray(size) { i ->
            if (offsets[i + 1] <= offsets[i]) {
                1f
            } else {
                exp((logGain[i] - offset).coerceIn(-maxLog, maxLog)).toFloat()
            }
        }
    }
}
