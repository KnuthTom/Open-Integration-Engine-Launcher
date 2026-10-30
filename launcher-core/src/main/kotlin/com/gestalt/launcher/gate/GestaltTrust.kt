/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.AppPaths
import com.gestalt.trust.TrustStore
import java.io.InputStream
import java.nio.file.Files
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * Production trust material for the gate:
 *  - the bundled public roots — Sectigo Public Code Signing Root R46 + USERTrust RSA (the OIE/Mirth
 *    chain terminates at these), shipped as classpath resources under `/anchors/`;
 *  - when available, the Gestalt self-signed code-signing certificate (its own anchor), looked up as
 *    a bundled resource or under the per-user data dir;
 *  - the default publisher pin allow-list ([OFFICIAL_SIGNER_PINS]).
 */
object GestaltTrust {

    /** The three pinned publishers, matched by DN attributes so cert renewals survive. */
    val OFFICIAL_SIGNER_PINS: List<SignerPin> = listOf(
        SignerPin.of("Mirth Connect (NextGen Healthcare)", "O" to "NEXTGEN HEALTHCARE, INC."),
        SignerPin.of("Open Integration Engine (BrightCodeCompany)", "O" to "BrightCodeCompany OÜ"),
        SignerPin.of("Gestalt Cloud Connect", "CN" to "Gestalt Cloud Connect Code Signing", "O" to "Gestalt Diagnostics"),
    )

    /** The two bundled public roots. Always present. */
    fun publicRoots(): TrustStore =
        listOf("/anchors/sectigo-code-signing-root-r46.pem", "/anchors/usertrust-rsa-root.pem")
            .map { res ->
                javaClass.getResourceAsStream(res)?.use { TrustStore.fromPem(it) }
                    ?: error("missing bundled anchor resource: $res")
            }
            .reduce { a, b -> a + b }

    /**
     * Full production trust store: the platform's own root CAs (what `jarsigner` trusts — so a publisher
     * whose code-signing chain terminates at any public root validates, not only at the two roots
     * bundled when the OIE chain was last looked at), the bundled public roots, the Gestalt self-signed
     * cert if found, and any [extra] anchors the caller supplies.
     */
    fun productionTrustStore(extra: Collection<X509Certificate> = emptyList()): TrustStore {
        var store = publicRoots()
        val system = systemRoots()
        if (system.isNotEmpty()) store += system
        gestaltCert()?.let { store += listOf(it) }
        if (extra.isNotEmpty()) store += extra
        return store
    }

    /** The root certificates in the JDK's default trust store (cacerts); empty if it can't be read. */
    fun systemRoots(): List<X509Certificate> = systemRootsCached

    private val systemRootsCached: List<X509Certificate> by lazy {
        runCatching {
            val tmf = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as java.security.KeyStore?)
            tmf.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().flatMap { it.acceptedIssuers.toList() }
        }.getOrDefault(emptyList())
    }

    /** The Gestalt self-signed cert, from a bundled resource or `<dataDir>/anchors/gestalt-signing.pem`. */
    fun gestaltCert(): X509Certificate? {
        javaClass.getResourceAsStream("/anchors/gestalt-signing.pem")?.use { return readCert(it) }
        val f = AppPaths.dataDir.resolve("anchors").resolve("gestalt-signing.pem")
        if (Files.isRegularFile(f)) Files.newInputStream(f).use { return readCert(it) }
        return null
    }

    private fun readCert(input: InputStream): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
}
