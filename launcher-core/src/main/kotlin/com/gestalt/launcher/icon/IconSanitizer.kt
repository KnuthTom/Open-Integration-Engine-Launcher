/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.icon

import org.w3c.dom.Attr
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Makes an untrusted, user-uploaded icon safe to persist and render — or rejects it.
 *
 * SVG is a first-class XSS / XXE / SSRF vector, so an uploaded `.svg` is **parsed with the same
 * XXE-hardened factory as [com.gestalt.launcher.jnlp.JnlpParser]** (DOCTYPE + all external entities
 * disabled — a `<!DOCTYPE>`/`<!ENTITY>` is rejected outright) and then scrubbed at the DOM level:
 *  - dangerous elements are removed: `<script>`, `<foreignObject>`, and all SMIL animation elements
 *    (`<animate>`, `<set>`, … — they can retarget `href`/event attributes);
 *  - every `on*` event attribute is stripped;
 *  - `href` / `xlink:href` keep **only** internal fragment refs (`#id`); any `javascript:`, `data:`,
 *    `http(s):`, or `file:` target is removed (kills script URLs, external `<use>`/`<image>` and SSRF);
 *  - attribute / `<style>` values carrying `javascript:`, `expression(`, `@import`, or an external
 *    `url(…)` are removed;
 *  - processing instructions (e.g. `<?xml-stylesheet?>`) are dropped.
 * Only the **sanitized** copy is returned. The type is decided by file **content** (magic bytes / leading
 * `<`), not the filename, so a hostile `.svg` renamed to `.png` is still SVG-sanitized.
 *
 * Raster uploads (`.png` / `.jpg`) are decoded with ImageIO to prove they are genuine images and bounded
 * to [MAX_DIMENSION]px; they are stored as-authored (never tinted).
 *
 * Both paths enforce the [MAX_BYTES] size cap. Anything that can't be made safe raises
 * [IconRejectedException].
 */
object IconSanitizer {

    /** Max accepted upload size (~512 KB). */
    const val MAX_BYTES: Long = 512L * 1024

    /** Max accepted raster dimension, per side (px). */
    const val MAX_DIMENSION: Int = 512

    /**
     * The safe result of sanitizing one upload.
     *
     * @property ext canonical extension to store under — `svg`, `png`, or `jpg`.
     * @property bytes the bytes to persist (a re-serialized, scrubbed DOM for SVG; the original bytes for raster).
     * @property tintable true only for an SVG authored with `currentColor`, so the renderer recolors it from
     *   the theme; raster and fixed-color SVGs render as authored.
     */
    class SanitizedIcon(val ext: String, val bytes: ByteArray, val tintable: Boolean)

    /** Sanitize [input] (named [sourceName] only for diagnostics); return a safe copy or throw. */
    fun sanitize(sourceName: String, input: ByteArray): SanitizedIcon {
        if (input.isEmpty()) reject("the file is empty")
        if (input.size > MAX_BYTES) reject("the file is ${input.size / 1024} KB; the limit is ${MAX_BYTES / 1024} KB")
        return when (detectType(input)) {
            Type.SVG -> sanitizeSvg(input)
            Type.PNG -> raster(input, "png")
            Type.JPG -> raster(input, "jpg")
            Type.UNKNOWN -> reject("unsupported icon type — use a .svg, .png, or .jpg image")
        }
    }

    private enum class Type { SVG, PNG, JPG, UNKNOWN }

    /** Decide the real type from content (not the filename), so a mislabelled file can't dodge the right gate. */
    private fun detectType(b: ByteArray): Type {
        if (b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()) return Type.PNG
        if (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return Type.JPG
        var i = 0
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) i = 3 // UTF-8 BOM
        while (i < b.size && b[i].toInt().toChar().isWhitespace()) i++
        return if (i < b.size && b[i] == '<'.code.toByte()) Type.SVG else Type.UNKNOWN
    }

    // --- SVG ---------------------------------------------------------------------------------------

    private val REMOVE_ELEMENTS = setOf(
        "script", "foreignobject",
        // SMIL animation can retarget href / event attributes at runtime — drop it wholesale (a static
        // icon never needs it).
        "animate", "animatetransform", "animatemotion", "animatecolor", "set", "mpath",
        // Event-binding elements.
        "handler", "listener",
    )

    /** Event-binding attribute localNames the `on*` rule misses (XML-Events: `ev:event`, `ev:handler`, …). */
    private val EVENT_ATTR_NAMES = setOf("event", "handler", "listener", "observer")

    /** URL-scheme tokens that have no place in a static icon's attribute or style value. */
    private val SCHEME_TOKENS = listOf("javascript:", "vbscript:", "http:", "https:", "ftp:", "file:", "data:")

    /** CSS comment, stripped before token matching so `expr/**/ession(` can't split a keyword. */
    private val CSS_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    /** Max element-nesting depth — bounds the DOM walk so a deeply-nested SVG can't blow the stack. */
    private const val MAX_DEPTH = 80

    private fun sanitizeSvg(input: ByteArray): SanitizedIcon {
        val doc: Document = try {
            secureBuilder().parse(ByteArrayInputStream(input))
        } catch (e: Exception) {
            // A DOCTYPE/ENTITY (XXE) trips disallow-doctype-decl; malformed XML lands here too.
            reject("not a parseable, safe SVG (${e.message})")
        }
        val root = doc.documentElement ?: reject("the SVG has no root element")
        if (localName(root) != "svg") reject("the root element is <${localName(root)}>, not <svg>")

        scrubAttributes(root)
        scrubChildren(root, 0)

        val bytes = serialize(doc)
        val tintable = String(bytes, Charsets.UTF_8).contains("currentColor")
        return SanitizedIcon("svg", bytes, tintable)
    }

    /** Depth-first removal of unsafe elements / PIs; scrub the attributes of everything that survives. */
    private fun scrubChildren(el: Element, depth: Int) {
        if (depth > MAX_DEPTH) reject("the SVG is nested more than $MAX_DEPTH levels deep")
        val children = el.childNodes
        val remove = ArrayList<Node>()
        for (i in 0 until children.length) {
            val n = children.item(i)
            when (n.nodeType) {
                Node.ELEMENT_NODE -> {
                    val child = n as Element
                    if (localName(child) in REMOVE_ELEMENTS) {
                        remove.add(child)
                    } else if (localName(child) == "style" && styleTextUnsafe(child.textContent)) {
                        remove.add(child)
                    } else {
                        scrubAttributes(child)
                        scrubChildren(child, depth + 1)
                    }
                }
                // Strip processing instructions (e.g. <?xml-stylesheet href="evil.css"?>).
                Node.PROCESSING_INSTRUCTION_NODE -> remove.add(n)
                else -> {} // text / CDATA / comment: inert for a static SVG raster — left as-is
            }
        }
        remove.forEach { el.removeChild(it) }
    }

    private fun scrubAttributes(el: Element) {
        val attrs = el.attributes
        val remove = ArrayList<Attr>()
        for (i in 0 until attrs.length) {
            val a = attrs.item(i) as Attr
            val qName = a.name
            // Keep namespace declarations (xmlns / xmlns:*) — they legitimately hold a URI and the document
            // needs them to render. Everything else is subject to the value/name checks below.
            if (qName == "xmlns" || qName.startsWith("xmlns:")) continue
            val name = (a.localName ?: qName).lowercase()
            val v = normalize(a.value ?: "")
            val unsafe = when {
                name.startsWith("on") || name in EVENT_ATTR_NAMES -> true  // event handlers / XML-Events bindings
                name == "href" -> !v.startsWith("#")                       // href/xlink:href: internal fragment only
                name == "style" -> styleValueUnsafe(v)
                hasScheme(v) -> true                                       // any javascript:/http(s):/file:/data:/… URL
                hasExternalUrl(v) -> true                                  // url(http…) etc. (non-#-fragment)
                else -> false
            }
            if (unsafe) remove.add(a)
        }
        remove.forEach { el.removeAttributeNode(it) }
    }

    /**
     * A token-matching view of a value: CSS comments and backslash escapes are removed first (so
     * `expr/**/ession(` and `url\28…\29` cannot split a token), then whitespace / control chars are
     * stripped and it is lower-cased. Used only for the safety checks, never for the stored bytes.
     */
    private fun normalize(s: String): String {
        val noComments = s.replace(CSS_COMMENT, "")
        return buildString {
            for (c in noComments) if (c != '\\' && !c.isWhitespace() && !c.isISOControl()) append(c.lowercaseChar())
        }
    }

    private fun hasScheme(v: String): Boolean = SCHEME_TOKENS.any { v.contains(it) }

    /** True if a normalized value contains a `url(…)` whose target is not a pure internal fragment `url(#…)`. */
    private fun hasExternalUrl(v: String): Boolean {
        var idx = v.indexOf("url(")
        while (idx >= 0) {
            var j = idx + 4
            while (j < v.length && (v[j] == '\'' || v[j] == '"')) j++
            if (j >= v.length || v[j] != '#') return true // anything not url(#…) is treated as external
            idx = v.indexOf("url(", idx + 4)
        }
        return false
    }

    private fun styleValueUnsafe(v: String): Boolean =
        hasScheme(v) || v.contains("expression(") || v.contains("@import") || v.contains("//") || hasExternalUrl(v)

    private fun styleTextUnsafe(text: String): Boolean = styleValueUnsafe(normalize(text))

    private fun serialize(doc: Document): ByteArray {
        val factory = TransformerFactory.newInstance()
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "") }
        val transformer = factory.newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "no")
        }
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    // --- raster ------------------------------------------------------------------------------------

    private fun raster(input: ByteArray, ext: String): SanitizedIcon {
        // The dimensions come from the header, before any pixel is decoded: a small file can declare a
        // raster of thousands of pixels a side, and decoding it first would allocate all of it.
        val image = try {
            ImageIO.createImageInputStream(ByteArrayInputStream(input)).use { stream ->
                val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: reject("not a readable ${ext.uppercase()} image")
                try {
                    reader.setInput(stream, true, true)
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    if (w > MAX_DIMENSION || h > MAX_DIMENSION) reject("the image is ${w}×${h}px; the limit is ${MAX_DIMENSION}px per side")
                    reader.read(0)
                } finally {
                    reader.dispose()
                }
            }
        } catch (e: IconRejectedException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: reject("not a readable ${ext.uppercase()} image")
        if (image.width > MAX_DIMENSION || image.height > MAX_DIMENSION) {
            reject("the image is ${image.width}×${image.height}px; the limit is ${MAX_DIMENSION}px per side")
        }
        return SanitizedIcon(ext, input, tintable = false)
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun reject(reason: String): Nothing = throw IconRejectedException(reason)

    private fun localName(el: Element): String = (el.localName ?: el.tagName).lowercase()

    /**
     * The XXE-hardened builder — identical hardening to `JnlpParser.secureBuilder()`: secure processing,
     * DOCTYPE declarations rejected, all external entities / DTD loading disabled, no XInclude, no entity
     * expansion. Duplicated (not shared) to keep this security gate self-contained and independently audited.
     */
    private fun secureBuilder() = DocumentBuilderFactory.newInstance().apply {
        setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        isXIncludeAware = false
        isExpandEntityReferences = false
        isNamespaceAware = true
    }.newDocumentBuilder()
}
