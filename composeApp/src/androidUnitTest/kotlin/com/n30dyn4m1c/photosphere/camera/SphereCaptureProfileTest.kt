package com.n30dyn4m1c.photosphere.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereCaptureProfileTest {

    @Test
    fun `a focusable lens is captured by tapping to focus`() {
        assertEquals(
            FocusMode.FOCUS_POINT,
            resolveFocusMode(minimumFocusDistance = 0.005f),
        )
        assertEquals(
            FocusMode.FOCUS_POINT,
            resolveFocusMode(minimumFocusDistance = 1f),
        )
    }

    @Test
    fun `an unknown camera falls back to tap to focus`() {
        assertEquals(
            FocusMode.FOCUS_POINT,
            resolveFocusMode(minimumFocusDistance = null),
        )
    }

    @Test
    fun `a fixed focus lens is never asked to auto focus`() {
        assertEquals(
            FocusMode.FIXED_FOCUS,
            resolveFocusMode(minimumFocusDistance = 0f),
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
