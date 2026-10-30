/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.jnlp

import com.gestalt.launcher.audit.AuditLog
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Parses a server JNLP into the safe [JnlpDescriptor] projection.
 *
 * Two security properties matter here:
 *  1. **XXE-hardened.** The document is untrusted, so DTDs and external entities are fully disabled.
 *  2. **No trust toggles are honored.** The parser extracts only jar list / main-class / arguments /
 *     version. Any element/attribute/property that looks like a verification-weakening directive is
 *     recorded in [JnlpDescriptor.ignoredDirectives] and audit-logged as ignored — it never changes
 *     behavior, because there is no field for it to flow into.
 */
object JnlpParser {

    // Substrings that mark a server-provided attempt to weaken verification. Detected, logged, ignored.
    private val WEAKENING_MARKERS = listOf(
        "allow-self-signed", "allow_self_signed", "allowselfsigned",
        "allow-incorrect-digest", "allow-bad-digest", "ignore-digest", "skip-digest",
        "allow-unsigned", "allow-unverified", "disable-verification", "skip-verification",
        "no-verify", "noverify", "insecure", "trust-all", "trustall", "disable-signature",
    )

    fun parse(xml: String): JnlpDescriptor = parse(xml.toByteArray(Charsets.UTF_8))

    fun parse(xml: ByteArray): JnlpDescriptor {
        val doc = secureBuilder().parse(ByteArrayInputStream(xml))
        doc.documentElement.normalize()
        val root = doc.documentElement // <jnlp>

        val codebase = root.getAttribute("codebase").ifBlank { null }
        val version = root.getAttribute("version").ifBlank { null }
            ?: firstPropertyValue(root, setOf("gestalt.version", "app.version", "version"))

        val jars = mutableListOf<JnlpJar>()
        elementsNamed(root, "jar").forEach { el ->
            val href = el.getAttribute("href")
            if (href.isNotBlank()) {
                val isMain = el.getAttribute("main").equals("true", ignoreCase = true)
                // OIE/Mirth publish the per-jar integrity digest inline (base64 SHA-256); captured so the
                // launcher can verify against it without a separate manifest endpoint.
                val sha256 = el.getAttribute("sha256").ifBlank { null }
                jars += JnlpJar(href, isMain, sha256)
            }
        }

        // Component descriptors (`<extension href="webstart/extensions/foo.jnlp"/>`): one per installed
        // Mirth extension. Only the href is read; the launcher resolves it against this descriptor's
        // codebase PATH on the connection's own origin, fetches it with the same transport, and parses it
        // with this parser.
        val extensions = elementsNamed(root, "extension")
            .map { it.getAttribute("href").trim() }
            .filter { it.isNotEmpty() }

        val appDesc = elementsNamed(root, "application-desc").firstOrNull()
        val mainClass = appDesc?.getAttribute("main-class")?.ifBlank { null }
        val arguments = appDesc?.let { elementsNamed(it, "argument").map { a -> a.textContent.trim() } }
            ?: emptyList()

        // Captured for transparency, NOT applied to the child JVM.
        val untrustedJvmArgs = elementsNamed(root, "j2se")
            .mapNotNull { it.getAttribute("java-vm-args").ifBlank { null } }
            .flatMap { it.trim().split(Regex("\\s+")) }
            .filter { it.isNotBlank() }

        val ignored = detectWeakeningDirectives(root)
        if (ignored.isNotEmpty()) {
            AuditLog.event(
                "jnlp.directive.ignored",
                "count" to ignored.size,
                "directives" to ignored.joinToString(","),
                "result" to "IGNORED",
            )
        }

        return JnlpDescriptor(
            codebase = codebase,
            mainClass = mainClass,
            version = version,
            jars = jars,
            arguments = arguments,
            untrustedJvmArgs = untrustedJvmArgs,
            ignoredDirectives = ignored,
            extensions = extensions,
        )
    }

    // --- weakening-directive detection (report-only) ----------------------------------------------

    private fun detectWeakeningDirectives(root: Element): List<String> {
        val found = linkedSetOf<String>()
        forEachElement(root) { el ->
            matchMarker(el.localNameOrTag())?.let { found += "<${el.localNameOrTag()}>" }
            val attrs = el.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i)
                val an = a.nodeName
                val av = a.nodeValue ?: ""
                matchMarker(an)?.let { found += "@$an" }
                // <property name="allow-self-signed" value="true"/>
                if (an.equals("name", ignoreCase = true)) matchMarker(av)?.let { found += "property:$av" }
                if (an.equals("value", ignoreCase = true)) matchMarker(av)?.let { found += "value:$av" }
            }
        }
        return found.toList()
    }

    private fun matchMarker(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val norm = s.lowercase()
        return WEAKENING_MARKERS.firstOrNull { norm.contains(it) }
    }

    // --- DOM helpers ------------------------------------------------------------------------------

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

    private fun Element.localNameOrTag(): String = (localName ?: tagName).lowercase()

    private fun elementsNamed(scope: Element, name: String): List<Element> {
        val out = mutableListOf<Element>()
        forEachElement(scope) { if (it.localNameOrTag() == name.lowercase()) out += it }
        return out
    }

    private fun firstPropertyValue(root: Element, names: Set<String>): String? {
        elementsNamed(root, "property").forEach { p ->
            if (p.getAttribute("name").lowercase() in names) {
                return p.getAttribute("value").ifBlank { null }
            }
        }
        return null
    }

    private fun forEachElement(node: Element, action: (Element) -> Unit) {
        action(node)
        val children = node.childNodes
        for (i in 0 until children.length) {
            val c = children.item(i)
            if (c.nodeType == Node.ELEMENT_NODE) forEachElement(c as Element, action)
        }
    }
}
