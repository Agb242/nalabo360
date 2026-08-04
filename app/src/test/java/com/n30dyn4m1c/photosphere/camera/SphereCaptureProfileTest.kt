package com.n30dyn4m1c.photosphere.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereCaptureProfileTest {

    @Test
    fun `a focusable lens that supports off means fixed infinity`() {
        val modes = intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_OFF,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_AUTO,
        )

        assertEquals(
            FocusMode.FIXED_INFINITY,
            resolveFocusMode(modes, minimumFocusDistance = 0.005f),
        )
    }

    @Test
    fun `a lens that cannot be switched off locks on the first frame`() {
        val modes = intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
        )

        assertEquals(
            FocusMode.LOCK_ON_FIRST,
            resolveFocusMode(modes, minimumFocusDistance = 0.005f),
        )
    }

    @Test
    fun `an unknown camera falls back to lock on first frame`() {
        assertEquals(
            FocusMode.LOCK_ON_FIRST,
            resolveFocusMode(afModes = null, minimumFocusDistance = null),
        )
        assertEquals(
            FocusMode.LOCK_ON_FIRST,
            resolveFocusMode(IntArray(0), minimumFocusDistance = 1f),
        )
    }

    @Test
    fun `a fixed focus lens is never asked to auto focus`() {
        val modes = intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF)

        assertEquals(
            FocusMode.FIXED_FOCUS,
            resolveFocusMode(modes, minimumFocusDistance = 0f),
        )
        assertEquals(
            FocusMode.FIXED_FOCUS,
            resolveFocusMode(null, minimumFocusDistance = 0f),
        )
    }

    @Test
    fun `lock support defaults to true when the camera stays silent`() {
        assertTrue(resolveLockSupport(null))
        assertTrue(resolveLockSupport(true))
        assertFalse(resolveLockSupport(false))
    }

    @Test
    fun `ois is only claimed when the lens actually offers it`() {
        assertTrue(
            resolveOpticalStabilization(
                intArrayOf(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
            )
        )
        assertFalse(
            resolveOpticalStabilization(
                intArrayOf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            )
        )
        assertFalse(resolveOpticalStabilization(null))
    }

    @Test
    fun `the default device profile keeps a sane capture configuration`() {
        val profile = SphereDeviceProfile.forDevice()
        assertTrue(profile.preferWidestCamera)
        assertTrue("capture cap ${profile.captureMaxLongEdgePx}", profile.captureMaxLongEdgePx >= 3000)
        assertTrue("burst ${profile.burstPerTarget}", profile.burstPerTarget >= 2)
    }
}
