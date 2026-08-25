package com.n30dyn4m1c.photosphere.metadata

import okio.BufferedSink
import okio.BufferedSource
import okio.Buffer
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.math.roundToInt

/**
 * The Google Photo Sphere (GPano) properties that mark a JPEG as a 360° photo.
 *
 * Every length is in pixels of the image the metadata is attached to. The
 * `CroppedArea*` group describes which part of the full sphere the pixels
 * actually cover: for a full equirectangular frame it is the whole image, which
 * is what [forFullPano] produces. A partial capture keeps the full-pano
 * dimensions at the size the *complete* sphere would have been and places the
 * covered rectangle inside it — [forSphereRegion] expresses a ring capture that
 * way, so a viewer renders the band the frames actually cover and limits
 * panning to it instead of showing the black wedges where nothing was shot.
 */
data class GPanoMetadata(
    /** Width the complete sphere occupies. */
    val fullPanoWidthPixels: Int,
    /** Height the complete sphere occupies; half [fullPanoWidthPixels] at 2:1. */
    val fullPanoHeightPixels: Int,
    /** Width of the region of the sphere this image holds. */
    val croppedAreaImageWidthPixels: Int,
    /** Height of the region of the sphere this image holds. */
    val croppedAreaImageHeightPixels: Int,
    /** Left edge of that region within the full sphere. */
    val croppedAreaLeftPixels: Int,
    /** Top edge of that region within the full sphere. */
    val croppedAreaTopPixels: Int,
    /** Projection the pixels are in. Viewers only understand [PROJECTION_EQUIRECTANGULAR]. */
    val projectionType: String = PROJECTION_EQUIRECTANGULAR,
    /** Whether viewers should open this in a pannable sphere viewer. */
    val usePanoramaViewer: Boolean = true,
) {
    init {
        require(fullPanoWidthPixels > 0 && fullPanoHeightPixels > 0) {
            "full pano must have area, was ${fullPanoWidthPixels}x$fullPanoHeightPixels"
        }
        require(croppedAreaImageWidthPixels > 0 && croppedAreaImageHeightPixels > 0) {
            "cropped area must have area, was " +
                "${croppedAreaImageWidthPixels}x$croppedAreaImageHeightPixels"
        }
        require(croppedAreaLeftPixels >= 0 && croppedAreaTopPixels >= 0) {
            "cropped area origin must be inside the sphere, was " +
                "($croppedAreaLeftPixels, $croppedAreaTopPixels)"
        }
        require(croppedAreaLeftPixels + croppedAreaImageWidthPixels <= fullPanoWidthPixels) {
            "cropped area runs past the right edge of the sphere"
        }
        require(croppedAreaTopPixels + croppedAreaImageHeightPixels <= fullPanoHeightPixels) {
            "cropped area runs past the bottom edge of the sphere"
        }
        require(projectionType.isNotBlank()) { "projectionType must not be blank" }
    }

    companion object {
        /** The only projection Google Photos, Facebook and friends will render. */
        const val PROJECTION_EQUIRECTANGULAR = "equirectangular"

        /**
         * Metadata for an image that *is* the whole sphere: the cropped area
         * covers all of it, anchored at the origin.
         */
        fun forFullPano(imageWidth: Int, imageHeight: Int): GPanoMetadata = GPanoMetadata(
            fullPanoWidthPixels = imageWidth,
            fullPanoHeightPixels = imageHeight,
            croppedAreaImageWidthPixels = imageWidth,
            croppedAreaImageHeightPixels = imageHeight,
            croppedAreaLeftPixels = 0,
            croppedAreaTopPixels = 0,
        )

        /**
         * Metadata for an image that holds a *region* of the sphere, mapped
         * equirectangularly.
         *
         * The image covers [longitudeSpanDegrees] of longitude centred on
         * [centerLongitudeDegrees] and [latitudeSpanDegrees] of latitude centred
         * on [centerLatitudeDegrees]. A viewer maps the full-pano dimensions to
         * the whole 360°×180° sphere and renders the cropped area inside it, so
         * a ring capture — one band all the way around the horizon — is expressed
         * as the matching horizontal slice of a full sphere, and panning is
         * limited to the band the frames actually covered. A full sphere passed
         * through here resolves to the same values as [forFullPano].
         */
        fun forSphereRegion(
            imageWidth: Int,
            imageHeight: Int,
            longitudeSpanDegrees: Float,
            centerLongitudeDegrees: Float,
            latitudeSpanDegrees: Float,
            centerLatitudeDegrees: Float,
        ): GPanoMetadata {
            require(longitudeSpanDegrees in 1f..360f) { "longitude span: $longitudeSpanDegrees" }
            require(latitudeSpanDegrees in 1f..180f) { "latitude span: $latitudeSpanDegrees" }
            val fullWidth = (imageWidth * 360f / longitudeSpanDegrees).roundToInt()
            val fullHeight = (imageHeight * 180f / latitudeSpanDegrees).roundToInt()
            val left = ((centerLongitudeDegrees + 180f) / 360f * fullWidth).roundToInt() -
                imageWidth / 2
            val top = ((90f - centerLatitudeDegrees - latitudeSpanDegrees / 2f) / 180f * fullHeight)
                .roundToInt()
            return GPanoMetadata(
                fullPanoWidthPixels = fullWidth,
                fullPanoHeightPixels = fullHeight,
                croppedAreaImageWidthPixels = imageWidth,
                croppedAreaImageHeightPixels = imageHeight,
                croppedAreaLeftPixels = left.coerceAtLeast(0),
                croppedAreaTopPixels = top.coerceAtLeast(0),
            )
        }
    }
}

/**
 * Writes GPano XMP into a stitched equirectangular JPEG.
 *
 * Without this the output is just a wide picture: what makes a viewer open a
 * file as a pannable 360° photo is an XMP packet in the `GPano` namespace, and
 * neither platform's encoder can put one there.
 *
 * **The pixels are never touched.** The JPEG is not decoded and not re-encoded —
 * this walks the file's marker segments, drops any XMP packet already present,
 * splices in a new `APP1` segment, and copies every remaining byte (including
 * the whole entropy-coded scan) through verbatim. Re-encoding to add metadata
 * would cost a generation of JPEG loss on an image that has already been through
 * one on the way in.
 *
 * ### Where the segment goes
 *
 * A JPEG is `SOI` followed by marker segments; the metadata ones (`APPn`) sit at
 * the front, before the quantisation tables and the scan. The new packet is
 * placed after the last `APP0`/`APP1` — that is, after JFIF and after EXIF if
 * they are there — which is where readers expect to find it, and before anything
 * heavier such as an ICC profile.
 *
 * Everything here is okio buffers, so it is unit-testable off-device and runs
 * identically on every target; the file overload streams through a temporary so
 * no whole panorama is ever held in memory twice.
 */
object GPanoXmpInjector {

    /** The Photo Sphere namespace itself. */
    const val GPANO_NAMESPACE = "http://ns.google.com/photos/1.0/panorama/"

    private const val RDF_NAMESPACE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    /** Identifies an `APP1` segment as carrying XMP rather than EXIF. */
    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/"

    /**
     * Second half of a packet too large for one segment. Never written here —
     * the GPano packet is a few hundred bytes — but recognised so that replacing
     * someone else's XMP cannot leave an orphaned continuation behind.
     */
    private const val XMP_EXTENSION_NAMESPACE = "http://ns.adobe.com/xmp/extension/"

    /** Tool marker written into the packet, for anyone inspecting the file later. */
    private const val TOOLKIT = "Nalabo360"

    private val XMP_SIGNATURE = "$XMP_NAMESPACE\u0000".toByteArray(Charsets.US_ASCII)
    private val XMP_EXTENSION_SIGNATURE =
        "$XMP_EXTENSION_NAMESPACE\u0000".toByteArray(Charsets.US_ASCII)

    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_SOI = 0xD8
    private const val MARKER_APP0 = 0xE0
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP15 = 0xEF
    private const val MARKER_COM = 0xFE

    /** The two bytes of a segment's length field, which the field itself counts. */
    private const val SEGMENT_LENGTH_BYTES = 2

    /** Largest value that field can hold, and so the largest segment. */
    private const val MAX_SEGMENT_LENGTH = 0xFFFF

    /** Suffix of the file written beside the target while it is being rebuilt. */
    private const val TEMP_SUFFIX = ".gpano.tmp"

    private val fileSystem: FileSystem = FileSystem.SYSTEM

    /**
     * Injects full-sphere GPano metadata into [file], in place.
     *
     * [imageWidth] and [imageHeight] must be the JPEG's real pixel dimensions;
     * they are what a viewer uses to map pixels onto the sphere, so a wrong value
     * shows up as a panorama that does not close or that is squashed at the
     * poles.
     *
     * Blocking I/O over the whole file — call off the main thread. Failures come
     * back as a failed [Result] rather than an exception: a sphere whose metadata
     * could not be written is still a perfectly good image, and losing it over a
     * header would be the worse outcome.
     */
    fun inject(file: Path, imageWidth: Int, imageHeight: Int): Result<Path> =
        inject(file, GPanoMetadata.forFullPano(imageWidth, imageHeight))

    /** Injects [metadata] into [file], in place. See the overload above. */
    fun inject(file: Path, metadata: GPanoMetadata): Result<Path> = runCatching {
        val parent = file.parent ?: ".".toPath()
        val temp = parent / (file.name + TEMP_SUFFIX)
        try {
            // okio's read/write hand their streams over as lambda *receivers*
            // (BufferedSource.() -> T), so the nested pair reaches both sides
            // of [inject] through `this` (the source) and `this@write` (the
            // sink) rather than named parameters.
            fileSystem.write(temp) {
                fileSystem.read(file) {
                    inject(this, this@write, metadata)
                }
            }
            // Atomic replace: the original stays fully readable until this line,
            // and there is never a window where neither copy exists.
            fileSystem.atomicMove(temp, file)
            file
        } finally {
            fileSystem.delete(temp, mustExist = false)
        }
    }

    /**
     * Copies the JPEG bytes with [metadata] spliced into them. Convenience for
     * tests and in-memory callers; see the buffered overload for the real walk.
     */
    fun inject(jpeg: ByteArray, metadata: GPanoMetadata): ByteArray {
        val source = Buffer().write(jpeg)
        val sink = Buffer()
        inject(source, sink, metadata)
        return sink.readByteArray()
    }

    /**
     * Copies the JPEG on [source] to [sink] with [metadata] spliced into it.
     *
     * Neither buffer is closed. Throws [IOException] if [source] is not a JPEG,
     * ends mid-segment, or the packet will not fit in one `APP1` segment.
     */
    fun inject(source: BufferedSource, sink: BufferedSink, metadata: GPanoMetadata) {
        val packet = buildXmpPacket(metadata).toByteArray(Charsets.UTF_8)
        val segmentLength = SEGMENT_LENGTH_BYTES + XMP_SIGNATURE.size + packet.size
        if (segmentLength > MAX_SEGMENT_LENGTH) {
            throw IOException("XMP packet is $segmentLength bytes, too large for one segment")
        }

        // `readByte` yields a signed byte, so 0xFF arrives as -1: mask before
        // comparing against the unsigned marker constants.
        if (source.readByte().toInt() and 0xff != MARKER_PREFIX ||
            source.readByte().toInt() and 0xff != MARKER_SOI
        ) {
            throw IOException("Not a JPEG: the file does not start with SOI")
        }
        sink.writeByte(MARKER_PREFIX)
        sink.writeByte(MARKER_SOI)

        val (leading, nextMarkerAt) = readLeadingSegments(source)

        // After JFIF and EXIF, before an ICC profile or anything else.
        val insertAt = leading.indexOfLast {
            it.marker == MARKER_APP0 || it.marker == MARKER_APP1
        } + 1

        leading.take(insertAt).forEach { it.writeTo(sink) }
        writeXmpSegment(sink, packet)
        leading.drop(insertAt).forEach { it.writeTo(sink) }

        // From the first non-metadata marker on, the file is copied byte for
        // byte: tables, frame header, and the entropy-coded scan untouched. One
        // marker prefix stands before the marker itself, however many padded the
        // input — the same collapse the stream version always performed.
        sink.writeByte(MARKER_PREFIX)
        sink.writeByte(nextMarkerAt)
        source.readAll(sink)
    }

    /**
     * The XMP packet, as the text that goes into the segment.
     *
     * `UsePanoramaViewer` is the switch a viewer actually reads; the rest tells
     * it how to wrap the pixels around the sphere.
     */
    fun buildXmpPacket(metadata: GPanoMetadata): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"$TOOLKIT\">\n")
        append("  <rdf:RDF xmlns:rdf=\"$RDF_NAMESPACE\">\n")
        append("    <rdf:Description rdf:about=\"\"\n")
        append("        xmlns:GPano=\"$GPANO_NAMESPACE\"\n")
        append("        GPano:UsePanoramaViewer=\"${metadata.usePanoramaViewer.asXmpBoolean()}\"\n")
        append("        GPano:ProjectionType=\"${metadata.projectionType.escapeXmlAttribute()}\"\n")
        append("        GPano:FullPanoWidthPixels=\"${metadata.fullPanoWidthPixels}\"\n")
        append("        GPano:FullPanoHeightPixels=\"${metadata.fullPanoHeightPixels}\"\n")
        append(
            "        GPano:CroppedAreaImageWidthPixels=" +
                "\"${metadata.croppedAreaImageWidthPixels}\"\n"
        )
        append(
            "        GPano:CroppedAreaImageHeightPixels=" +
                "\"${metadata.croppedAreaImageHeightPixels}\"\n"
        )
        append("        GPano:CroppedAreaLeftPixels=\"${metadata.croppedAreaLeftPixels}\"\n")
        append("        GPano:CroppedAreaTopPixels=\"${metadata.croppedAreaTopPixels}\"/>\n")
        append("  </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        // "r" for read-only: no padding follows, so nothing may edit the packet
        // in place. Rewriting the segment, as this class does, is still fine.
        append("<?xpacket end=\"r\"?>")
    }

    /** One marker segment, held while the metadata block is reordered. */
    private class Segment(val marker: Int, val payload: ByteArray) {
        fun writeTo(sink: BufferedSink) {
            val length = SEGMENT_LENGTH_BYTES + payload.size
            sink.writeByte(MARKER_PREFIX)
            sink.writeByte(marker)
            sink.writeShort(length)
            sink.write(payload)
        }
    }

    /**
     * Reads the run of metadata segments at the head of the file, dropping any
     * XMP already there, and stops on the first marker that is not one.
     *
     * Returns those segments plus the byte value of the marker it stopped on;
     * every marker-prefix byte of its padding run has already been consumed from
     * [source], and the caller writes exactly one prefix back before it.
     */
    private fun readLeadingSegments(source: BufferedSource): Pair<List<Segment>, Int> {
        val segments = ArrayList<Segment>()
        while (true) {
            val prefix = source.readByte().toInt() and 0xff
            if (prefix != MARKER_PREFIX) {
                throw IOException("Expected a marker, found 0x${prefix.toString(16)}")
            }

            // Any number of 0xFF bytes may pad the gap before a marker.
            var marker = source.readByte().toInt() and 0xff
            while (marker == MARKER_PREFIX) marker = source.readByte().toInt() and 0xff

            val isMetadata = marker in MARKER_APP0..MARKER_APP15 || marker == MARKER_COM
            if (!isMetadata) return segments to marker

            val length = source.readShort().toInt() and 0xffff
            if (length < SEGMENT_LENGTH_BYTES) {
                throw IOException("Segment 0x${marker.toString(16)} declares $length bytes")
            }
            val payload = source.readByteArray((length - SEGMENT_LENGTH_BYTES).toLong())

            // Replacing rather than appending: two XMP packets in one file is
            // undefined, and readers generally take the first one they find.
            val isExistingXmp = marker == MARKER_APP1 &&
                (payload.startsWith(XMP_SIGNATURE) || payload.startsWith(XMP_EXTENSION_SIGNATURE))
            if (!isExistingXmp) segments += Segment(marker, payload)
        }
    }

    private fun writeXmpSegment(sink: BufferedSink, packet: ByteArray) {
        val length = SEGMENT_LENGTH_BYTES + XMP_SIGNATURE.size + packet.size
        sink.writeByte(MARKER_PREFIX)
        sink.writeByte(MARKER_APP1)
        sink.writeShort(length)
        sink.write(XMP_SIGNATURE)
        sink.write(packet)
    }

    /** XMP booleans are the words, capitalised — not `1`/`0` or lowercase. */
    private fun Boolean.asXmpBoolean(): String = if (this) "True" else "False"

    private fun String.escapeXmlAttribute(): String = buildString(length) {
        this@escapeXmlAttribute.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

    private fun ByteArray.startsWith(signature: ByteArray): Boolean =
        size >= signature.size && signature.indices.all { this[it] == signature[it] }
}
