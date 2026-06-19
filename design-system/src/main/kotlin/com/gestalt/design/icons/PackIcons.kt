/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

/**
 * An optional, **build-time** licensed icon pack, discovered at runtime from the classpath. When a build
 * bundles an extra pack (see the `stageIconPack` Gradle task), it stages the icons under
 * `icons/pack/<file>` plus an `icons/pack/index.txt` describing them; this object exposes those as
 * additional pickable library entries. Default builds bundle no pack, so [all] is empty.
 *
 * Pack icons are referenced from a connection by the key `pack:<key>` (distinct from the built-in
 * semantic keys and from user `custom:<id>` keys). Their **attribution** is surfaced separately in the
 * app's About → Credits (from `icon-pack.properties`); this object only resolves the glyphs.
 */
object PackIcons {

    /** The connection-icon key namespace for build-time pack icons: `pack:<key>`. */
    const val KEY_PREFIX = "pack:"

    private const val INDEX_RESOURCE = "icons/pack/index.txt"

    /**
     * One pack entry.
     *
     * @property key the connection-icon storage key, `pack:<rawKey>`.
     * @property label display name shown in the picker.
     * @property resourcePath classpath path of the glyph, e.g. `icons/pack/foo.svg`.
     * @property tintable true for SVGs (recolorable from the theme); raster packs render as authored.
     */
    data class Entry(val key: String, val label: String, val resourcePath: String) {
        val tintable: Boolean get() = resourcePath.endsWith(".svg", ignoreCase = true)
    }

    /** All bundled pack entries (empty when no pack is bundled), parsed once from the classpath index. */
    val all: List<Entry> by lazy {
        val text = PackIcons::class.java.classLoader.getResourceAsStream(INDEX_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: return@lazy emptyList()
        parseIndex(text)
    }

    /** Resolve a `pack:<key>` storage key to its entry, or null. */
    fun byKey(key: String): Entry? = all.firstOrNull { it.key == key }

    /**
     * Parse the `index.txt` body: one `rawKey|label|filename` line per icon (blank lines and `#` comments
     * ignored). Extracted as a pure function so it is unit-testable without a classpath fixture.
     */
    fun parseIndex(text: String): List<Entry> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 3) return@mapNotNull null
            val rawKey = parts[0].trim()
            val label = parts[1].trim()
            val file = parts[2].trim()
            if (rawKey.isEmpty() || file.isEmpty()) return@mapNotNull null
            Entry(key = "$KEY_PREFIX$rawKey", label = label.ifEmpty { rawKey }, resourcePath = "icons/pack/$file")
        }
        .toList()
}
