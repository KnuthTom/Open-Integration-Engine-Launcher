/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate

/**
 * An immutable set of trust anchors that signer chains must terminate at.
 *
 * Anchors come from three sources for Gestalt:
 *   (a) the Sectigo Public Code Signing Root R46 and the USERTrust RSA root (the OIE
 *       jar chains to these), and
 *   (b) the Gestalt self-signed code-signing certificate.
 *
 * Load them from PEM (one or many concatenated certs) or from a PKCS12/JKS keystore.
 */
class TrustStore private constructor(val anchors: Set<X509Certificate>) {

    init {
        require(anchors.isNotEmpty()) { "TrustStore must contain at least one trust anchor" }
    }

    /** The anchors as JCA [TrustAnchor]s (no name constraints). */
    fun trustAnchors(): Set<TrustAnchor> = anchors.mapTo(LinkedHashSet()) { TrustAnchor(it, null) }

    /** Returns a new store with [more] anchors added. */
    operator fun plus(more: Collection<X509Certificate>): TrustStore =
        TrustStore(anchors + more)

    operator fun plus(other: TrustStore): TrustStore =
        TrustStore(anchors + other.anchors)

    companion object {
        private fun x509Factory(): CertificateFactory = CertificateFactory.getInstance("X.509")

        /** Build directly from already-parsed certificates. */
        fun of(certs: Collection<X509Certificate>): TrustStore = TrustStore(certs.toSet())

        fun of(vararg certs: X509Certificate): TrustStore = TrustStore(certs.toSet())

        /** Load one or more PEM-encoded X.509 certificates from a stream (stream is not closed). */
        fun fromPem(input: InputStream): TrustStore {
            val certs = x509Factory().generateCertificates(input).filterIsInstance<X509Certificate>()
            require(certs.isNotEmpty()) { "no X.509 certificates found in PEM input" }
            return TrustStore(certs.toSet())
        }

        /** Load one or more PEM-encoded X.509 certificates from a file. */
        fun fromPem(file: File): TrustStore = file.inputStream().use { fromPem(it) }

        /** Merge anchors from several PEM files into one store. */
        fun fromPemFiles(files: Collection<File>): TrustStore {
            val cf = x509Factory()
            val all = files.flatMap { f -> f.inputStream().use { cf.generateCertificates(it) } }
                .filterIsInstance<X509Certificate>()
            require(all.isNotEmpty()) { "no X.509 certificates found in: $files" }
            return TrustStore(all.toSet())
        }

        /** Load every certificate entry from a keystore (PKCS12 by default). */
        fun fromKeyStore(file: File, password: CharArray?, type: String = "PKCS12"): TrustStore {
            val ks = KeyStore.getInstance(type)
            file.inputStream().use { ks.load(it, password) }
            val certs = ks.aliases().toList()
                .mapNotNull { ks.getCertificate(it) as? X509Certificate }
            require(certs.isNotEmpty()) { "no certificates found in keystore: ${file.path}" }
            return TrustStore(certs.toSet())
        }
    }
}
