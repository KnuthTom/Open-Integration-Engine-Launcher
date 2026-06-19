/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Supply-chain invariants: HttpClient 5 only (no HttpClient 4), no `not-yet-commons-ssl`, and the
 * dependabot config is present. (The SBOM itself is produced by the `cyclonedxBom` Gradle task.)
 */
class SupplyChainTest {

    private fun loadable(fqcn: String): Boolean =
        try { Class.forName(fqcn, false, javaClass.classLoader); true } catch (e: Throwable) { false }

    @Test
    @DisplayName("HttpClient 5 is on the classpath")
    fun httpClient5Present() {
        assertTrue(loadable("org.apache.hc.client5.http.impl.classic.CloseableHttpClient"))
    }

    @Test
    @DisplayName("HttpClient 4 is absent")
    fun httpClient4Absent() {
        assertFalse(loadable("org.apache.http.impl.client.CloseableHttpClient")) { "HttpClient 4 must not be present" }
        assertFalse(loadable("org.apache.http.client.HttpClient")) { "HttpClient 4 must not be present" }
    }

    @Test
    @DisplayName("not-yet-commons-ssl is absent")
    fun commonsSslAbsent() {
        assertFalse(loadable("org.apache.commons.ssl.SSLClient")) { "not-yet-commons-ssl must not be present" }
    }

    @Test
    @DisplayName(".github/dependabot.yml is present")
    fun dependabotConfigPresent() {
        // Gradle runs tests with the module dir as the working dir; the repo root is one level up.
        val candidates = listOf(
            Path.of(".github/dependabot.yml"),
            Path.of("../.github/dependabot.yml"),
        )
        assertTrue(candidates.any { Files.isRegularFile(it) }) {
            "dependabot.yml not found relative to ${Path.of("").toAbsolutePath()}"
        }
    }
}
