package com.n30dyn4m1c.photosphere.camera

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The heap-budget tiering: the stitch's output canvas has to fit the device's
 * actual memory class, and a 128 MB-heap Go-edition handset was observed dying
 * with an OutOfMemoryError inside the blend planes at the default 4096 width.
 */
class SphereDeviceProfileTest {

    @Test
    fun unknownBudgetKeepsTunedProfiles() {
        assertEquals(4096, SphereDeviceProfile.forDevice(null, null, null).stitchMaxOutputWidth)
        assertEquals(
            6144,
            SphereDeviceProfile.forDevice("samsung", "SM-S918B", null).stitchMaxOutputWidth,
        )
    }

    @Test
    fun generousBudgetRunsProfilesUntouched() {
        assertEquals(4096, SphereDeviceProfile.forDevice(null, null, 384).stitchMaxOutputWidth)
        assertEquals(4096, SphereDeviceProfile.forDevice(null, null, 512).stitchMaxOutputWidth)
        // A flagship's 6144 stays intact only where the budget truly allows it.
        assertEquals(
            6144,
            SphereDeviceProfile.forDevice("samsung", "SM-S918B", 512).stitchMaxOutputWidth,
        )
    }

    @Test
    fun largeHeapBudgetCapsTheCanvasAtMediumWidth() {
        // The common large-heap class is 256 MB — honoured flag, mid-sized canvas.
        assertEquals(3584, SphereDeviceProfile.forDevice(null, null, 256).stitchMaxOutputWidth)
        // Exactly at the floor still counts as "largeHeap honoured".
        assertEquals(3584, SphereDeviceProfile.forDevice(null, null, 224).stitchMaxOutputWidth)
    }

    @Test
    fun smallHeapBudgetDropsToTheNarrowCanvas() {
        // The observed crash device: a strict 128 MB class.
        assertEquals(2560, SphereDeviceProfile.forDevice(null, null, 128).stitchMaxOutputWidth)
        assertEquals(2560, SphereDeviceProfile.forDevice(null, null, 0).stitchMaxOutputWidth)
    }

    @Test
    fun capsOnlyEverShrinkNeverWiden() {
        // A profile already narrower than the cap keeps its own tuning.
        assertEquals(2560, SphereDeviceProfile.forDevice("samsung", "SM-S918B", 128).stitchMaxOutputWidth)
    }
}
