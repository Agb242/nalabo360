package com.n30dyn4m1c.photosphere.camera

import com.n30dyn4m1c.photosphere.deviceIdentity
import com.n30dyn4m1c.photosphere.heapBudgetMegabytes
import com.n30dyn4m1c.photosphere.stitching.PivotModel

/**
 * Per-device tuning, decided once per phone.
 *
 * The generic defaults suit a mid-range device; this profile is the single
 * place a specific phone is tuned. It picks which lens to capture with, caps
 * the still-capture size to what the stitch can actually use, sets how many
 * frames each target is burst before the sharpest is kept, and decides how
 * much resolution and sharpening the stitch ends with.
 *
 * The device detection keys off the manufacturer/model pair, so a new flagship
 * is a few lines here rather than a sprinkling of `if (model == …)` across the
 * camera and stitching code.
 */
data class SphereDeviceProfile(
    /**
     * Whether to prefer the back camera with the widest field of view.
     *
     * True trades sharpness for speed — a wide lens shoots the sphere in far
     * fewer frames. False uses the main camera, which is the sharper lens.
     */
    val preferWidestCamera: Boolean,
    /** Long edge the still captures are capped to, in pixels (12 MP 4:3 = 4000). */
    val captureMaxLongEdgePx: Int,
    /** Frames each target is burst before the sharpest is kept. */
    val burstPerTarget: Int,
    /** Long edge each frame is decoded to before stitching. */
    val stitchMaxInputDimension: Int,
    /** Width cap for the finished sphere; the canvas is always half as tall. */
    val stitchMaxOutputWidth: Int,
    /** Unsharp-mask strength on the finished canvas, 0 = off. */
    val unsharpAmount: Float,
    /**
     * How the phone is assumed to be swung around: how far the lens sits from
     * the axis the user turns about, and how far away the scene is taken to be.
     * See [PivotModel] — this is the correction for pivoting around your body
     * rather than around the camera.
     */
    val pivot: PivotModel,
) {
    companion object {
        /**
         * The default: a mid-range phone with one usable back camera. The wider
         * lens is preferred because every extra frame is capture time and
         * sensor drift, and the stitch runs at the compact 1024 → 4096 profile
         * that a mid-range chip and memory envelope can chew through.
         */
        private val DEFAULT = SphereDeviceProfile(
            preferWidestCamera = true,
            captureMaxLongEdgePx = 4000,
            burstPerTarget = 2,
            stitchMaxInputDimension = 1024,
            stitchMaxOutputWidth = 4096,
            unsharpAmount = 0f,
            pivot = PivotModel.HandheldBodySwivel,
        )

        /**
         * Samsung Galaxy S23 / S23+ / S23 Ultra.
         *
         * Tuned for sharpness, because the hardware earns it:
         *
         * - **The 50 MP main is the capture lens** ([preferWidestCamera] is
         *   false). It is the sharpest camera on the phone — better glass, OIS,
         *   and a much larger sensor than the ultrawide — so a sphere on the
         *   main is notably crisper even at the same output size. It costs more
         *   frames (~33 instead of ~11), which is the price of sharpness.
         * - **Stills are capped at the 12 MP binned output**, which writes fast
         *   and is far more than the stitch reads; 50 MP stills would only slow
         *   the burst.
         * - **The stitch runs at 2000 → 6144.** Frames are decoded at a 2000 px
         *   long edge and the sphere is rendered up to 6144 wide (Google's
         *   Photo Sphere standard is 5376), so the main camera's detail reaches
         *   the finished photo instead of being thrown away at 4096.
         * - **Burst three and sharpen lightly.** Three frames per target make a
         *   sharp survivor more likely, and a gentle unsharp mask lifts the
         *   finished canvas without haloing the parts of the sphere that were
         *   never shot.
         */
        private val SAMSUNG_S23 = SphereDeviceProfile(
            preferWidestCamera = false,
            captureMaxLongEdgePx = 4000,
            burstPerTarget = 3,
            stitchMaxInputDimension = 2000,
            stitchMaxOutputWidth = 6144,
            unsharpAmount = 0.3f,
            pivot = PivotModel.HandheldBodySwivel,
        )

        /** The pure decision, testable on any host. */
        fun forDevice(manufacturer: String?, model: String?): SphereDeviceProfile =
            forDevice(manufacturer, model, heapBudgetMegabytes = null)

        /**
         * The pure decision with an explicit heap budget, testable on any host.
         *
         * A full-sphere canvas at width W costs roughly `W²/2 × 3` bytes of
         * output plus `~5.4 × W²/2` bytes of float blend levels — about 105 MB
         * of allocations at the default 4096 — on top of the app's own
         * baseline. A 128 MB-heap Go-edition handset cannot hold that (the
         * observed crash), so the tuned profile's output is capped by what the
         * budget actually affords:
         *
         * - **≥ 384 MB** — the profile as tuned (6144 on a flagship included).
         * - **≥ 224 MB** (`largeHeap` honoured) — capped at 3584; peak stays
         *   comfortably under the ceiling.
         * - **below** (largeHeap ignored) — capped at 2560, which fits even a
         *   strict 128 MB class.
         */
        fun forDevice(
            manufacturer: String?,
            model: String?,
            heapBudgetMegabytes: Int?,
        ): SphereDeviceProfile {
            val base = if (isSamsungGalaxyS23(manufacturer, model)) SAMSUNG_S23 else DEFAULT
            val cap = when {
                heapBudgetMegabytes == null -> return base
                heapBudgetMegabytes >= FULL_STITCH_HEAP_MB -> return base
                heapBudgetMegabytes >= LARGE_HEAP_FLOOR_MB -> MEDIUM_HEAP_OUTPUT_WIDTH
                else -> SMALL_HEAP_OUTPUT_WIDTH
            }
            return if (base.stitchMaxOutputWidth <= cap) base
            else base.copy(stitchMaxOutputWidth = cap)
        }

        /** The profile for the device this process is running on. */
        fun forDevice(): SphereDeviceProfile {
            val identity = deviceIdentity ?: return DEFAULT
            return forDevice(identity.first, identity.second, heapBudgetMegabytes)
        }

        private fun isSamsungGalaxyS23(manufacturer: String?, model: String?): Boolean =
            manufacturer?.equals("samsung", ignoreCase = true) == true &&
                (model?.startsWith("SM-S911") == true ||
                    model?.startsWith("SM-S916") == true ||
                    model?.startsWith("SM-S918") == true)

        /** At or above this budget every tuned profile runs untouched. */
        private const val FULL_STITCH_HEAP_MB = 384

        /**
         * The floor that still counts as "largeHeap honoured": Android's common
         * large-heap class is 256 MB, so anything at or above this keeps a
         * mid-sized canvas rather than dropping to the smallest tier.
         */
        private const val LARGE_HEAP_FLOOR_MB = 224

        /** Output cap when the heap carries a full-size sphere but tightly. */
        private const val MEDIUM_HEAP_OUTPUT_WIDTH = 3584

        /** Output cap that fits a strict 128 MB heap-class device. */
        private const val SMALL_HEAP_OUTPUT_WIDTH = 2560
    }
}
