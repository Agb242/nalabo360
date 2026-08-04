package com.n30dyn4m1c.photosphere.stitching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The rotation algebra behind pose refinement, exercised without OpenCV: this is
 * the part that decides how well the overlaps align, so it is pinned down here.
 */
class RotationMathTest {

    @Test
    fun `rotationBetween maps one unit vector exactly onto another`() {
        val from = doubleArrayOf(1.0, 0.0, 0.0)
        val to = normalized(doubleArrayOf(0.0, 1.0, 1.0))
        val rotation = RotationMath.rotationBetween(from, to)
        assertVector(to, RotationMath.apply(rotation, from))
    }

    @Test
    fun `anti-parallel vectors still produce a valid rotation`() {
        val from = doubleArrayOf(1.0, 0.0, 0.0)
        val to = doubleArrayOf(-1.0, 0.0, 0.0)
        val rotation = RotationMath.rotationBetween(from, to)
        assertVector(to, RotationMath.apply(rotation, from))
    }

    @Test
    fun `a rotation survives a trip through its quaternion`() {
        val axis = normalized(doubleArrayOf(1.0, -2.0, 0.5))
        for (angle in doubleArrayOf(0.1, 0.7, 1.3, 2.9)) {
            val rotation = RotationMath.rotationAboutAxis(axis, angle)
            val rebuilt = RotationMath.quaternionToMatrix(RotationMath.matrixToQuaternion(rotation))
            assertEquals("angle $angle", 0.0, RotationMath.angle(rotation, rebuilt), 1e-6)
        }
    }

    @Test
    fun `procrustes recovers a rotation from noisy correspondences`() {
        val truth = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.5, 0.2, 0.9)), 0.8)
        val from = Array(60) { randomUnit() }
        val to = Array(60) { perturb(RotationMath.apply(truth, from[it]), Math.toRadians(0.3)) }

        val estimate = RotationMath.procrustes(from, to)
        assertTrue(
            "procrustes off by ${Math.toDegrees(RotationMath.angle(estimate, truth))}°",
            RotationMath.angle(estimate, truth) < Math.toRadians(0.2),
        )
    }

    @Test
    fun `a symmetric matrix returns its eigenvalues and vectors in order`() {
        // A symmetric matrix with known eigenvalues 3, 2, 1 along the axes.
        val matrix = doubleArrayOf(
            3.0, 0.0, 0.0,
            0.0, 2.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val (values, vectors) = RotationMath.symmetricEigen3x3(matrix)
        assertEquals(3.0, values[0], 1e-9)
        assertEquals(2.0, values[1], 1e-9)
        assertEquals(1.0, values[2], 1e-9)
        // Eigenvectors are unit and mutually orthogonal.
        assertEquals(1.0, norm(vectors.copyOfRange(0, 3)), 1e-9)
        assertEquals(1.0, norm(vectors.copyOfRange(3, 6)), 1e-9)
        assertEquals(1.0, norm(vectors.copyOfRange(6, 9)), 1e-9)
    }

    @Test
    fun `basisRotation is exact for a pure rotation`() {
        val truth = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.3, 1.0, 0.0)), 0.7)
        val a1 = normalized(doubleArrayOf(1.0, 0.2, 0.1))
        val a2 = normalized(doubleArrayOf(0.1, 1.0, -0.3))
        val b1 = RotationMath.apply(truth, a1)
        val b2 = RotationMath.apply(truth, a2)
        val estimate = RotationMath.basisRotation(a1, b1, a2, b2)
        assertEquals(0.0, RotationMath.angle(estimate, truth), 1e-9)
    }

    @Test
    fun `RANSAC recovers a rotation despite a third of the matches lying`() {
        val truth = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.2, 0.9, 0.3)), 0.5)
        val from = Array(300) { randomUnit() }
        val to = Array(300) { index ->
            if (index % 3 == 0) {
                // Outlier: a completely wrong correspondence.
                randomUnit()
            } else {
                // Inlier with a degree of measurement noise.
                perturb(RotationMath.apply(truth, from[index]), Math.toRadians(0.4))
            }
        }

        val estimate = RotationMath.estimateRotation(
            from = from,
            to = to,
            thresholdRadians = Math.toRadians(1.5),
            maxIterations = 400,
            random = Random(7),
            minInliers = 100,
        )

        assertNotNull("RANSAC should find a consensus", estimate)
        assertTrue("inlier count ${estimate!!.inlierCount}", estimate.inlierCount >= 150)
        assertTrue(
            "recovered rotation off by ${Math.toDegrees(RotationMath.angle(estimate.rotation, truth))}°",
            RotationMath.angle(estimate.rotation, truth) < Math.toRadians(0.6),
        )
    }

    @Test
    fun `rotation averaging recovers the absolute rotations from measured edges`() {
        val count = 8
        val truth = List(count) { index ->
            RotationMath.rotationAboutAxis(normalized(doubleArrayOf(0.1, 1.0, 0.2)), index * 0.5)
        }

        // A ring of relative rotations with small measurement noise — the
        // ~0.2° residual a RANSAC rotation fit leaves behind. The initial poses
        // carry the larger ~1° sensor error the averaging is meant to remove.
        val edges = ArrayList<RotationMath.RotationEdge>()
        for (i in 0 until count) {
            val j = (i + 1) % count
            val trueRelative = RotationMath.multiply(RotationMath.transpose(truth[j]), truth[i])
            val noisy = RotationMath.multiply(
                RotationMath.rotationAboutAxis(randomUnit(), 0.003),
                trueRelative,
            )
            edges += RotationMath.RotationEdge(i, j, noisy, 1.0)
        }

        // The anchor is exact; every other frame is given a sensor-style error.
        val initial = List(count) { index ->
            if (index == 0) truth[0]
            else RotationMath.multiply(
                RotationMath.rotationAboutAxis(randomUnit(), 0.01),
                truth[index],
            )
        }

        val refined = RotationMath.averageRotations(initial, edges, 20)
        for (i in 0 until count) {
            val error = RotationMath.angle(refined[i], truth[i])
            assertTrue("frame $i off by ${Math.toDegrees(error)}°", error < Math.toRadians(0.5))
        }
    }

    @Test
    fun `the weighted mean of identical rotations is that rotation`() {
        val rotation = RotationMath.rotationAboutAxis(normalized(doubleArrayOf(1.0, 0.0, 0.0)), 1.0)
        val mean = RotationMath.weightedMeanRotation(
            listOf(rotation, rotation, rotation),
            listOf(1.0, 2.0, 0.5),
        )
        assertEquals(0.0, RotationMath.angle(mean, rotation), 1e-6)
    }

    private fun randomUnit(): DoubleArray {
        var candidate: DoubleArray
        do {
            candidate = doubleArrayOf(Random.nextDouble() - 0.5, Random.nextDouble() - 0.5, Random.nextDouble() - 0.5)
        } while (norm(candidate) < 1e-6)
        return normalized(candidate)
    }

    /** Rotates [v] by up to [angleRad] about a random axis. */
    private fun perturb(v: DoubleArray, angleRad: Double): DoubleArray =
        RotationMath.apply(RotationMath.rotationAboutAxis(randomUnit(), angleRad), v)

    private fun normalized(v: DoubleArray): DoubleArray {
        val magnitude = norm(v)
        return doubleArrayOf(v[0] / magnitude, v[1] / magnitude, v[2] / magnitude)
    }

    private fun norm(v: DoubleArray): Double = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun assertVector(expected: DoubleArray, actual: DoubleArray) {
        val dot = expected[0] * actual[0] + expected[1] * actual[1] + expected[2] * actual[2]
        assertEquals("angle between them", 0.0, acos(dot.coerceIn(-1.0, 1.0)), 1e-9)
        // Also check it is not the reflection.
        assertEquals(expected[0], actual[0], 1e-6)
        assertEquals(expected[1], actual[1], 1e-6)
        assertEquals(expected[2], actual[2], 1e-6)
    }
}
