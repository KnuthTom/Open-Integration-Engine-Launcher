/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase-4a wiring: a connection's `javaHome = Bundled(8|17)` must resolve to the JRE staged inside the
 * package, relative to the app-resources directory the Compose/jpackage runtime exposes — which is
 * what makes it work for both the installed app and the moved/unzipped portable app-image.
 */
class BundledRuntimeResolutionTest {

    @Test
    @DisplayName("explicit override path wins, returned as-is for the caller to validate")
    fun overrideWins(@TempDir tmp: Path) {
        val home = tmp.resolve("custom-jre").toString()
        assertEquals(
            Path.of(home),
            ChildJvmCommandBuilder.bundledRuntimeHome("17", override = home, resourcesDir = tmp.toString()),
        )
    }

    @Test
    @DisplayName("packaged: resolves runtimes/jre<version> under the app-resources dir when present")
    fun packagedResolves(@TempDir tmp: Path) {
        val jre8 = tmp.resolve("runtimes").resolve("jre8")
        Files.createDirectories(jre8)
        assertEquals(
            jre8,
            ChildJvmCommandBuilder.bundledRuntimeHome("8", override = null, resourcesDir = tmp.toString()),
        )
    }

    @Test
    @DisplayName("no override and no staged runtime -> null (caller falls back to the default JVM)")
    fun missingRuntimeIsNull(@TempDir tmp: Path) {
        // resources dir present but runtimes/jre17 not staged
        assertNull(ChildJvmCommandBuilder.bundledRuntimeHome("17", override = null, resourcesDir = tmp.toString()))
        // not packaged at all
        assertNull(ChildJvmCommandBuilder.bundledRuntimeHome("17", override = null, resourcesDir = null))
        // blank inputs are treated as absent
        assertNull(ChildJvmCommandBuilder.bundledRuntimeHome("17", override = "", resourcesDir = ""))
    }
}
