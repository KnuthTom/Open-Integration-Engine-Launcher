/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.testutil

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import java.util.concurrent.atomic.AtomicLong
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Builds real signed/unsigned jars and certificate chains with BouncyCastle, so the gate /
 * signature-enforcement / TLS tests are hermetic and need no committed binary fixtures.
 *
 * The signed jars are produced exactly the way `JarSignatureVerifier` expects: a MANIFEST.MF with
 * per-entry SHA-256 digests, a `.SF` with a SHA-256-Digest-Manifest, and a detached CMS/PKCS#7 block
 * over the `.SF` carrying the leaf + CA chain.
 */
object TestArtifacts {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private val serials = AtomicLong(System.currentTimeMillis())

    data class Ca(val keyPair: KeyPair, val cert: X509Certificate)

    /** Leaf signing material: its key, and the chain [leaf, ca] to embed in the signature. */
    data class Leaf(val keyPair: KeyPair, val chain: List<X509Certificate>) {
        val cert: X509Certificate get() = chain.first()
    }

    fun rsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    fun selfSignedCa(dn: String): Ca {
        val kp = rsaKeyPair()
        val name = X500Name(dn)
        val cert = buildCert(
            subject = name, issuer = name, subjectKey = kp, signerKey = kp.private,
        ) { b ->
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
        }
        return Ca(kp, cert)
    }

    /** A code-signing leaf (codeSigning EKU, digitalSignature KU) issued by [issuer]. */
    fun codeSigningLeaf(subjectDn: String, issuer: Ca): Leaf {
        val kp = rsaKeyPair()
        // Take the issuer name straight from the CA cert (byte-identical encoding) so PKIX name
        // chaining matches; round-tripping through a DN string can re-encode and break the link.
        val issuerName = JcaX509CertificateHolder(issuer.cert).subject
        val cert = buildCert(
            subject = X500Name(subjectDn), issuer = issuerName,
            subjectKey = kp, signerKey = issuer.keyPair.private,
        ) { b ->
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            b.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_codeSigning))
        }
        return Leaf(kp, listOf(cert, issuer.cert))
    }

    /** A self-signed TLS server certificate with a SAN dNSName of [sanDns]. */
    fun serverCert(commonName: String, sanDns: String): Pair<KeyPair, X509Certificate> {
        val kp = rsaKeyPair()
        val name = X500Name("CN=$commonName")
        val cert = buildCert(subject = name, issuer = name, subjectKey = kp, signerKey = kp.private) { b ->
            b.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            b.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
            b.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(GeneralName(GeneralName.dNSName, sanDns)),
            )
        }
        return kp to cert
    }

    /**
     * @param unsignedExtras entries written to the jar but deliberately NOT included in the signed
     *   manifest — i.e. "stowaway" content. A correct verifier must reject a jar that contains any.
     */
    fun writeSignedJar(
        file: Path,
        entries: Map<String, ByteArray>,
        signerKey: PrivateKey,
        chain: List<X509Certificate>,
        unsignedExtras: Map<String, ByteArray> = emptyMap(),
    ) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Created-By", "Gestalt test harness")
            for ((name, data) in entries) {
                getEntries()[name] = Attributes().apply {
                    putValue("SHA-256-Digest", b64(sha256(data)))
                }
            }
        }
        val manifestBytes = ByteArrayOutputStream().also { manifest.write(it) }.toByteArray()

        val sf = Manifest().apply {
            mainAttributes.putValue("Signature-Version", "1.0")
            mainAttributes.putValue("SHA-256-Digest-Manifest", b64(sha256(manifestBytes)))
        }
        val sfBytes = ByteArrayOutputStream().also { sf.write(it) }.toByteArray()

        val signerCert = chain.first() as X509Certificate
        val sigGen = JcaSimpleSignerInfoGeneratorBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build("SHA256withRSA", signerKey, signerCert)
        val gen = CMSSignedDataGenerator().apply {
            addSignerInfoGenerator(sigGen)
            addCertificates(JcaCertStore(chain))
        }
        val blockBytes = gen.generate(CMSProcessableByteArray(sfBytes), false).encoded

        Files.createDirectories(file.parent)
        JarOutputStream(Files.newOutputStream(file)).use { jos ->
            putEntry(jos, "META-INF/MANIFEST.MF", manifestBytes)
            putEntry(jos, "META-INF/GESTALT.SF", sfBytes)
            putEntry(jos, "META-INF/GESTALT.RSA", blockBytes)
            for ((name, data) in entries) putEntry(jos, name, data)
            for ((name, data) in unsignedExtras) putEntry(jos, name, data) // not in the manifest
        }
    }

    fun writeUnsignedJar(file: Path, entries: Map<String, ByteArray>) {
        val manifest = Manifest().apply { mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0" }
        Files.createDirectories(file.parent)
        JarOutputStream(Files.newOutputStream(file), manifest).use { jos ->
            for ((name, data) in entries) putEntry(jos, name, data)
        }
    }

    fun sha256Hex(file: Path): String = sha256(Files.readAllBytes(file)).joinToString("") { "%02x".format(it) }

    fun digestManifestText(vararg named: Pair<String, Path>): String =
        named.joinToString("\n") { (name, path) -> "${sha256Hex(path)}  $name" }

    // --- internals --------------------------------------------------------------------------------

    private fun buildCert(
        subject: X500Name,
        issuer: X500Name,
        subjectKey: KeyPair,
        signerKey: PrivateKey,
        addExtensions: (JcaX509v3CertificateBuilder) -> Unit,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L)
        val notAfter = Date(now + 3650L * 86_400_000L)
        val builder = JcaX509v3CertificateBuilder(
            issuer, BigInteger.valueOf(serials.incrementAndGet()), notBefore, notAfter, subject, subjectKey.public,
        )
        addExtensions(builder)
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(signerKey)
        return JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(builder.build(signer))
    }

    private fun putEntry(jos: JarOutputStream, name: String, data: ByteArray) {
        jos.putNextEntry(JarEntry(name))
        jos.write(data)
        jos.closeEntry()
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}
