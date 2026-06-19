/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.design.icons

import androidx.compose.ui.unit.Density
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class IconRenderSmokeTest {

    @Test
    @DisplayName("every bundled SVG parses into a tintable painter")
    fun allIconsParse() {
        val density = Density(1f)
        val paths = ChromeIcon.all.map { it.resourcePath } + ConnectionIcon.all.map { it.resourcePath }
        val result = runCatching { paths.forEach { IconResources.painter(it, density) } }
        result.exceptionOrNull()?.let { e ->
            // If the Skia backend can't initialize in this (possibly headless) environment, skip
            // rather than fail — the resource-existence test already guards the registry.
            assumeTrue(e !is UnsatisfiedLinkError && e !is NoClassDefFoundError) {
                "skiko unavailable in this environment: ${e.message}"
            }
            throw e
        }
    }
}
