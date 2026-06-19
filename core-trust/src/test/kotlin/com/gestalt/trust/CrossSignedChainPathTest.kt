/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXParameters
import java.security.cert.TrustAnchor
import java.security.cert.X509Certificate
import java.util.Date

/**
 * Regression test for the cross-signed-root PKIX bug.
 *
 * A real OIE/Mirth (NextGen) jar embeds a chain shaped like
 *   leaf → "Sectigo RSA Code Signing CA" → "USERTrust RSA Certification Authority" → "AAA (Comodo)"
 * where the USERTrust cert is **cross-signed** (same subject as our bundled USERTrust anchor, but issued by
 * the higher Comodo AAA root). The launcher bundles the self-signed USERTrust root, so the chain SHOULD
 * validate — but the old `filterNot { subject in anchors }` dropped the cross-signed USERTrust cert while
 * leaving AAA above it, producing a broken `[leaf, intermediate, topRoot]` path PKIX could not anchor.
 *
 * This reproduces that shape with a throwaway CA hierarchy and pins down both halves:
 *  (1) [JarSignatureVerifier.pathToTrustAnchor] truncates at the anchor-subject cert → a clean leaf→inter path;
 *  (2) that trimmed path PKIX-validates against the self-signed root, while the old filtered path does NOT.
 */
class CrossSignedChainPathTest {

    private val rootKp = rsa()
    private val topKp = rsa()
    private val interKp = rsa()
    private val leafKp = rsa()

    private val rootDn = "CN=Test USERTrust Root,O=Test"   // our bundled anchor's subject
    private val topDn = "CN=Test AAA Top Root,O=Test"      // the higher (NOT bundled) cross-signing root
    private val interDn = "CN=Test Sectigo Intermediate,O=Test"
    private val leafDn = "CN=Test Leaf Signer,O=Test"

    // Self-signed root (the anchor we bundle), the higher top root, and a CROSS-SIGNED root: same subject &
    // key as the self-signed root, but issued by the top root.
    private val root = cert(rootDn, rootKp.public, rootDn, rootKp.private, ca = true, serial = 1)
    private val top = cert(topDn, topKp.public, topDn, topKp.private, ca = true, serial = 2)
    private val crossRoot = cert(rootDn, rootKp.public, topDn, topKp.private, ca = true, serial = 3)
    private val inter = cert(interDn, interKp.public, rootDn, rootKp.private, ca = true, serial = 4)
    private val leaf = cert(leafDn, leafKp.public, interDn, interKp.private, ca = false, serial = 5)

    // What orderChain() produces from the jar's certs: leaf → inter → cross-signed root → top root.
    private val ordered = listOf(leaf, inter, crossRoot, top)
    private val anchorSubjects = setOf(root.subjectX500Principal)

    @Test
    @DisplayName("pathToTrustAnchor truncates at the first anchor-subject cert (drops the cross-signed root AND the top root)")
    fun trimsAtAnchorSubject() {
        val path = JarSignatureVerifier.pathToTrustAnchor(ordered, anchorSubjects)
        assertEquals(listOf(leaf, inter), path) {
            "the path must stop below the anchor-subject cert, not carry the dangling top root"
        }
        // The old subject-only filter left the dangling top root — the exact broken path.
        val oldFiltered = ordered.filterNot { it.subjectX500Principal in anchorSubjects }
        assertEquals(listOf(leaf, inter, top), oldFiltered) { "sanity: the old filter kept the top root" }
    }

    @Test
    @DisplayName("the trimmed path validates against the self-signed root; the old (dangling-top-root) path does not")
    fun trimmedPathValidatesOldDoesnt() {
        assertTrue(pkixValidates(listOf(leaf, inter), root)) {
            "leaf→inter must validate against the bundled self-signed root"
        }
        assertFalse(pkixValidates(listOf(leaf, inter, top), root)) {
            "the old broken path (with the dangling top root) must fail to anchor — this is the bug"
        }
    }

    @Test
    @DisplayName("a normal chain (anchor is the last/root cert) is unaffected by the trim")
    fun normalChainUnaffected() {
        val normal = listOf(leaf, inter, root) // root subject == anchor subject, at the end
        assertEquals(listOf(leaf, inter), JarSignatureVerifier.pathToTrustAnchor(normal, anchorSubjects))
        // No embedded anchor at all → chain returned unchanged.
        assertEquals(listOf(leaf, inter), JarSignatureVerifier.pathToTrustAnchor(listOf(leaf, inter), anchorSubjects))
    }

    // --- helpers ----------------------------------------------------------------------------------

    private fun pkixValidates(path: List<X509Certificate>, anchor: X509Certificate): Boolean {
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(path)
        val params = PKIXParameters(setOf(TrustAnchor(anchor, null))).apply {
            isRevocationEnabled = false
            date = Date(NOW) // validate at the fixed instant the fixtures are valid for (clock-independent)
        }
        return runCatching { CertPathValidator.getInstance("PKIX").validate(certPath, params) }.isSuccess
    }

    private fun rsa(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun cert(
        subjectDn: String,
        subjectPub: PublicKey,
        issuerDn: String,
        issuerPriv: PrivateKey,
        ca: Boolean,
        serial: Long,
    ): X509Certificate {
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuerDn),
            BigInteger.valueOf(serial),
            Date(NOW - 86_400_000L),
            Date(NOW + 3650L * 86_400_000L),
            X500Name(subjectDn),
            subjectPub,
        ).addExtension(Extension.basicConstraints, true, BasicConstraints(ca))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(issuerPriv)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private companion object {
        const val NOW = 1_700_000_000_000L // fixed instant (~Nov 2023); certs valid for ~10y from here
    }
}
