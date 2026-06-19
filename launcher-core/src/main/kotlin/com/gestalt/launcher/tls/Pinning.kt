/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Trust manager for an **exactly pinned** server certificate: the connection is accepted iff the
 * server's leaf certificate's SHA-256 equals the stored pin. Chain validity and hostname are
 * intentionally not consulted here — an exact-cert pin is strictly stronger than PKIX + hostname
 * matching, which is what lets the localhost / SSM-tunnel case work without weakening verification.
 *
 * This is used ONLY when a connection carries a pin. Non-pinned connections use the platform default
 * trust manager (full chain validation) — see [HttpClientFactory].
 */
class PinnedTrustManager(pin: String) : X509TrustManager {
    private val pin = CertFingerprint.normalize(pin)

    init {
        require(this.pin.length == 64) { "a SHA-256 pin must be 64 hex chars, got: '${this.pin}'" }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client authentication is not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("server presented no certificate")
        val actual = CertFingerprint.sha256Hex(leaf)
        if (actual != pin) {
            throw CertificateException("server certificate pin mismatch (expected $pin, got $actual)")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Hostname verifier paired with [PinnedTrustManager]. A hostname mismatch is allowed ONLY for the
 * exactly-pinned certificate: the verifier returns true iff the peer leaf matches the pin, so the
 * pin — not the hostname — is what authenticates the server. For any other certificate it returns
 * false. Never use [org.apache.hc.client5.http.ssl.NoopHostnameVerifier]; this is not a no-op.
 */
class PinnedHostnameVerifier(pin: String) : HostnameVerifier {
    private val pin = CertFingerprint.normalize(pin)

    override fun verify(hostname: String?, session: SSLSession): Boolean {
        val leaf = session.peerCertificates.firstOrNull() as? X509Certificate ?: return false
        return CertFingerprint.sha256Hex(leaf) == pin
    }
}
