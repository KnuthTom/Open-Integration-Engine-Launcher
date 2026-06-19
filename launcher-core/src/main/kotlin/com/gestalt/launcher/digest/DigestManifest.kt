/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.digest

/**
 * Binds artifact name → expected SHA-256 so the launcher can detect substitution/rollback before
 * downloading and after caching. Two sources feed it, in the launcher's order of preference:
 *  1. [fromInlineDigests] — the per-jar digest a real OIE/Mirth server already publishes inline in its
 *     JNLP (`<jar … sha256="…"/>`, base64). This is the primary source; no extra endpoint is involved.
 *  2. [parse] — the optional external manifest at `GET /digest/{version}`, one
 *     `"<sha-256-hex>  <artifact-name>"` line per artifact. Used only when a JNLP carries no inline
 *     digests.
 *
 * Lookups are exact-by-name with a basename fallback (the JNLP may reference `lib/foo.jar` while the
 * manifest lists `foo.jar`).
 */
class DigestManifest private constructor(
    val entries: Map<String, String>,
) {
    /** Expected lowercase-hex SHA-256 for [artifactName], or null if not in the manifest. */
    fun expectedFor(artifactName: String): String? {
        entries[artifactName]?.let { return it }
        val base = basename(artifactName)
        entries[base]?.let { return it }
        // also allow manifest entries given with a path while the query is a basename
        return entries.entries.firstOrNull { basename(it.key) == base }?.value
    }

    companion object {
        /**
         * Build a manifest from per-jar digests the JNLP carries inline, keyed by the same artifact name
         * the gate looks up. Each value may be base64 (of the 32-byte SHA-256) or 64-char hex; both
         * normalize to lowercase hex. Returns null when [entries] is empty, so the caller can fall back to
         * an external `/digest` manifest.
         */
        fun fromInlineDigests(entries: Map<String, String>): DigestManifest? {
            if (entries.isEmpty()) return null
            val map = LinkedHashMap<String, String>()
            entries.forEach { (name, value) ->
                require(name.isNotBlank()) { "inline digest entry has a blank artifact name" }
                map[name] = normalizeSha256(value)
            }
            return DigestManifest(map)
        }

        /** Accept a SHA-256 as 64-char hex or as base64 of exactly 32 bytes; return lowercase hex. */
        private fun normalizeSha256(value: String): String {
            val v = value.trim()
            if (v.length == 64 && v.all { it.lowercaseChar() in "0123456789abcdef" }) return v.lowercase()
            val bytes = try {
                java.util.Base64.getDecoder().decode(v)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("jar digest is neither 64-char hex nor base64: '$value'")
            }
            require(bytes.size == 32) { "jar digest did not decode to 32 bytes (SHA-256): '$value'" }
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun parse(text: String): DigestManifest {
            val map = LinkedHashMap<String, String>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEach
                // "<hex>  <name>" — split on first run of whitespace; name may contain spaces.
                val idx = line.indexOfFirst { it == ' ' || it == '\t' }
                require(idx > 0) { "malformed digest line: '$raw'" }
                val hex = line.substring(0, idx).lowercase()
                val name = line.substring(idx).trim().removePrefix("*").trim()
                require(hex.length == 64 && hex.all { it in "0123456789abcdef" }) {
                    "not a SHA-256 hex digest: '$hex'"
                }
                require(name.isNotEmpty()) { "digest line has no artifact name: '$raw'" }
                map[name] = hex
            }
            require(map.isNotEmpty()) { "digest manifest is empty" }
            return DigestManifest(map)
        }

        private fun basename(name: String): String =
            name.substringAfterLast('/').substringAfterLast('\\')
    }
}
