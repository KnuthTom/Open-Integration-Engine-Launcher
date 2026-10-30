/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.credential

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.model.Connection
import com.gestalt.launcher.model.ConnectionStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CredentialAndConnectionStoreTest {

    @Test
    @DisplayName("connections.json persists non-secret fields only; never a credential")
    fun connectionsJsonNeverContainsCredentials(@TempDir dir: Path) {
        val file = dir.resolve("connections.json")
        val store = ConnectionStore(file)
        val conn = Connection(id = "c1", name = "Prod", serverUrl = "https://localhost:8443/", username = "alice")
        store.add(conn)

        // The secret goes to the credential store — NOT to connections.json.
        val creds = InMemoryCredentialStore()
        creds.store(conn.id, "alice", "SuperSecret123!".toCharArray())

        val text = Files.readString(file)
        assertFalse(text.contains("SuperSecret123!")) { "password must never appear in connections.json" }
        assertFalse(text.contains("password", ignoreCase = true)) { "no credential-looking key allowed" }
        assertTrue(text.contains("alice")) { "username is non-secret and is expected to persist" }
    }

    @Test
    @DisplayName("secret scan refuses to persist a credential-looking field")
    fun secretScanRejectsCredentialKey() {
        assertThrows(IllegalStateException::class.java) {
            ConnectionStore.secretScan("""{ "username": "x", "password": "y" }""")
        }
    }

    @Test
    @DisplayName("a credential round-trips through the CredentialStore")
    fun credentialRoundTrips() {
        val store: CredentialStore =
            if (AppPaths.isWindows) AuditingCredentialStore(WindowsDpapiCredentialStore()) else InMemoryCredentialStore()
        assertFalse(store.exists("conn-rt", "bob"))
        store.store("conn-rt", "bob", "rt-secret-987".toCharArray())
        assertTrue(store.exists("conn-rt", "bob")) { "existence is answered without reading the secret where the platform allows" }
        val got = store.retrieve("conn-rt", "bob")
        assertNotNull(got)
        assertEquals("rt-secret-987", String(got!!))
        store.delete("conn-rt", "bob")
        assertNull(store.retrieve("conn-rt", "bob"))
        assertFalse(store.exists("conn-rt", "bob"))
    }

    @Test
    @DisplayName("DPAPI blob on disk does not contain the plaintext (Windows)")
    fun dpapiBlobHasNoPlaintext() {
        assumeTrue(AppPaths.isWindows) { "SKIP: DPAPI is Windows-only" }
        val store = WindowsDpapiCredentialStore()
        val needle = "plaintext-needle-42"
        store.store("conn-blob", "carol", needle.toCharArray())
        val needleBytes = needle.toByteArray()
        val anyContains = Files.list(AppPaths.credentialsDir).use { stream ->
            stream.toList().any { containsBytes(Files.readAllBytes(it), needleBytes) }
        }
        assertFalse(anyContains) { "the DPAPI-protected blob must not contain the plaintext secret" }
        store.delete("conn-blob", "carol")
    }

    private fun containsBytes(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
