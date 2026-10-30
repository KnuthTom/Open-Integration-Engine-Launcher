/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.trust

import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.SignerId
import org.bouncycastle.cms.SignerInformation
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.tsp.TimeStampToken
import org.bouncycastle.util.Selector
import org.bouncycastle.util.Store
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.Security
import java.security.cert.CertPathValidator
import java.security.cert.CertificateFactory
import java.security.cert.PKIXCertPathValidatorResult
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.jar.Attributes
import java.util.jar.Manifest
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal

/**
 * Verifies that a jar is genuinely and intactly signed by a trusted publisher.
 *
 * This deliberately does **not** rely on [java.util.jar.JarFile]'s automatic verification.
 * Instead it does the work explicitly so the trust decision is auditable and timestamp-aware:
 *
 *   1. Parse the PKCS#7 / CMS signature block (META-INF/<name>.RSA|DSA|EC) with BouncyCastle and
 *      verify its signature over the signature file (META-INF/<name>.SF).
 *   2. Confirm the `.SF` digests the `MANIFEST.MF`, and that the manifest's per-entry digests
 *      match the actual bytes of every signed entry (this is what catches a flipped class byte).
 *   3. PKIX path-validate the signer chain to a configured trust anchor.
 *   4. Enforce the codeSigning EKU (id-kp-codeSigning, 1.3.6.1.5.5.7.3.3) on the signer leaf.
 *   5. If the signature carries an RFC-3161 timestamp, validate the timestamp token and use its
 *      instant as the PKIX validity date — so a leaf that is expired *now* but was valid at the
 *      timestamp still validates.
 *   6. Apply the [TrustMode] policy (pin the publisher DN, or accept any chain to an anchor).
 */
class JarSignatureVerifier(
    private val trustStore: TrustStore,
    private val config: VerifierConfig = VerifierConfig(),
) {
    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun verify(path: String): VerificationResult = verify(File(path))

    fun verify(jar: File): VerificationResult {
        if (!jar.isFile) {
            return VerificationResult.failure("jar not found: ${jar.path}", config.trustMode)
        }
        return try {
            ZipFile(jar).use { zip -> verifyOpen(zip) }
        } catch (e: Exception) {
            VerificationResult.failure(
                "could not read jar: ${e.javaClass.simpleName}: ${e.message}", config.trustMode,
            )
        }
    }

    private fun verifyOpen(zip: ZipFile): VerificationResult {
        val mode = config.trustMode

        // --- locate manifest + signature block (raw bytes; no auto-verification) ---
        val manifestBytes = zip.readEntry("META-INF/MANIFEST.MF")
            ?: return VerificationResult.failure("jar has no manifest", mode)

        val names = zip.entries().asSequence().map { it.name }.toList()
        val sfName = names.firstOrNull { it.matches(SF_RE) }
        val blockName = sfName?.let { sf ->
            val base = sf.substringBeforeLast('.')
            names.firstOrNull {
                it != sf && it.substringBeforeLast('.') == base &&
                    it.substringAfterLast('.').uppercase() in BLOCK_EXTS
            }
        }
        if (sfName == null || blockName == null) {
            return VerificationResult.failure("jar is not signed (no .SF / signature block found)", mode)
        }
        val sfBytes = zip.readEntry(sfName)!!
        val blockBytes = zip.readEntry(blockName)!!

        // --- 1. parse CMS, pick signer, verify signature over the .SF ---
        val cms = try {
            CMSSignedData(CMSProcessableByteArray(sfBytes), blockBytes)
        } catch (e: Exception) {
            return VerificationResult.failure("malformed signature block: ${e.message}", mode)
        }
        val signer: SignerInformation = cms.signerInfos.signers.firstOrNull()
            ?: return VerificationResult.failure("signature block contains no signers", mode)

        val converter = JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME)
        val allCerts = cms.certificates.getMatches(null).map { converter.getCertificate(it) }
        val signerHolder: X509CertificateHolder = certsMatching(cms.certificates, signer.sid).firstOrNull()
            ?: return VerificationResult.failure("signer certificate not present in signature block", mode)
        val leaf = converter.getCertificate(signerHolder)
        val signerDN = leaf.subjectX500Principal.name

        val sigOk = try {
            signer.verify(
                JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(signerHolder),
            )
        } catch (e: Exception) {
            false
        }
        if (!sigOk) {
            return VerificationResult.failure(
                "signature over .SF is invalid (jar signature does not verify)", mode, signerDN,
            )
        }

        // --- 5(a). RFC-3161 timestamp: validate token, derive validity instant ---
        val ts = extractTimestamp(signer)
        val usedTimestamp = ts != null
        val validityDate = ts?.instant?.let(Date::from) ?: Date()

        // --- 2. content integrity: .SF digests manifest; manifest digests each entry ---
        verifyManifestDigest(sfBytes, manifestBytes)?.let {
            return VerificationResult.failure(it, mode, signerDN, usedTimestamp = usedTimestamp, timestampInstant = ts?.instant)
        }
        verifyEntryDigests(zip, manifestBytes)?.let {
            return VerificationResult.failure(it, mode, signerDN, usedTimestamp = usedTimestamp, timestampInstant = ts?.instant)
        }

        // --- 3. PKIX path validation to a trust anchor (at the timestamp instant if present) ---
        val anchorSubjects = trustStore.anchors.map { it.subjectX500Principal }.toSet()
        val ordered = orderChain(allCerts, leaf)
        val validatedChain: List<X509Certificate>
        val leafIsAnchor = trustStore.anchors.any {
            it.subjectX500Principal == leaf.subjectX500Principal && it.publicKey == leaf.publicKey
        }
        if (leafIsAnchor) {
            // Self-signed / directly-pinned leaf: it *is* the anchor, so there is no path to build.
            try {
                leaf.checkValidity(validityDate)
            } catch (e: Exception) {
                return VerificationResult.failure(
                    "signer certificate not valid at ${validityDate.toInstant()}: ${e.message}",
                    mode, signerDN, listOf(leaf), usedTimestamp, ts?.instant, integrityVerified = true,
                )
            }
            validatedChain = listOf(leaf)
        } else {
            val pathCerts = pathToTrustAnchor(ordered, anchorSubjects)
            if (pathCerts.isEmpty()) {
                return VerificationResult.failure(
                    "no certification path could be built from the signer to a trust anchor",
                    mode, signerDN, ordered, usedTimestamp, ts?.instant, integrityVerified = true,
                )
            }
            val anchorCert = try {
                pkixValidate(pathCerts, validityDate)
            } catch (e: Exception) {
                return VerificationResult.failure(
                    "PKIX path validation failed: ${e.message}",
                    mode, signerDN, ordered, usedTimestamp, ts?.instant, integrityVerified = true,
                )
            }
            validatedChain = pathCerts + anchorCert
        }

        // --- 4. enforce codeSigning EKU on the leaf ---
        val ekus = leaf.extendedKeyUsage
        if (ekus == null || CODE_SIGNING_EKU !in ekus) {
            return VerificationResult.failure(
                "signer leaf does not assert the codeSigning EKU",
                mode, signerDN, validatedChain, usedTimestamp, ts?.instant, integrityVerified = true,
            )
        }

        // --- 6. trust-mode policy ---
        if (mode == TrustMode.PIN_KNOWN_SIGNERS && !matchesPin(leaf)) {
            return VerificationResult.failure(
                "signer is not on the pinned allow-list: $signerDN",
                mode, signerDN, validatedChain, usedTimestamp, ts?.instant, integrityVerified = true,
            )
        }

        return VerificationResult.success(signerDN, validatedChain, usedTimestamp, ts?.instant, mode)
    }

    // ------------------------------------------------------------------------------------------

    private fun matchesPin(leaf: X509Certificate): Boolean {
        val subject = leaf.subjectX500Principal
        // Canonical X.500 equality first; fall back to a normalized string compare for robustness.
        if (config.pinnedPrincipals.any { it == subject }) return true
        val name = subject.name
        return config.pinnedSubjectDNs.any { it.equals(name, ignoreCase = true) }
    }

    /** Walk leaf -> issuer -> ... using subject/issuer links among [certs]; stops at a self-signed cert. */
    private fun orderChain(certs: Collection<X509Certificate>, leaf: X509Certificate): List<X509Certificate> {
        val bySubject = certs.associateBy { it.subjectX500Principal }
        val ordered = mutableListOf(leaf)
        val seen = hashSetOf(leaf)
        var current = leaf
        while (current.issuerX500Principal != current.subjectX500Principal) {
            val issuer = bySubject[current.issuerX500Principal] ?: break
            if (!seen.add(issuer)) break
            ordered += issuer
            current = issuer
        }
        return ordered
    }

    private fun pkixValidate(pathCerts: List<X509Certificate>, date: Date): X509Certificate {
        val certPath = CertificateFactory.getInstance("X.509").generateCertPath(pathCerts)
        val params = PKIXParameters(trustStore.trustAnchors()).apply {
            isRevocationEnabled = false // no OCSP/CRL fetch in this phase; see INTERFACE-SPEC TODO
            this.date = date
        }
        val validator = try {
            CertPathValidator.getInstance("PKIX", BouncyCastleProvider.PROVIDER_NAME)
        } catch (e: Exception) {
            CertPathValidator.getInstance("PKIX")
        }
        val result = validator.validate(certPath, params) as PKIXCertPathValidatorResult
        return result.trustAnchor.trustedCert
            ?: error("trust anchor has no certificate")
    }

    /** Returns null on success, or a failure message. */
    private fun verifyManifestDigest(sfBytes: ByteArray, manifestBytes: ByteArray): String? {
        val sf = Manifest(ByteArrayInputStream(sfBytes))
        val main = sf.mainAttributes
        for ((header, alg) in MANIFEST_DIGEST_HEADERS) {
            val expected = main.getValue(header) ?: continue
            val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance(alg).digest(manifestBytes))
            return if (actual == expected) null
            else "signature file ($alg) does not match MANIFEST.MF — manifest was altered after signing"
        }
        return "signature file does not digest the manifest (no *-Digest-Manifest header); cannot establish manifest integrity"
    }

    /**
     * Returns null on success, or a failure message.
     *
     * Iterates the **actual** jar entries (not merely the manifest's listed sections) and requires
     * every content entry to be covered by a signed manifest section whose digest matches the entry
     * bytes. This closes the classic "files not listed in the manifest are not verified" gap: an
     * attacker cannot add (or leave behind) an unsigned class in an otherwise validly-signed jar and
     * have it admitted — every byte that can end up on the classpath must be under the signature.
     */
    private fun verifyEntryDigests(zip: ZipFile, manifestBytes: ByteArray): String? {
        val mf = Manifest(ByteArrayInputStream(manifestBytes))
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue
            val name = entry.name
            if (isSignatureFile(name)) continue
            val attrs = mf.entries[name]
                ?: return "jar contains an entry not covered by the signed manifest: $name"
            val (alg, expected) = pickDigest(attrs)
                ?: return "manifest section carries no digest (entry is not signed): $name"
            val data = zip.getInputStream(entry).use { it.readBytes() }
            val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance(alg).digest(data))
            if (actual != expected) {
                return "entry digest mismatch (tampered content): $name"
            }
        }
        // Defense in depth: an entry the manifest signs must actually be present in the jar.
        for ((name, attrs) in mf.entries) {
            if (pickDigest(attrs) != null && zip.getEntry(name) == null) {
                return "manifest lists a signed entry that is missing from the jar: $name"
            }
        }
        return null
    }

    /** True only for the JAR signature metadata itself (which is, correctly, not self-covered). */
    private fun isSignatureFile(name: String): Boolean {
        val upper = name.uppercase()
        if (upper == "META-INF/MANIFEST.MF") return true
        if (!upper.startsWith("META-INF/")) return false
        val rest = upper.removePrefix("META-INF/")
        if (rest.contains('/')) return false // e.g. META-INF/services/* is real content and must be signed
        val ext = rest.substringAfterLast('.', "")
        return ext == "SF" || ext in BLOCK_EXTS || rest.startsWith("SIG-")
    }

    private fun pickDigest(attrs: Attributes): Pair<String, String>? {
        for ((header, alg) in ENTRY_DIGEST_HEADERS) {
            attrs.getValue(header)?.let { return alg to it }
        }
        return null
    }

    private data class Timestamp(val instant: Instant, val tsaCert: X509Certificate)

    /** Extracts and validates an RFC-3161 timestamp from the signer's unsigned attributes, if any. */
    private fun extractTimestamp(signer: SignerInformation): Timestamp? {
        val unsigned = signer.unsignedAttributes ?: return null
        val attr = unsigned.get(PKCSObjectIdentifiers.id_aa_signatureTimeStampToken) ?: return null
        if (attr.attributeValues.isEmpty()) return null
        return try {
            val token = TimeStampToken(ContentInfo.getInstance(attr.attributeValues[0]))
            val tsaHolder = certsMatching(token.certificates, token.sid).firstOrNull() ?: return null
            // The token must be signed by the TSA cert it carries...
            token.validate(
                JcaSimpleSignerInfoVerifierBuilder()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build(tsaHolder),
            )
            // ...and must actually cover *this* signature (messageImprint == hash(signer.signature)).
            val tsi = token.timeStampInfo
            val md = MessageDigest.getInstance(tsi.messageImprintAlgOID.id, BouncyCastleProvider.PROVIDER_NAME)
            if (!md.digest(signer.signature).contentEquals(tsi.messageImprintDigest)) return null
            val tsaCert = JcaX509CertificateConverter()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .getCertificate(tsaHolder)
            Timestamp(tsi.genTime.toInstant(), tsaCert)
        } catch (e: Exception) {
            null // present-but-unusable timestamp => treat as no timestamp (validate at "now")
        }
    }

    private fun ZipFile.readEntry(name: String): ByteArray? =
        getEntry(name)?.let { e -> getInputStream(e).use { it.readBytes() } }

    // BouncyCastle's SignerId implements the *raw* Selector, so Kotlin won't auto-assign it to
    // Selector<X509CertificateHolder>. SignerId only ever selects cert holders, so the cast is safe.
    @Suppress("UNCHECKED_CAST")
    private fun certsMatching(
        store: Store<X509CertificateHolder>,
        sid: SignerId,
    ): Collection<X509CertificateHolder> = store.getMatches(sid as Selector<X509CertificateHolder>)

    companion object {
        /**
         * Trim an ordered leaf→root chain to the certs **below** the first one whose subject is a trust
         * anchor, dropping that cert and everything above it.
         *
         * A jar may embed an extra **cross-signed** copy of a root: a cert with the same subject as one of
         * our trust anchors (e.g. "USERTrust RSA Certification Authority") but issued by yet another root
         * (e.g. Comodo "AAA Certificate Services") for legacy-device compatibility. Filtering the chain only
         * by subject would drop that cross-signed cert while LEAVING the higher root above it, yielding a
         * broken, non-contiguous path (`[leaf, intermediate, topRoot]`) that PKIX can't anchor —
         * "Trust anchor for certification path not found" — even though we DO hold the matching root.
         * Truncating at the first anchor-subject cert instead leaves a clean `[leaf, intermediate]` path
         * that validates against the anchor we hold. Returns the chain unchanged when nothing in it matches
         * an anchor subject (the anchor is supplied only via the trust store).
         */
        internal fun pathToTrustAnchor(
            ordered: List<X509Certificate>,
            anchorSubjects: Set<X500Principal>,
        ): List<X509Certificate> {
            val idx = ordered.indexOfFirst { it.subjectX500Principal in anchorSubjects }
            return if (idx >= 0) ordered.take(idx) else ordered
        }

        private const val CODE_SIGNING_EKU = "1.3.6.1.5.5.7.3.3" // id-kp-codeSigning
        private val SF_RE = Regex("(?i)META-INF/[^/]+\\.SF")
        private val BLOCK_EXTS = setOf("RSA", "DSA", "EC")

        // Strongest first.
        private val ENTRY_DIGEST_HEADERS = linkedMapOf(
            "SHA-512-Digest" to "SHA-512",
            "SHA-384-Digest" to "SHA-384",
            "SHA-256-Digest" to "SHA-256",
            "SHA1-Digest" to "SHA-1",
        )
        private val MANIFEST_DIGEST_HEADERS = linkedMapOf(
            "SHA-512-Digest-Manifest" to "SHA-512",
            "SHA-384-Digest-Manifest" to "SHA-384",
            "SHA-256-Digest-Manifest" to "SHA-256",
            "SHA1-Digest-Manifest" to "SHA-1",
        )
    }
}
