/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.digest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

/** Locks the inline-digest normalization the launcher relies on for real OIE/Mirth JNLPs. */
class DigestManifestTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    @DisplayName("a base64 SHA-256 (Mirth's inline form) normalizes to lowercase hex")
    fun base64NormalizesToHex() {
        val digest = ByteArray(32) { it.toByte() } // a stand-in 32-byte SHA-256
        val manifest = DigestManifest.fromInlineDigests(mapOf("mirth-client.jar" to Base64.getEncoder().encodeToString(digest)))!!
        assertEquals(hex(digest), manifest.expectedFor("mirth-client.jar"))
    }

    @Test
    @DisplayName("a 64-char hex digest is accepted too (case-insensitive)")
    fun hexIsAcceptedAndLowercased() {
        val manifest = DigestManifest.fromInlineDigests(mapOf("a.jar" to ("AB".repeat(32))))!!
        assertEquals("ab".repeat(32), manifest.expectedFor("a.jar"))
    }

    @Test
    @DisplayName("lookups fall back to basename (path href vs basename query)")
    fun basenameFallback() {
        val digest = ByteArray(32) { 7 }
        val manifest = DigestManifest.fromInlineDigests(mapOf("mirth-client.jar" to Base64.getEncoder().encodeToString(digest)))!!
        assertEquals(hex(digest), manifest.expectedFor("webstart/client-lib/mirth-client.jar"))
    }

    @Test
    @DisplayName("no entries -> null, so the caller falls back to the external /digest manifest")
    fun emptyReturnsNull() {
        assertNull(DigestManifest.fromInlineDigests(emptyMap()))
    }

    @Test
    @DisplayName("a value that isn't a 32-byte SHA-256 is rejected (fail closed, no silent admit)")
    fun rejectsBadDigest() {
        val sixteenBytes = Base64.getEncoder().encodeToString(ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) {
            DigestManifest.fromInlineDigests(mapOf("a.jar" to sixteenBytes))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DigestManifest.fromInlineDigests(mapOf("a.jar" to "not-a-digest"))
        }
    }
}
