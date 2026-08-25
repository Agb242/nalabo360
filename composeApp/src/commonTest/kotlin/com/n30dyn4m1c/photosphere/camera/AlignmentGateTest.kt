package com.n30dyn4m1c.photosphere.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlignmentGateTest {

    private fun gate() = AlignmentGate(thresholdDegrees = 2f, dwellMillis = 300L)

    @Test
    fun `an aim outside the threshold never fires`() {
        val gate = gate()

        val reading = gate.update(distanceDegrees = 2.5f, nowMillis = 0L)
        assertFalse(reading.isAligned)
        assertFalse(reading.isTriggered)
        assertEquals(0f, reading.dwellProgress, 1e-4f)

        // Even after far longer than the dwell.
        assertFalse(gate.update(2.5f, 5_000L).isTriggered)
    }

    @Test
    fun `a held aim fires once the dwell elapses`() {
        val gate = gate()

        assertFalse(gate.update(1f, 0L).isTriggered)
        assertFalse(gate.update(1f, 150L).isTriggered)
        assertFalse(gate.update(1f, 299L).isTriggered)
        assertTrue(gate.update(1f, 300L).isTriggered)
    }

    @Test
    fun `dwell progress reports how far through the hold the user is`() {
        val gate = gate()

        gate.update(1f, 0L)
        assertEquals(0.5f, gate.update(1f, 150L).dwellProgress, 1e-4f)
        assertEquals(1f, gate.update(1f, 900L).dwellProgress, 1e-4f)
    }

    @Test
    fun `leaving the threshold restarts the hold from zero`() {
        val gate = gate()

        gate.update(1f, 0L)
        gate.update(1f, 250L)
        // A wobble out of the window at 250ms — this is the case the dwell exists
        // for, and it must not be forgiven.
        assertFalse(gate.update(9f, 260L).isAligned)
        assertFalse(gate.update(1f, 270L).isTriggered)
        assertFalse(gate.update(1f, 560L).isTriggered)
        assertTrue(gate.update(1f, 570L).isTriggered)
    }

    @Test
    fun `the trigger is consumed so a steady hand does not fire twice`() {
        val gate = gate()

        gate.update(1f, 0L)
        assertTrue(gate.update(1f, 300L).isTriggered)
        // Still perfectly aligned, but the shutter stays quiet until a fresh
        // dwell has been served.
        assertFalse(gate.update(1f, 310L).isTriggered)
        assertFalse(gate.update(1f, 599L).isTriggered)
        assertTrue(gate.update(1f, 610L).isTriggered)
    }

    @Test
    fun `reset drops a part-served dwell`() {
        val gate = gate()

        gate.update(1f, 0L)
        gate.reset()
        assertFalse(gate.update(1f, 299L).isTriggered)
        assertTrue(gate.update(1f, 600L).isTriggered)
    }

    @Test
    fun `a distance with no fix behind it is not an alignment`() {
        val reading = gate().update(Float.NaN, 0L)

        assertFalse(reading.isAligned)
        assertFalse(reading.isTriggered)
    }

    @Test
    fun `the shipped defaults are the ones the capture flow promises`() {
        assertEquals(2f, AlignmentGate.DEFAULT_THRESHOLD_DEGREES, 1e-4f)
        assertEquals(300L, AlignmentGate.DEFAULT_DWELL_MILLIS)
    }
}
