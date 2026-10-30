/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.security.cert.X509Certificate
import java.time.Duration

/**
 * Cert-detail extraction is tested against a real, bundled CA root (shipped by :launcher-core as a
 * classpath resource) — deterministic, no certificate generation needed.
 */
class CertDetailsTest {

    private fun usertrustRoot(): X509Certificate =
        javaClass.getResourceAsStream("/anchors/usertrust-rsa-root.pem")
            .let { requireNotNull(it) { "bundled anchor resource missing" } }
            .use { CertDetails.readCert(it) }

    @Test
    @DisplayName("extracts subject, issuer, fingerprint, and validity from a real certificate")
    fun extraction() {
        val cert = usertrustRoot()
        val d = CertDetails.from(cert)

        assertTrue(d.subject.contains("USERTrust", ignoreCase = true)) { "subject was ${d.subject}" }
        // A root is self-signed: issuer == subject.
        assertEquals(d.subject, d.issuer)

        assertTrue(d.fingerprintPlain.matches(Regex("[0-9a-f]{64}"))) { "fp was ${d.fingerprintPlain}" }
        assertEquals(CertFingerprint.sha256Hex(cert), d.fingerprintPlain)
        assertEquals(d.fingerprintPlain, d.fingerprintDisplay.replace(":", "").lowercase())
        assertEquals(31, d.fingerprintDisplay.count { it == ':' }) { "32 bytes => 31 separators" }

        assertTrue(d.notBefore.isBefore(d.notAfter))
    }

    @Test
    @DisplayName("expiry helpers are deterministic relative to a supplied instant")
    fun expiryHelpers() {
        val d = CertDetails.from(usertrustRoot())

        assertTrue(d.isExpired(d.notAfter.plusSeconds(1)))
        assertFalse(d.isExpired(d.notBefore.plusSeconds(1)))
        assertTrue(d.isNotYetValid(d.notBefore.minusSeconds(1)))

        val tenDaysLeft = d.notAfter.minus(Duration.ofDays(10))
        assertTrue(d.isNearExpiry(tenDaysLeft, withinDays = 30))
        assertFalse(d.isNearExpiry(d.notAfter.minus(Duration.ofDays(60)), withinDays = 30))
        assertEquals(10L, d.daysUntilExpiry(tenDaysLeft))
    }
}
