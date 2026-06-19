/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class UpdateCryptoTest {

    private fun manifest(version: String) = UpdateManifest(
        version = version,
        notes = "release $version",
        perOS = linkedMapOf(
            "windows-x64" to ArtifactRef("https://h/gcc-$version.msi", "ab".repeat(32)),
            "macos-arm64" to ArtifactRef("https://h/gcc-$version.dmg", "cd".repeat(32)),
        ),
    )

    @Test
    @DisplayName("canonical signable bytes are independent of perOS insertion order")
    fun canonicalOrderIndependent() {
        val a = UpdateManifest("1.0.0", "n", linkedMapOf("b" to ArtifactRef("u2", "22"), "a" to ArtifactRef("u1", "11")))
        val b = UpdateManifest("1.0.0", "n", linkedMapOf("a" to ArtifactRef("u1", "11"), "b" to ArtifactRef("u2", "22")))
        assertArrayEquals(UpdateCrypto.signableBytes(a), UpdateCrypto.signableBytes(b))
    }

    @Test
    @DisplayName("sign then verify round-trips with the matching pinned key")
    fun signVerifyRoundTrip() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        val m = manifest("1.2.0")
        val signed = m.copy(signature = UpdateCrypto.sign(priv, m))
        assertTrue(UpdateCrypto.verify(signed, listOf(pub)))
    }

    @Test
    @DisplayName("verification fails for a different key, and for any tampered field")
    fun verifyFailsOnWrongKeyOrTamper() {
        val (priv, pub) = UpdateCrypto.generateKeyPair()
        val (_, otherPub) = UpdateCrypto.generateKeyPair()
        val m = manifest("1.2.0")
        val signed = m.copy(signature = UpdateCrypto.sign(priv, m))

        assertFalse(UpdateCrypto.verify(signed, listOf(otherPub)), "wrong key must not verify")
        assertFalse(UpdateCrypto.verify(signed, emptyList()), "no pinned key must not verify")
        // Tamper the version while keeping the old signature.
        assertFalse(UpdateCrypto.verify(signed.copy(version = "9.9.9"), listOf(pub)), "version tamper must be caught")
        // Tamper an artifact sha256.
        val tampered = signed.copy(perOS = signed.perOS + ("windows-x64" to ArtifactRef("https://h/gcc-1.2.0.msi", "00".repeat(32))))
        assertFalse(UpdateCrypto.verify(tampered, listOf(pub)), "artifact tamper must be caught")
    }

    @Test
    fun sha256KnownVector() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", UpdateCrypto.sha256(ByteArray(0)))
    }

    @Test
    @DisplayName("SemVer: only a strictly-newer version is treated as an update")
    fun semverGating() {
        assertTrue(SemVer.isNewer("1.1.0", "1.0.0"))
        assertTrue(SemVer.isNewer("1.10.0", "1.9.0"))
        assertTrue(SemVer.isNewer("2.0.0", "1.9.9"))
        assertFalse(SemVer.isNewer("1.0.0", "1.0.0"), "equal is not newer")
        assertFalse(SemVer.isNewer("1.0.0", "1.1.0"), "older is not newer")
        assertFalse(SemVer.isNewer("1.0.0-rc1", "1.0.0"), "pre-release core equals release -> not newer")
    }
}
