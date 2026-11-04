/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * The sanitizer is the security boundary for untrusted uploaded icons. These tests prove each of the
 * documented SVG attack vectors is removed or the file rejected, that the caps hold, and that benign
 * content survives — feeding representative hostile inputs the way `JnlpParserTest` does.
 */
class IconSanitizerTest {

    private fun svg(body: String): ByteArray =
        ("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\" " +
            "viewBox=\"0 0 24 24\">$body</svg>").toByteArray()

    private fun out(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    @DisplayName("a benign currentColor SVG round-trips and is marked tintable")
    fun benignRoundTrips() {
        val result = IconSanitizer.sanitize("microscope.svg", svg("""<path fill="currentColor" d="M2 2h20v20H2z"/>"""))
        assertEquals("svg", result.ext)
        assertTrue(result.tintable) { "currentColor SVG should be tintable" }
        assertTrue(out(result.bytes).contains("<path")) { "the drawing survives sanitization" }
    }

    @Test
    @DisplayName("a fixed-color SVG (no currentColor) is kept but NOT tintable")
    fun fixedColorNotTintable() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="10" height="10" fill="#ff0000"/>"""))
        assertFalse(result.tintable)
    }

    @Test
    @DisplayName("<script> elements are removed")
    fun scriptStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<script>alert('xss')</script><rect width="1" height="1"/>"""))
        val text = out(result.bytes).lowercase()
        assertFalse(text.contains("<script")) { text }
        assertFalse(text.contains("alert"))
    }

    @Test
    @DisplayName("on* event attributes are stripped (including on the root <svg>)")
    fun eventAttributesStripped() {
        val rooted = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\">" +
            "<rect width=\"1\" height=\"1\" onclick=\"steal()\" onmouseover=\"x()\"/></svg>"
        val result = IconSanitizer.sanitize("x.png", rooted.toByteArray()) // mislabelled .png — still SVG by content
        val text = out(result.bytes).lowercase()
        assertEquals("svg", result.ext)
        assertFalse(text.contains("onload"))
        assertFalse(text.contains("onclick"))
        assertFalse(text.contains("onmouseover"))
    }

    @Test
    @DisplayName("<foreignObject> is removed")
    fun foreignObjectStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<foreignObject><body>hi</body></foreignObject><rect width="1" height="1"/>"""))
        assertFalse(out(result.bytes).lowercase().contains("foreignobject"))
    }

    @Test
    @DisplayName("javascript: URLs in href are stripped")
    fun javascriptHrefStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<a href="javascript:alert(1)"><rect width="1" height="1"/></a>"""))
        assertFalse(out(result.bytes).lowercase().contains("javascript:"))
    }

    @Test
    @DisplayName("external http/file refs in href / xlink:href are stripped (SSRF / external <use>/<image>)")
    fun externalRefsStripped() {
        val result = IconSanitizer.sanitize(
            "x.svg",
            svg("""<use xlink:href="http://attacker.example/evil.svg#i"/><image href="file:///etc/passwd"/>"""),
        )
        val text = out(result.bytes).lowercase()
        assertFalse(text.contains("http://attacker"))
        assertFalse(text.contains("file:"))
    }

    @Test
    @DisplayName("an internal fragment ref (#id) is preserved")
    fun internalFragmentRefKept() {
        val result = IconSanitizer.sanitize(
            "x.svg",
            svg("""<linearGradient id="g"/><rect width="10" height="10" fill="url(#g)"/><use href="#g"/>"""),
        )
        val text = out(result.bytes)
        assertTrue(text.contains("#g")) { "internal references must survive: $text" }
    }

    @Test
    @DisplayName("an external url() in a style attribute is stripped")
    fun externalStyleUrlStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="1" height="1" style="fill:url(http://evil/x)"/>"""))
        assertFalse(out(result.bytes).lowercase().contains("http://evil"))
    }

    @Test
    @DisplayName("SMIL animation that could retarget href is removed")
    fun smilAnimationStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="1" height="1"><set attributeName="href" to="javascript:alert(1)"/></rect>"""))
        val text = out(result.bytes).lowercase()
        assertFalse(text.contains("<set"))
        assertFalse(text.contains("javascript:"))
    }

    @Test
    @DisplayName("a DOCTYPE / external-entity (XXE) SVG is rejected outright")
    fun xxeRejected() {
        val xxe = """
            <?xml version="1.0"?>
            <!DOCTYPE svg [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <svg xmlns="http://www.w3.org/2000/svg"><text>&xxe;</text></svg>
        """.trimIndent().toByteArray()
        assertThrows(IconRejectedException::class.java) { IconSanitizer.sanitize("x.svg", xxe) }
    }

    @Test
    @DisplayName("a file over the size cap is rejected before parsing")
    fun oversizeFileRejected() {
        assertThrows(IconRejectedException::class.java) {
            IconSanitizer.sanitize("big.svg", ByteArray((IconSanitizer.MAX_BYTES + 1).toInt()))
        }
    }

    @Test
    @DisplayName("an unrecognized type is rejected")
    fun unsupportedTypeRejected() {
        assertThrows(IconRejectedException::class.java) { IconSanitizer.sanitize("x.bin", byteArrayOf(1, 2, 3, 4)) }
    }

    @Test
    @DisplayName("a genuine PNG within bounds is accepted as-is and not tintable")
    fun pngAccepted() {
        val result = IconSanitizer.sanitize("logo.png", png(16, 16))
        assertEquals("png", result.ext)
        assertFalse(result.tintable)
    }

    @Test
    @DisplayName("an over-large raster is rejected")
    fun oversizeRasterRejected() {
        assertThrows(IconRejectedException::class.java) { IconSanitizer.sanitize("big.png", png(600, 600)) }
    }

    @Test
    @DisplayName("a tiny PNG whose header declares a huge raster is rejected from the header, before any pixel is decoded")
    fun hugeDeclaredRasterRejectedFromTheHeader() {
        // Signature + IHDR (20000 × 20000, 8-bit RGBA) + IEND: 57 bytes that would decode to 1.6 GB.
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, body: ByteArray) {
            val len = java.nio.ByteBuffer.allocate(4).putInt(body.size).array()
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(typeBytes); update(body) }.value.toInt()
            out.write(len); out.write(typeBytes); out.write(body); out.write(java.nio.ByteBuffer.allocate(4).putInt(crc).array())
        }
        chunk("IHDR", java.nio.ByteBuffer.allocate(13).putInt(20_000).putInt(20_000).put(8).put(6).put(0).put(0).put(0).array())
        chunk("IEND", ByteArray(0))
        val before = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val ex = assertThrows(IconRejectedException::class.java) { IconSanitizer.sanitize("huge.png", out.toByteArray()) }
        assertTrue(ex.message!!.contains("20000") && ex.message!!.contains("limit")) { "rejected by its declared size: ${ex.message}" }
        val after = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        assertTrue(after - before < 64L * 1024 * 1024) { "no raster was allocated (grew by ${(after - before) / 1024} KB)" }
    }

    @Test
    @DisplayName("a non-image masquerading as .png is rejected")
    fun fakePngRejected() {
        assertThrows(IconRejectedException::class.java) {
            IconSanitizer.sanitize("fake.png", "not really a png".toByteArray())
        }
    }

    @Test
    @DisplayName("a deeply-nested SVG is rejected (bounded DOM walk — no StackOverflow)")
    fun deeplyNestedRejected() {
        val nested = "<svg xmlns=\"http://www.w3.org/2000/svg\">" + "<g>".repeat(400) + "</g>".repeat(400) + "</svg>"
        assertThrows(IconRejectedException::class.java) { IconSanitizer.sanitize("x.svg", nested.toByteArray()) }
    }

    @Test
    @DisplayName("a CSS image()/image-set() external ref in a style is stripped")
    fun cssImageFunctionStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="1" height="1" style="fill:image('http://evil/x.png')"/>"""))
        assertFalse(out(result.bytes).lowercase().contains("evil"))
    }

    @Test
    @DisplayName("a CSS-escaped url(\\28…\\29) external ref is stripped")
    fun cssEscapedUrlStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="1" height="1" style="fill:url\28http://evil/x\29"/>"""))
        assertFalse(out(result.bytes).lowercase().contains("evil"))
    }

    @Test
    @DisplayName("a comment-split CSS expression() is stripped")
    fun cssCommentExpressionStripped() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="1" height="1" style="width:expr/**/ession(alert(1))"/>"""))
        val text = out(result.bytes).lowercase()
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("expr"))
    }

    @Test
    @DisplayName("XML-Events binding attributes (ev:event / ev:handler) are stripped")
    fun xmlEventsAttributesStripped() {
        val xml = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:ev=\"http://www.w3.org/2001/xml-events\">" +
            "<rect width=\"1\" height=\"1\" ev:event=\"load\" ev:handler=\"#h\"/></svg>"
        val text = out(IconSanitizer.sanitize("x.svg", xml.toByteArray()).bytes).lowercase()
        assertFalse(text.contains("ev:event"))
        assertFalse(text.contains("ev:handler"))
    }

    @Test
    @DisplayName("the xmlns namespace declaration is preserved (not stripped as a scheme-bearing value)")
    fun namespaceDeclarationPreserved() {
        val result = IconSanitizer.sanitize("x.svg", svg("""<rect width="1" height="1" fill="currentColor"/>"""))
        assertTrue(out(result.bytes).contains("http://www.w3.org/2000/svg")) { "xmlns must survive" }
    }

    private fun png(w: Int, h: Int): ByteArray {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }
}
