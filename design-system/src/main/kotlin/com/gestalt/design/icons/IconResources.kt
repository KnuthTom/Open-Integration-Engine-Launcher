/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density

/**
 * Loads icon assets into [Painter]s. The built-in sets are bundled SVGs on the classpath (authored with
 * `currentColor`, so a `ColorFilter.tint` recolors them from the active tokens — see `GestaltIcon`). The
 * runtime loaders additionally render arbitrary user-uploaded / pack icons from bytes or a classpath
 * resource, dispatching on extension: SVG → `loadSvgPainter`, raster (png/jpg) → `loadImageBitmap`.
 */
internal object IconResources {

    /** Load the SVG at [resourcePath] (classpath-relative, e.g. `icons/chrome/connect.svg`). */
    // The desktop useResource/loadSvgPainter pair is deprecated in favor of the multiplatform Compose
    // resources library; for this desktop-only module the classpath loader is intentional and stable.
    @Suppress("DEPRECATION")
    fun painter(resourcePath: String, density: Density): Painter =
        useResource(resourcePath) { loadSvgPainter(it, density) }

    /** Render raw icon [bytes] of type [ext] (`svg`/`png`/`jpg`/`jpeg`) into a [Painter]. */
    @Suppress("DEPRECATION")
    fun painterFromBytes(bytes: ByteArray, ext: String, density: Density): Painter =
        if (ext.equals("svg", ignoreCase = true)) {
            bytes.inputStream().use { loadSvgPainter(it, density) }
        } else {
            BitmapPainter(bytes.inputStream().use { loadImageBitmap(it) })
        }

    /** Render a classpath resource (e.g. a bundled icon-pack file) into a [Painter] by its extension. */
    fun painterFromResource(resourcePath: String, density: Density): Painter {
        val bytes = IconResources::class.java.classLoader.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: error("icon resource not found on classpath: $resourcePath")
        return painterFromBytes(bytes, resourcePath.substringAfterLast('.', ""), density)
    }

    /** Whether [resourcePath] is present on the classpath — used by tests to verify the registry. */
    fun exists(resourcePath: String): Boolean =
        IconResources::class.java.classLoader.getResource(resourcePath) != null
}
