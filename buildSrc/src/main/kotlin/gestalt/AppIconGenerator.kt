/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package gestalt

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Renders the default app icon from code, with no external rasterizer (ImageMagick / Inkscape / rsvg
 * are not assumed present). The artwork is a neutral, vendor-neutral abstract mark: a rounded tile
 * carrying two accent discs, each with a tile-colored center — matching `Branding.Default`. The
 * original Gestalt mark (`design-system/.../branding/gestalt-cloud-connect-icon.svg`) ships only as
 * part of the OPTIONAL Gestalt branding pack, not as the default.
 *
 * Output (into the given directory):
 *  - `oie-launcher-icon.ico`  — multi-size Windows icon (PNG-compressed entries, 16…256).
 *  - `oie-launcher-icon.icns` — macOS icon (PNG entries, 32…1024) for the mac build host.
 *  - `oie-launcher-icon.png`  — 512px PNG, the Linux jpackage icon.
 *  - `png/icon-<n>.png`       — the individual PNG set.
 */
object AppIconGenerator {

    const val BASE_NAME = "oie-launcher-icon"

    // Neutral default palette (mirrors com.gestalt.design.branding.Branding.Default).
    private val TILE = Color(0x21, 0x50, 0x6E)   // deep teal-blue
    private val CELL_A = Color(0x4A, 0xA3, 0xC7) // cyan-blue
    private val CELL_B = Color(0xE0, 0xA2, 0x4A) // amber

    /** Sizes baked into the Windows .ico (256 is encoded as the special 0 byte). */
    private val ICO_SIZES = intArrayOf(16, 24, 32, 48, 64, 128, 256)

    /** macOS .icns PNG entries: OSType -> pixel size. */
    private val ICNS_ENTRIES = linkedMapOf(
        "ic11" to 32, "ic12" to 64, "ic07" to 128, "ic08" to 256, "ic09" to 512, "ic10" to 1024,
    )

    /** Standalone PNG sizes emitted under png/ (and 512 promoted to the top-level Linux icon). */
    private val PNG_SIZES = intArrayOf(16, 24, 32, 48, 64, 128, 256, 512)

    fun generate(outDir: File) {
        outDir.mkdirs()
        val pngDir = File(outDir, "png").apply { mkdirs() }

        // Per-size PNGs (the Linux PNG set).
        val pngBytesBySize = PNG_SIZES.associateWith { size -> encodePng(render(size)) }
        pngBytesBySize.forEach { (size, bytes) -> File(pngDir, "icon-$size.png").writeBytes(bytes) }

        // Linux jpackage icon: a single high-res PNG.
        File(outDir, "$BASE_NAME.png").writeBytes(pngBytesBySize.getValue(512))

        // Windows .ico (multi-size, PNG-compressed entries).
        File(outDir, "$BASE_NAME.ico").writeBytes(buildIco(ICO_SIZES.associateWith { encodePng(render(it)) }))

        // macOS .icns (PNG entries).
        File(outDir, "$BASE_NAME.icns").writeBytes(buildIcns(ICNS_ENTRIES.mapValues { encodePng(render(it.value)) }))
    }

    // --- drawing ----------------------------------------------------------------------------------

    /** Draw the mark at [size]×[size] on a transparent canvas. All geometry is proportional to [size]. */
    private fun render(size: Int): BufferedImage {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.composite = AlphaComposite.Clear
            g.fillRect(0, 0, size, size)
            g.composite = AlphaComposite.SrcOver

            val s = size.toDouble()
            // Rounded indigo tile.
            val inset = s * 0.035
            val arc = s * 0.22
            g.color = TILE
            g.fill(RoundRectangle2D.Double(inset, inset, s - 2 * inset, s - 2 * inset, arc, arc))

            // Accent disc A (upper-left) with a tile-colored center.
            disc(g, CELL_A, s, 0.40, 0.42, 0.225)
            disc(g, TILE, s, 0.40, 0.42, 0.075)
            // Accent disc B (lower-right) with a tile-colored center.
            disc(g, CELL_B, s, 0.635, 0.625, 0.165)
            disc(g, TILE, s, 0.635, 0.625, 0.055)
        } finally {
            g.dispose()
        }
        return img
    }

    /** Fill a circle whose center/radius are fractions of the tile size [s]. */
    private fun disc(g: java.awt.Graphics2D, color: Color, s: Double, cx: Double, cy: Double, r: Double) {
        g.color = color
        val rr = r * s
        g.fill(Ellipse2D.Double(cx * s - rr, cy * s - rr, 2 * rr, 2 * rr))
    }

    private fun encodePng(img: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()

    // --- .ico container (little-endian) -----------------------------------------------------------

    private fun buildIco(images: Map<Int, ByteArray>): ByteArray {
        val sizes = images.keys.sorted()
        val out = ByteArrayOutputStream()
        // ICONDIR header.
        writeLeShort(out, 0)            // reserved
        writeLeShort(out, 1)            // type = icon
        writeLeShort(out, sizes.size)   // count
        var offset = 6 + sizes.size * 16
        for (size in sizes) {
            val png = images.getValue(size)
            val dim = if (size >= 256) 0 else size  // 0 means 256
            out.write(dim); out.write(dim)
            out.write(0)                // color count (PNG/true-color -> 0)
            out.write(0)                // reserved
            writeLeShort(out, 1)        // color planes
            writeLeShort(out, 32)       // bits per pixel
            writeLeInt(out, png.size)   // bytes in resource
            writeLeInt(out, offset)     // image offset
            offset += png.size
        }
        for (size in sizes) out.write(images.getValue(size))
        return out.toByteArray()
    }

    // --- .icns container (big-endian) -------------------------------------------------------------

    private fun buildIcns(entries: Map<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        DataOutputStream(body).use { d ->
            for ((type, png) in entries) {
                d.writeBytes(type)          // 4-char OSType
                d.writeInt(png.size + 8)    // entry length includes the 8-byte header
                d.write(png)
            }
        }
        val bodyBytes = body.toByteArray()
        val file = ByteArrayOutputStream()
        DataOutputStream(file).use { d ->
            d.writeBytes("icns")
            d.writeInt(bodyBytes.size + 8)  // total file size includes the 8-byte magic+size header
            d.write(bodyBytes)
        }
        return file.toByteArray()
    }

    private fun writeLeShort(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
}
