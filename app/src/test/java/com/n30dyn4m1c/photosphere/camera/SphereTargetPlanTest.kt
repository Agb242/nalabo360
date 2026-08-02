package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.sensor.OrientationData
import com.n30dyn4m1c.photosphere.sensor.normalizeDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

private const val TOLERANCE = 1e-3f

class SphereTargetPlanTest {

    @Test
    fun `every ring lies inside the plus or minus sixty degree band`() {
        val plan = SphereTargetPlan.create()

        assertTrue(plan.size > 0)
        plan.targets.forEach { target ->
            assertTrue(
                "elevation ${target.elevationDegrees} outside the band",
                abs(target.elevationDegrees) <= 60f + TOLERANCE,
            )
            assertTrue(
                "yaw ${target.yawDegrees} outside [-180, 180)",
                target.yawDegrees >= -180f && target.yawDegrees < 180f,
            )
        }
    }

    @Test
    fun `rings thin out toward the poles`() {
        val plan = SphereTargetPlan.create()
        val perRing = plan.targets.groupingBy { it.elevationDegrees }.eachCount()

        // 30 degrees of yaw at the equator, widened by 1/cos(elevation) above it.
        assertEquals(12, perRing[0f])
        assertEquals(10, perRing[30f])
        assertEquals(10, perRing[-30f])
        assertEquals(6, perRing[60f])
        assertEquals(6, perRing[-60f])
        assertEquals(44, plan.size)
    }

    @Test
    fun `capture starts at the bearing the user is already facing`() {
        val plan = SphereTargetPlan.create(startYawDegrees = -75f)

        assertEquals(-75f, plan[0].yawDegrees, TOLERANCE)
        assertEquals(0f, plan[0].elevationDegrees, TOLERANCE)
    }

    @Test
    fun `alternate rings are swept the other way so the seams meet`() {
        val plan = SphereTargetPlan.create(startYawDegrees = 0f)
        val equator = plan.targets.filter { it.elevationDegrees == 0f }
        val secondRing = plan.targets.filter { it.elevationDegrees == 30f }

        // The equator sweeps clockwise and finishes just short of a full turn...
        assertEquals(30f, equator[1].yawDegrees, TOLERANCE)
        assertEquals(-30f, equator.last().yawDegrees, TOLERANCE)
        // ...and the next ring is walked back the other way, so it picks up near
        // the bearing the equator left off at instead of a turn away from it.
        val handover = normalizeDegrees(secondRing.first().yawDegrees - equator.last().yawDegrees)
        assertTrue("ring hand-over turns $handover degrees", abs(handover) < 45f)
        assertEquals(0f, secondRing.last().yawDegrees, TOLERANCE)
    }

    @Test
    fun `neighbours within a ring stay a constant angular distance apart`() {
        val plan = SphereTargetPlan.create()

        listOf(0f, 30f, 60f).forEach { elevation ->
            val ring = plan.targets.filter { it.elevationDegrees == elevation }
            val separations = ring.indices.map { index ->
                val a = ring[index]
                val b = ring[(index + 1) % ring.size]
                angularDistance(a, b)
            }
            separations.forEach { separation ->
                // The ring counts are integers, so the spacing lands near the
                // 30-degree target rather than exactly on it.
                assertTrue(
                    "elevation $elevation spaced $separation degrees apart",
                    separation in 24f..37f,
                )
            }
        }
    }

    @Test
    fun `a plan can be laid out with custom rings`() {
        val plan = SphereTargetPlan.create(
            ringElevations = listOf(0f),
            equatorSpacingDegrees = 90f,
        )

        assertEquals(4, plan.size)
        assertEquals(listOf(0f, 90f, -180f, -90f), plan.targets.map { it.yawDegrees })
    }

    /** Great-circle angle between two targets, for checking coverage. */
    private fun angularDistance(a: SphereTarget, b: SphereTarget): Float =
        SphereProjection.angularDistanceDegrees(
            OrientationData(yawDegrees = a.yawDegrees, pitchDegrees = a.pitchDegrees),
            b,
        )
}
