package com.n30dyn4m1c.photosphere.stitching

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the camera was pointing when a frame was shot.
 *
 * Angles follow the conventions the sensor layer publishes: [yawDegrees] is the
 * camera's compass bearing and [pitchDegrees] is **negative above the horizon**.
 * [elevationDegrees] flips the pitch into the sign the geometry here works in,
 * so the rest of this file can read "up is positive" throughout.
 */
data class CameraPose(
    val yawDegrees: Float,
    val pitchDegrees: Float,
    val rollDegrees: Float,
) {
    /** Height above the horizon, positive up. */
    val elevationDegrees: Float get() = -pitchDegrees
}

/**
 * The camera's axes expressed in the world frame (X east, Y north, Z up).
 *
 * This is the same triple `SphereProjection` builds to place markers on the
 * viewfinder, kept in its own type here because the stitcher needs it per frame
 * and evaluates it against millions of directions — the components are held as
 * flat doubles so the projection loop allocates nothing.
 *
 * The three vectors are orthonormal and right-handed: `right × up = forward`.
 */
class CameraBasis private constructor(
    val forwardX: Double,
    val forwardY: Double,
    val forwardZ: Double,
    val rightX: Double,
    val rightY: Double,
    val rightZ: Double,
    val upX: Double,
    val upY: Double,
    val upZ: Double,
) {
    /** Component of a world direction along the optical axis — its depth. */
    fun depthOf(x: Double, y: Double, z: Double): Double =
        x * forwardX + y * forwardY + z * forwardZ

    /** Component across the frame, positive to the right of the image. */
    fun lateralOf(x: Double, y: Double, z: Double): Double =
        x * rightX + y * rightY + z * rightZ

    /** Component up the frame, positive towards the top of the image. */
    fun verticalOf(x: Double, y: Double, z: Double): Double =
        x * upX + y * upY + z * upZ

    /**
     * Turns a direction in the camera's own frame back into a world direction.
     *
     * The inverse of the three projections above. Used to walk a frame's border
     * out onto the sphere when working out which part of the canvas it covers.
     */
    fun toWorld(cameraX: Double, cameraY: Double, cameraZ: Double): DoubleArray = doubleArrayOf(
        cameraX * rightX + cameraY * upX + cameraZ * forwardX,
        cameraX * rightY + cameraY * upY + cameraZ * forwardY,
        cameraX * rightZ + cameraY * upZ + cameraZ * forwardZ,
    )

    /**
     * This basis as a row-major 3×3 rotation matrix mapping camera axes to the
     * world frame. The columns are `[right, up, forward]` — `toWorld` is exactly
     * the matrix times the camera-space vector. Exchanged with pose refinement,
     * which works in rotation matrices.
     */
    fun toRotationMatrix(): DoubleArray = doubleArrayOf(
        rightX, upX, forwardX,
        rightY, upY, forwardY,
        rightZ, upZ, forwardZ,
    )

    /** The [yawDegrees]/[pitchDegrees]/[rollDegrees] this basis was built from. */
    fun toPose(): CameraPose {
        val yaw = Math.toDegrees(atan2(forwardX, forwardY)).toFloat()
        val elevation = Math.toDegrees(asin(forwardZ.coerceIn(-1.0, 1.0))).toFloat()
        // Rebuild the unrolled pair for this yaw/elevation and measure how far
        // the actual up/right pair has rolled about forward.
        val yawR = Math.toRadians(yaw.toDouble())
        val elevationR = Math.toRadians(elevation.toDouble())
        val sinYaw = sin(yawR)
        val cosYaw = cos(yawR)
        val sinElevation = sin(elevationR)
        val up0X = -sinYaw * sinElevation
        val up0Y = -cosYaw * sinElevation
        val up0Z = cos(elevationR)
        val right0X = cosYaw
        val right0Y = -sinYaw
        val roll = Math.toDegrees(
            atan2(
                -(rightX * up0X + rightY * up0Y + rightZ * up0Z),
                upX * up0X + upY * up0Y + upZ * up0Z,
            )
        ).toFloat()
        return CameraPose(yawDegrees = yaw, pitchDegrees = -elevation, rollDegrees = roll)
    }

    companion object {
        /**
         * Builds the basis for [pose].
         *
         * Forward is where the lens points. "Right" is the bearing a quarter
         * turn clockwise from the aim and stays horizontal for an unrolled
         * device; "up" completes the right-handed triple. Roll then turns that
         * pair about the forward axis — both are perpendicular to it, so
         * Rodrigues' formula collapses to a plain rotation within their plane.
         */
        fun of(pose: CameraPose): CameraBasis {
            val yaw = Math.toRadians(pose.yawDegrees.toDouble())
            val elevation = Math.toRadians(pose.elevationDegrees.toDouble())
            val roll = Math.toRadians(pose.rollDegrees.toDouble())

            val sinYaw = sin(yaw)
            val cosYaw = cos(yaw)
            val cosElevation = cos(elevation)

            val forwardX = sinYaw * cosElevation
            val forwardY = cosYaw * cosElevation
            val forwardZ = sin(elevation)

            val right0X = cosYaw
            val right0Y = -sinYaw
            // right0 × forward, with right0.z == 0 folded through.
            val up0X = right0Y * forwardZ
            val up0Y = -right0X * forwardZ
            val up0Z = right0X * forwardY - right0Y * forwardX

            val cosRoll = cos(roll)
            val sinRoll = sin(roll)
            return CameraBasis(
                forwardX = forwardX,
                forwardY = forwardY,
                forwardZ = forwardZ,
                rightX = right0X * cosRoll - up0X * sinRoll,
                rightY = right0Y * cosRoll - up0Y * sinRoll,
                rightZ = -up0Z * sinRoll,
                upX = up0X * cosRoll + right0X * sinRoll,
                upY = up0Y * cosRoll + right0Y * sinRoll,
                upZ = up0Z * cosRoll,
            )
        }

        /**
         * Builds the basis a row-major rotation matrix describes — the inverse
         * of [toRotationMatrix]. [matrix] is expected to be orthonormal and
         * right-handed, as the refinement pipeline produces.
         */
        fun fromRotationMatrix(matrix: DoubleArray): CameraBasis = CameraBasis(
            rightX = matrix[0],
            rightY = matrix[1],
            rightZ = matrix[2],
            upX = matrix[3],
            upY = matrix[4],
            upZ = matrix[5],
            forwardX = matrix[6],
            forwardY = matrix[7],
            forwardZ = matrix[8],
        )
    }
}

/**
 * The pinhole model of one captured frame.
 *
 * Focal length is kept per axis rather than as one number. Both are derived from
 * the same physical sensor so they ought to agree, but the field of view is an
 * estimate read from optics the camera may describe loosely, and splitting the
 * axes guarantees the frame's full width maps to the full horizontal field of
 * view and its full height to the vertical one. A single focal length would let
 * a small inconsistency between the two show up as a systematic gap or overlap
 * between neighbouring frames.
 */
data class FrameIntrinsics(
    val widthPx: Int,
    val heightPx: Int,
    val focalXPx: Double,
    val focalYPx: Double,
    /**
     * Brown-Conrady radial coefficients `[k1, k2, k3]`, already rescaled to this
     * frame's pixel size (see [RadialDistortion.effectiveFor]), or null when the
     * camera does not describe its distortion.
     */
    val radial: DoubleArray? = null,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "frame must have positive extent" }
        require(focalXPx > 0.0 && focalYPx > 0.0) { "focal length must be positive" }
    }

    val centerXPx: Double get() = widthPx / 2.0
    val centerYPx: Double get() = heightPx / 2.0

    companion object {
        /** Intrinsics of a [widthPx] × [heightPx] frame covering the given angles. */
        fun fromFieldOfView(
            widthPx: Int,
            heightPx: Int,
            horizontalFovDegrees: Float,
            verticalFovDegrees: Float,
            radial: DoubleArray? = null,
        ): FrameIntrinsics {
            require(horizontalFovDegrees in 1f..179f) { "horizontal fov: $horizontalFovDegrees" }
            require(verticalFovDegrees in 1f..179f) { "vertical fov: $verticalFovDegrees" }
            return FrameIntrinsics(
                widthPx = widthPx,
                heightPx = heightPx,
                focalXPx = (widthPx / 2.0) / tanHalf(horizontalFovDegrees),
                focalYPx = (heightPx / 2.0) / tanHalf(verticalFovDegrees),
                radial = radial,
            )
        }

        private fun tanHalf(degrees: Float): Double = tan(Math.toRadians(degrees / 2.0))
    }
}

/**
 * The equirectangular canvas: longitude across, latitude down.
 *
 * The canvas is always 2:1, spanning 360° of longitude and 180° of latitude,
 * with pixel centres sampled at the half-pixel. Longitude runs -180°..180° left
 * to right and matches the yaw convention used everywhere else, so a frame shot
 * facing north lands in the middle of the canvas.
 */
object Equirectangular {

    /** Longitude sampled at the centre of column [col]. */
    fun longitudeDegrees(col: Int, width: Int): Double =
        (col + 0.5) / width * 360.0 - 180.0

    /** Latitude sampled at the centre of row [row]. */
    fun latitudeDegrees(row: Int, height: Int): Double =
        90.0 - (row + 0.5) / height * 180.0

    /** Column a longitude falls in. May sit outside `0..width-1` before wrapping. */
    fun columnFor(longitudeDegrees: Double, width: Int): Double =
        (longitudeDegrees + 180.0) / 360.0 * width - 0.5

    /** Row a latitude falls in, clamped to the canvas. */
    fun rowFor(latitudeDegrees: Double, height: Int): Double =
        (90.0 - latitudeDegrees) / 180.0 * height - 0.5

    /** Unit world direction at a longitude/latitude, in the X east, Y north, Z up frame. */
    fun direction(longitudeDegrees: Double, latitudeDegrees: Double): DoubleArray {
        val lon = Math.toRadians(longitudeDegrees)
        val lat = Math.toRadians(latitudeDegrees)
        val cosLat = cos(lat)
        return doubleArrayOf(sin(lon) * cosLat, cos(lon) * cosLat, sin(lat))
    }

    /** Longitude of a world direction, -180°..180°. */
    fun longitudeOf(x: Double, y: Double): Double = Math.toDegrees(atan2(x, y))

    /** Latitude of a *unit* world direction, -90°..90°. */
    fun latitudeOf(z: Double): Double = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
}

/**
 * The block of canvas one frame can contribute to.
 *
 * [startColumn] may be negative and `startColumn + columnSpan` may run past the
 * canvas width: a frame straddling the ±180° seam covers a contiguous band of
 * longitude that is split in the canvas, and it is cheaper to describe that as
 * one unwrapped range than to force every caller to reason about two. Rows never
 * wrap — latitude is clamped at the poles — so [startRow] and [rowSpan] are
 * always within the canvas.
 */
data class CanvasFootprint(
    val startColumn: Int,
    val columnSpan: Int,
    val startRow: Int,
    val rowSpan: Int,
) {
    val isEmpty: Boolean get() = columnSpan <= 0 || rowSpan <= 0

    /** True when the footprint runs off the right edge and resumes on the left. */
    fun wrapsSeam(canvasWidth: Int): Boolean =
        startColumn < 0 || startColumn + columnSpan > canvasWidth
}

/**
 * Works out which part of the canvas a frame can paint.
 *
 * A frame covers a curved quadrilateral on the sphere, so the bounds are found
 * by walking its border rather than by transforming its four corners: at wide
 * fields of view the mid-edge of a frame reaches a higher latitude than either
 * corner does, and a corners-only box would clip the top of every frame.
 *
 * Longitude is unwrapped relative to the frame's own centre, which is what lets
 * a frame spanning the ±180° seam come back as one contiguous range.
 *
 * A frame that contains a pole is the special case: every longitude is inside
 * it, so no bounding range in longitude is meaningful and the footprint opens
 * out to the full width of the canvas.
 */
object FrameFootprint {

    /** Border samples per edge. Enough to catch the bulge at a 70° field of view. */
    private const val SAMPLES_PER_EDGE = 24

    /**
     * Bounds of what [basis]/[intrinsics] can paint on a [canvasWidth] canvas.
     *
     * [marginPx] pads the result, covering the difference between the sampled
     * border and the true one between samples.
     */
    fun compute(
        basis: CameraBasis,
        intrinsics: FrameIntrinsics,
        canvasWidth: Int,
        canvasHeight: Int,
        marginPx: Int = 2,
    ): CanvasFootprint {
        val centreLongitude = Equirectangular.longitudeOf(basis.forwardX, basis.forwardY)

        var minLongitude = Double.MAX_VALUE
        var maxLongitude = -Double.MAX_VALUE
        var minLatitude = Double.MAX_VALUE
        var maxLatitude = -Double.MAX_VALUE

        forEachBorderDirection(intrinsics) { cameraX, cameraY ->
            val world = basis.toWorld(cameraX, cameraY, 1.0)
            val length = length(world)
            val latitude = Equirectangular.latitudeOf(world[2] / length)
            // Unwrap onto the branch nearest the frame centre, so a border point
            // just past +180° reads as slightly more than the centre rather than
            // as nearly a full turn less.
            val longitude = unwrapNear(
                Equirectangular.longitudeOf(world[0], world[1]),
                centreLongitude,
            )

            minLongitude = min(minLongitude, longitude)
            maxLongitude = max(maxLongitude, longitude)
            minLatitude = min(minLatitude, latitude)
            maxLatitude = max(maxLatitude, latitude)
        }

        // A frame looking over a pole sees every longitude; the border walk finds
        // only the longitudes its edges happen to cross, which would cut the
        // frame in half. Test the poles directly instead.
        val coversNorthPole = containsDirection(basis, intrinsics, 0.0, 0.0, 1.0)
        val coversSouthPole = containsDirection(basis, intrinsics, 0.0, 0.0, -1.0)
        if (coversNorthPole) maxLatitude = 90.0
        if (coversSouthPole) minLatitude = -90.0

        val startRow = floorToInt(Equirectangular.rowFor(maxLatitude, canvasHeight)) - marginPx
        val endRow = ceilToInt(Equirectangular.rowFor(minLatitude, canvasHeight)) + marginPx
        val clampedStartRow = startRow.coerceIn(0, canvasHeight)
        val clampedEndRow = endRow.coerceIn(0, canvasHeight)

        if (coversNorthPole || coversSouthPole) {
            return CanvasFootprint(
                startColumn = 0,
                columnSpan = canvasWidth,
                startRow = clampedStartRow,
                rowSpan = clampedEndRow - clampedStartRow,
            )
        }

        val startColumn =
            floorToInt(Equirectangular.columnFor(minLongitude, canvasWidth)) - marginPx
        val endColumn = ceilToInt(Equirectangular.columnFor(maxLongitude, canvasWidth)) + marginPx
        val columnSpan = min(endColumn - startColumn, canvasWidth)

        return CanvasFootprint(
            startColumn = startColumn,
            columnSpan = columnSpan,
            startRow = clampedStartRow,
            rowSpan = clampedEndRow - clampedStartRow,
        )
    }

    /** True when a world direction projects inside the frame. */
    private fun containsDirection(
        basis: CameraBasis,
        intrinsics: FrameIntrinsics,
        x: Double,
        y: Double,
        z: Double,
    ): Boolean = projectDirection(basis, intrinsics, x, y, z) != null

    /** Calls [block] with the camera-frame ray of each sampled border pixel. */
    private fun forEachBorderDirection(
        intrinsics: FrameIntrinsics,
        block: (cameraX: Double, cameraY: Double) -> Unit,
    ) {
        val lastColumn = intrinsics.widthPx - 1.0
        val lastRow = intrinsics.heightPx - 1.0
        val radial = intrinsics.radial

        // The border walked is the captured image's, whose pixels have passed
        // through the lens. Before a pixel can be turned into a ray it has to
        // be un-distorted back to where the pinhole model put it, or a
        // barrel-distorted frame would report a footprint narrower than the
        // true extent it reaches — exactly the kind of cropped edge that
        // becomes a seam gap.
        val border = { column: Double, row: Double ->
            val ideal = if (radial != null) {
                undistortPixel(column, row, intrinsics.centerXPx, intrinsics.centerYPx, radial)
            } else {
                doubleArrayOf(column, row)
            }
            block(cameraXOf(ideal[0], intrinsics), cameraYOf(ideal[1], intrinsics))
        }

        for (step in 0..SAMPLES_PER_EDGE) {
            val fraction = step.toDouble() / SAMPLES_PER_EDGE
            val alongWidth = fraction * lastColumn
            val alongHeight = fraction * lastRow
            border(alongWidth, 0.0)
            border(alongWidth, lastRow)
            border(0.0, alongHeight)
            border(lastColumn, alongHeight)
        }
    }

    private fun cameraXOf(column: Double, intrinsics: FrameIntrinsics): Double =
        (column - intrinsics.centerXPx) / intrinsics.focalXPx

    private fun cameraYOf(row: Double, intrinsics: FrameIntrinsics): Double =
        -(row - intrinsics.centerYPx) / intrinsics.focalYPx

    private fun length(vector: DoubleArray): Double =
        Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])

    private fun floorToInt(value: Double): Int = Math.floor(value).toInt()

    private fun ceilToInt(value: Double): Int = Math.ceil(value).toInt()
}

/**
 * Directions closer to the image plane than this count as behind the camera:
 * their projection races off to infinity and is useless as a pixel position.
 */
internal const val MIN_DEPTH = 1e-6

/**
 * The pixel of a frame that looked in a given world direction, or null when the
 * direction falls outside the frame or behind the camera.
 *
 * This is the whole of the camera model in five lines: rotate into the camera's
 * axes, divide through by depth, scale by the focal length, then push the ideal
 * pixel through the lens's radial distortion to find where it was captured. The
 * renderer inlines it over millions of pixels rather than calling it, so any
 * change here has to be made in both places — which is why the footprint code
 * and the tests share this one, keeping an independent statement of the same
 * geometry.
 *
 * The returned array is `[column, row]` in the frame's pixel coordinates, where
 * rows increase downwards while the camera's "up" axis points the other way.
 */
internal fun projectDirection(
    basis: CameraBasis,
    intrinsics: FrameIntrinsics,
    x: Double,
    y: Double,
    z: Double,
): DoubleArray? {
    val depth = basis.depthOf(x, y, z)
    if (depth <= MIN_DEPTH) return null
    val centreX = intrinsics.centerXPx
    val centreY = intrinsics.centerYPx
    val idealColumn = centreX + intrinsics.focalXPx * basis.lateralOf(x, y, z) / depth
    val idealRow = centreY - intrinsics.focalYPx * basis.verticalOf(x, y, z) / depth

    val radial = intrinsics.radial
    val column: Double
    val row: Double
    if (radial != null) {
        val distorted = distortPixel(idealColumn, idealRow, centreX, centreY, radial)
        column = distorted[0]
        row = distorted[1]
    } else {
        column = idealColumn
        row = idealRow
    }
    if (column < 0.0 || column > intrinsics.widthPx - 1.0) return null
    if (row < 0.0 || row > intrinsics.heightPx - 1.0) return null
    return doubleArrayOf(column, row)
}

/** Shifts [degrees] by whole turns until it lands within 180° of [reference]. */
internal fun unwrapNear(degrees: Double, reference: Double): Double {
    var result = degrees
    while (result - reference > 180.0) result -= 360.0
    while (result - reference < -180.0) result += 360.0
    return result
}

/** Wraps a canvas column onto `0..width-1`, for footprints that cross the seam. */
internal fun wrapColumn(column: Int, width: Int): Int {
    val remainder = column % width
    return if (remainder < 0) remainder + width else remainder
}
