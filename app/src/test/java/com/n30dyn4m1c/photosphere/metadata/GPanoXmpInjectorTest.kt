package com.n30dyn4m1c.photosphere.metadata

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * The injector rewrites a JPEG's header by hand, so what matters is that the
 * bytes it does not own come out identical — a re-encode here would quietly cost
 * the user a generation of image quality on every sphere.
 */
class GPanoXmpInjectorTest {

    @Test
    fun `every GPano property Google Photos looks for is written`() {
        val packet = GPanoXmpInjector.buildXmpPacket(GPanoMetadata.forFullPano(4096, 2048))

        assertTrue(packet.contains("xmlns:GPano=\"http://ns.google.com/photos/1.0/panorama/\""))
        assertTrue(packet.contains("GPano:UsePanoramaViewer=\"True\""))
        assertTrue(packet.contains("GPano:ProjectionType=\"equirectangular\""))
        assertTrue(packet.contains("GPano:FullPanoWidthPixels=\"4096\""))
        assertTrue(packet.contains("GPano:FullPanoHeightPixels=\"2048\""))
        assertTrue(packet.contains("GPano:CroppedAreaImageWidthPixels=\"4096\""))
        assertTrue(packet.contains("GPano:CroppedAreaImageHeightPixels=\"2048\""))
        assertTrue(packet.contains("GPano:CroppedAreaLeftPixels=\"0\""))
        assertTrue(packet.contains("GPano:CroppedAreaTopPixels=\"0\""))
    }

    @Test
    fun `the packet is a well-formed xpacket around one rdf description`() {
        val packet = GPanoXmpInjector.buildXmpPacket(GPanoMetadata.forFullPano(1000, 500))

        assertTrue(packet.startsWith("<?xpacket begin="))
        assertTrue(packet.endsWith("<?xpacket end=\"r\"?>"))
        assertEquals(1, packet.split("<rdf:Description").size - 1)
        assertTrue(packet.contains("</x:xmpmeta>"))
    }

    @Test
    fun `the image data is copied through byte for byte`() {
        val tail = tailBytes()
        val source = jpeg(app0Jfif(), exifApp1()) + tail

        val output = inject(source, GPanoMetadata.forFullPano(4096, 2048))

        // Everything from the first non-metadata marker on is the encoded image:
        // tables, frame header and the entropy-coded scan. None of it may move.
        assertArrayEquals(tail, output.tail())
    }

    @Test
    fun `the XMP segment goes in after JFIF and Exif`() {
        val source = jpeg(app0Jfif(), exifApp1()) + tailBytes()

        val markers = inject(source, GPanoMetadata.forFullPano(4096, 2048))
            .segments()
            .map { (marker, payload) -> marker to payload.signature() }

        assertEquals(
            listOf(
                APP0 to "JFIF",
                APP1 to "Exif",
                APP1 to "XMP",
            ),
            markers,
        )
    }

    @Test
    fun `a JPEG with no metadata segments at all gets one`() {
        val source = jpeg() + tailBytes()

        val segments = inject(source, GPanoMetadata.forFullPano(2048, 1024)).segments()

        assertEquals(1, segments.size)
        assertEquals(APP1, segments.single().first)
        assertTrue(segments.single().second.xmpPacket().contains("GPano:FullPanoWidthPixels"))
    }

    @Test
    fun `an existing XMP packet is replaced rather than added to`() {
        val stale = GPanoXmpInjector.buildXmpPacket(GPanoMetadata.forFullPano(640, 320))
        val source = jpeg(app0Jfif(), xmpApp1(stale), iccApp2()) + tailBytes()

        val segments = inject(source, GPanoMetadata.forFullPano(4096, 2048)).segments()

        val xmpSegments = segments.filter { it.second.signature() == "XMP" }
        assertEquals(1, xmpSegments.size)
        // The new dimensions, not the stale ones.
        val packet = xmpSegments.single().second.xmpPacket()
        assertTrue(packet.contains("GPano:FullPanoWidthPixels=\"4096\""))
        assertTrue(!packet.contains("640"))
        // The ICC profile beside it is untouched, and still after the XMP.
        assertEquals(listOf("JFIF", "XMP", "ICC_PROFILE"), segments.map { it.second.signature() })
    }

    @Test
    fun `injecting twice is the same as injecting once`() {
        val source = jpeg(app0Jfif(), exifApp1()) + tailBytes()
        val metadata = GPanoMetadata.forFullPano(4096, 2048)

        val once = inject(source, metadata)
        val twice = inject(once, metadata)

        assertArrayEquals(once, twice)
    }

    @Test
    fun `a file that does not start with SOI is refused`() {
        val notAJpeg = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        assertThrows(IOException::class.java) {
            inject(notAJpeg, GPanoMetadata.forFullPano(100, 50))
        }
    }

    @Test
    fun `a JPEG that ends inside a segment is refused`() {
        // A segment declaring 200 bytes of payload with none of them present.
        val truncated = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), APP1.toByte(), 0x00, 0xC8.toByte(),
        )

        assertThrows(IOException::class.java) {
            inject(truncated, GPanoMetadata.forFullPano(100, 50))
        }
    }

    @Test
    fun `a full pano covers the whole image`() {
        val metadata = GPanoMetadata.forFullPano(4096, 2048)

        assertEquals(4096, metadata.croppedAreaImageWidthPixels)
        assertEquals(2048, metadata.croppedAreaImageHeightPixels)
        assertEquals(0, metadata.croppedAreaLeftPixels)
        assertEquals(0, metadata.croppedAreaTopPixels)
        assertEquals(GPanoMetadata.PROJECTION_EQUIRECTANGULAR, metadata.projectionType)
        assertTrue(metadata.usePanoramaViewer)
    }

    @Test
    fun `a cropped area outside the sphere is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            GPanoMetadata(
                fullPanoWidthPixels = 4096,
                fullPanoHeightPixels = 2048,
                croppedAreaImageWidthPixels = 4096,
                croppedAreaImageHeightPixels = 2048,
                croppedAreaLeftPixels = 1,
                croppedAreaTopPixels = 0,
            )
        }
    }

    // --- Helpers ------------------------------------------------------------

    private fun inject(source: ByteArray, metadata: GPanoMetadata): ByteArray {
        val sink = ByteArrayOutputStream()
        GPanoXmpInjector.inject(ByteArrayInputStream(source), sink, metadata)
        return sink.toByteArray()
    }

    /** `SOI` followed by [segments], with no image data after them. */
    private fun jpeg(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segments.fold(ByteArray(0), ByteArray::plus)

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            marker.toByte(),
            (length ushr 8).toByte(),
            (length and 0xFF).toByte(),
        ) + payload
    }

    private fun app0Jfif(): ByteArray =
        segment(APP0, "JFIF\u0000".ascii() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))

    private fun exifApp1(): ByteArray =
        segment(APP1, "Exif\u0000\u0000".ascii() + ByteArray(16) { it.toByte() })

    private fun xmpApp1(packet: String): ByteArray =
        segment(APP1, "http://ns.adobe.com/xap/1.0/\u0000".ascii() + packet.toByteArray())

    private fun iccApp2(): ByteArray =
        segment(APP2, "ICC_PROFILE\u0000".ascii() + ByteArray(8) { 0x7F })

    /**
     * A quantisation table, a scan header, some entropy-coded data and `EOI`:
     * stand-in for everything the injector must not touch.
     */
    private fun tailBytes(): ByteArray =
        segment(DQT, ByteArray(65) { (it * 3).toByte() }) +
            segment(SOS, byteArrayOf(1, 1, 0, 0, 63, 0)) +
            ByteArray(512) { (it % 251).toByte() } +
            byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    /** Splits a JPEG into its leading metadata segments and everything after. */
    private fun ByteArray.split(): Pair<List<Pair<Int, ByteArray>>, ByteArray> {
        require(this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()) { "no SOI" }
        val segments = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 2
        while (true) {
            require(this[offset] == 0xFF.toByte()) { "no marker at $offset" }
            val marker = this[offset + 1].toInt() and 0xFF
            if (marker !in APP0..APP15 && marker != COM) {
                return segments to copyOfRange(offset, size)
            }
            val length = ((this[offset + 2].toInt() and 0xFF) shl 8) or
                (this[offset + 3].toInt() and 0xFF)
            segments += marker to copyOfRange(offset + 4, offset + 2 + length)
            offset += 2 + length
        }
    }

    private fun ByteArray.segments(): List<Pair<Int, ByteArray>> = split().first

    private fun ByteArray.tail(): ByteArray = split().second

    /**
     * What an APPn payload says it is: the NUL-terminated identifier it opens
     * with, shortened to "XMP" for the one that is a whole namespace URI.
     */
    private fun ByteArray.signature(): String =
        when (val identifier = String(this, Charsets.US_ASCII).substringBefore('\u0000')) {
            "http://ns.adobe.com/xap/1.0/" -> "XMP"
            else -> identifier
        }

    private fun ByteArray.xmpPacket(): String =
        String(this, Charsets.UTF_8).substringAfter('\u0000')

    private fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)

    private companion object {
        const val APP0 = 0xE0
        const val APP1 = 0xE1
        const val APP2 = 0xE2
        const val APP15 = 0xEF
        const val COM = 0xFE
        const val DQT = 0xDB
        const val SOS = 0xDA
    }
}
