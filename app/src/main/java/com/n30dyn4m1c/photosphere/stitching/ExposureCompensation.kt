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
     * Gains for frames whose mean luminance is [meanLuma], solved over [edges].
     *
     * [edges] lists which frame pairs overlap; the pairs may be given in either
     * order. Frames with a zero or missing mean (a blank frame) get gain 1 and
     * take no part in the solve.
     */
    fun solveGains(meanLuma: DoubleArray, edges: List<Pair<Int, Int>>): FloatArray {
        val size = meanLuma.size
        if (size == 0) return FloatArray(0)

        val usable = BooleanArray(size)
        for (i in 0 until size) usable[i] = meanLuma[i] > 1e-6

        // Directed neighbour pairs, so every edge contributes both directions.
        // A frame is "connected" if it shares at least one edge: only connected
        // frames take part in the solve and the gain normalisation.
        val connected = BooleanArray(size)
        val directed = ArrayList<Pair<Int, Int>>()
        edges.forEach { (a, b) ->
            if (a in 0 until size && b in 0 until size && usable[a] && usable[b]) {
                directed += a to b
                directed += b to a
                connected[a] = true
                connected[b] = true
            }
        }
        // d_ij = log m_j - log m_i makes the edge equation l_i = l_j + d_ij.
        val delta = HashMap<Pair<Int, Int>, Double>(directed.size)
        directed.forEach { (from, to) ->
            delta[from to to] = ln(meanLuma[to]) - ln(meanLuma[from])
        }

        val logGain = DoubleArray(size)

        // Gauss-Seidel over the edge equations: each frame's log-gain is the
        // average of what its neighbours imply, which is a linear solve that a
        // dozen sweeps converge for a graph this small.
        repeat(ITERATIONS) {
            for (i in 0 until size) {
                if (!connected[i]) continue
                var sum = 0.0
                var count = 0
                for (edge in directed) {
                    if (edge.first == i) {
                        sum += logGain[edge.second] + delta[edge]!!
                        count++
                    }
                }
                if (count > 0) logGain[i] = sum / count
            }
        }

        // Remove the gauge freedom among the frames the graph actually ties
        // together: the sphere's overall exposure is not ours to change, so the
        // connected gains are scaled to a geometric mean of one. Frames with no
        // overlap partner have no equation to satisfy and stay exactly neutral.
        var logSum = 0.0
        var count = 0
        for (i in 0 until size) {
            if (connected[i]) {
                logSum += logGain[i]
                count++
            }
        }
        val offset = if (count > 0) logSum / count else 0.0

        return FloatArray(size) { i ->
            if (connected[i]) exp(logGain[i] - offset).toFloat() else 1f
        }
    }
}
