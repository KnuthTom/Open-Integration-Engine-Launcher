/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

/**
 * The functional UI ("chrome") icon set — bundled [Lucide](https://lucide.dev) glyphs (ISC license),
 * tintable from the color tokens. Each entry maps a stable semantic [key] to a bundled SVG resource.
 *
 * See `THIRD_PARTY_LICENSES.md` for attribution.
 */
enum class ChromeIcon(val key: String, val label: String) {
    Connect("connect", "Connect"),
    Play("play", "Launch"),
    Plus("plus", "Add"),
    Pencil("pencil", "Edit"),
    Trash("trash", "Delete"),
    Settings("settings", "Settings"),
    Sliders("sliders", "Adjust"),
    Shield("shield", "Trust"),
    ShieldCheck("shield-check", "Verified"),
    Sort("sort", "Sort"),
    Drag("drag", "Reorder"),
    Terminal("terminal", "Console"),
    Refresh("refresh", "Refresh"),
    Server("server", "Server"),
    Folder("folder", "Folder"),
    Search("search", "Search"),
    ChevronRight("chevron-right", "Expand"),
    ChevronDown("chevron-down", "Collapse"),
    CheckCircle("check-circle", "Success"),
    AlertTriangle("alert-triangle", "Warning"),
    XCircle("x-circle", "Error");

    /** Classpath-relative location of the bundled SVG. */
    val resourcePath: String get() = "icons/chrome/$key.svg"

    companion object {
        /** All chrome icons, in display order. */
        val all: List<ChromeIcon> = entries.toList()

        fun byKey(key: String): ChromeIcon? = entries.firstOrNull { it.key == key }
    }
}
