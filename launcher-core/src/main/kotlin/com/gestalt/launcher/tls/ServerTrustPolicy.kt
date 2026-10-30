/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** The server presented a certificate the system CAs don't validate and the user hasn't reviewed. */
class UnknownServerCertificateException(val chain: List<X509Certificate>, cause: Throwable? = null) :
    CertificateException("server certificate is not trusted by the system CAs and has not been reviewed: ${chain.firstOrNull()?.subjectX500Principal?.name ?: "(no certificate)"}", cause)

/** The server presented a certificate the user explicitly rejected. */
class RejectedServerCertificateException(val chain: List<X509Certificate>) :
    CertificateException("server certificate was rejected by the user: ${chain.firstOrNull()?.subjectX500Principal?.name ?: "(no certificate)"}")

/**
 * The launcher's one TLS trust rule, applied to every https fetch:
 *
 *  1. a leaf the user REJECTED fails, whatever the chain says;
 *  2. a leaf the user TRUSTED (or auto-approved) is accepted by exact fingerprint — chain and hostname
 *     are not consulted, which is what self-signed engines and port-forwards need;
 *  3. anything else must validate against the platform trust store (full PKIX), and the hostname must
 *     match (see [TrustedOrDefaultHostnameVerifier]); a PKIX failure surfaces as
 *     [UnknownServerCertificateException] carrying the chain so the caller can offer a review.
 *
 * There is no trust-all path: an unknown certificate is never accepted silently.
 */
class TrustedOrPkixTrustManager(
    trusted: Set<String>,
    rejected: Set<String>,
    private val system: X509TrustManager = systemTrustManager(),
) : X509TrustManager {
    private val trusted = trusted.mapTo(HashSet()) { CertFingerprint.normalize(it) }
    private val rejected = rejected.mapTo(HashSet()) { CertFingerprint.normalize(it) }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client authentication is not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("server presented no certificate")
        val fp = CertFingerprint.sha256Hex(leaf)
        if (fp in rejected) throw RejectedServerCertificateException(chain.toList())
        if (fp in trusted) return
        try {
            system.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            throw UnknownServerCertificateException(chain.toList(), e)
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    companion object {
        /** The JDK's default X.509 trust manager over the platform trust store (cacerts). */
        fun systemTrustManager(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}

/**
 * Hostname rule paired with [TrustedOrPkixTrustManager]: a trusted-by-fingerprint leaf passes whatever
 * name it carries (the fingerprint is the authority); any other leaf gets the standard RFC 2818 check.
 */
class TrustedOrDefaultHostnameVerifier(trusted: Set<String>) : HostnameVerifier {
    private val trusted = trusted.mapTo(HashSet()) { CertFingerprint.normalize(it) }
    private val default = DefaultHostnameVerifier()

    override fun verify(hostname: String?, session: SSLSession): Boolean {
        val leaf = runCatching { session.peerCertificates.firstOrNull() as? X509Certificate }.getOrNull() ?: return false
        if (CertFingerprint.sha256Hex(leaf) in trusted) return true
        return default.verify(hostname, session)
    }
}

/** Classifies a fetch failure as a server-trust problem the user could resolve by reviewing the certificate. */
object ServerTrustFailure {
    enum class Kind { UNKNOWN, REJECTED }

    /** The trust failure in [t]'s cause chain, or null when the failure is something else (connect, HTTP, …). */
    fun kindOf(t: Throwable): Kind? {
        var cur: Throwable? = t
        val seen = java.util.IdentityHashMap<Throwable, Boolean>()
        while (cur != null && seen.put(cur, true) == null) {
            when (cur) {
                is RejectedServerCertificateException -> return Kind.REJECTED
                is UnknownServerCertificateException -> return Kind.UNKNOWN
                // Hostname mismatch on a chain the system trusts: the verifier said no. Reviewable too.
                is SSLPeerUnverifiedException -> return Kind.UNKNOWN
            }
            cur = cur.cause
        }
        return null
    }
}
