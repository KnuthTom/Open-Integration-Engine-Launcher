/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.app.trust

import com.gestalt.launcher.tls.CertFingerprint
import java.io.InputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant

/**
 * Human-readable view of an X.509 certificate for the trust UI: subject, issuer, SHA-256 fingerprint
 * (in both the colon-grouped display form and the plain lowercase form that matches a stored pin), and
 * the validity window with near-expiry / expired helpers.
 *
 * Pure extraction over the standard JCA [X509Certificate] API — it makes no trust decision. The
 * plain fingerprint is produced by the same [CertFingerprint] the launcher pins against, so a value
 * shown here can be compared directly with [com.gestalt.launcher.model.Connection.pinnedCertSha256].
 */
data class CertDetails(
    val subject: String,
    val issuer: String,
    /** Lowercase hex, no separators — identical to what gets pinned. */
    val fingerprintPlain: String,
    /** Colon-grouped uppercase, e.g. `AB:12:…`, for display only. */
    val fingerprintDisplay: String,
    val notBefore: Instant,
    val notAfter: Instant,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean = now.isAfter(notAfter)

    fun isNotYetValid(now: Instant = Instant.now()): Boolean = now.isBefore(notBefore)

    /** Whole days until expiry (negative once expired). */
    fun daysUntilExpiry(now: Instant = Instant.now()): Long = Duration.between(now, notAfter).toDays()

    /** True when the cert is still valid but expires within [withinDays]. */
    fun isNearExpiry(now: Instant = Instant.now(), withinDays: Long = 30): Boolean =
        !isExpired(now) && daysUntilExpiry(now) <= withinDays

    companion object {
        fun from(cert: X509Certificate): CertDetails {
            val plain = CertFingerprint.sha256Hex(cert)
            return CertDetails(
                subject = cert.subjectX500Principal.name,
                issuer = cert.issuerX500Principal.name,
                fingerprintPlain = plain,
                fingerprintDisplay = grouped(plain),
                notBefore = cert.notBefore.toInstant(),
                notAfter = cert.notAfter.toInstant(),
            )
        }

        /** Load the first X.509 cert from a PEM/DER stream (used for bundled + custom CA anchors). */
        fun readCert(input: InputStream): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate

        /** `ab12cd…` -> `AB:12:CD:…` for legible fingerprints. */
        private fun grouped(plain: String): String =
            plain.uppercase().chunked(2).joinToString(":")
    }
}
