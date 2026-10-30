/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.launcher.tls.CertTrustStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/**
 * One code-signing publisher the launcher has met: the exact signer leaf, keyed by its SHA-256. The
 * user's decision about a publisher applies to that certificate only — a renewed signing certificate
 * is a new publisher to review.
 */
@Serializable
data class TrustedSigner(
    val sha256: String,
    val status: CertTrustStatus,
    val subject: String,
    val issuer: String,
    val notBefore: String? = null,
    val notAfter: String? = null,
    /** Jar artifact names this publisher was seen signing (server-relative paths). */
    val artifacts: List<String> = emptyList(),
    val connectionIds: List<String> = emptyList(),
    val addedAt: String? = null,
    val decidedAt: String? = null,
    /** Base64 DER of the signer certificate, for display without a jar in hand. */
    val der: String? = null,
) {
    /** The publisher as a person would name it: the subject's CN or O, else the full DN. */
    val displayName: String
        get() = Regex("(?:^|,)\\s*(?:CN|O)=([^,]+)").find(subject)?.groupValues?.get(1)?.trim() ?: subject
}

@Serializable
private data class TrustedSignersFile(val version: Int = 1, val signers: List<TrustedSigner> = emptyList())

/**
 * The user's decisions about jar publishers — the trust-on-first-use counterpart of the server
 * certificate store, for the code-signing gate:
 *
 *  - a jar whose signature and content digests verify but whose signer the gate doesn't accept (not on
 *    the pinned allow-list, or chaining to no anchor — an in-house extension signed with a self-made
 *    key, say) is recorded as [CertTrustStatus.PENDING] and the launch stops for review;
 *  - a [CertTrustStatus.TRUSTED] publisher is admitted by exact signer fingerprint, which stands in for
 *    the chain, the codeSigning EKU and the DN pin (the integrity checks still apply to every jar);
 *  - a [CertTrustStatus.REJECTED] publisher fails closed until the decision is changed.
 *
 * One JSON file under the data dir, written atomically; every operation is locked and audited.
 */
class TrustedSignerStore(private val file: Path = AppPaths.dataDir.resolve(FILE_NAME)) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val entries = LinkedHashMap<String, TrustedSigner>()

    init {
        load()
    }

    fun list(): List<TrustedSigner> = synchronized(lock) { entries.values.toList() }
    fun get(sha256: String): TrustedSigner? = synchronized(lock) { entries[CertFingerprint.normalize(sha256)] }
    fun statusOf(leaf: X509Certificate): CertTrustStatus? = get(CertFingerprint.sha256Hex(leaf))?.status
    fun pending(): List<TrustedSigner> = list().filter { it.status == CertTrustStatus.PENDING }

    fun trust(leaf: X509Certificate, artifact: String? = null, connectionId: String? = null): TrustedSigner =
        decide(leaf, CertTrustStatus.TRUSTED, artifact, connectionId).also {
            AuditLog.event("publisher.trust", "sha256" to it.sha256, "subject" to it.subject, "connection" to (connectionId ?: "-"))
        }

    fun reject(leaf: X509Certificate, artifact: String? = null, connectionId: String? = null): TrustedSigner =
        decide(leaf, CertTrustStatus.REJECTED, artifact, connectionId).also {
            AuditLog.event("publisher.reject", "sha256" to it.sha256, "subject" to it.subject, "connection" to (connectionId ?: "-"))
        }

    /** Decide by fingerprint for an entry already on file (the UI acts on listed entries). */
    fun trust(sha256: String): TrustedSigner? = decideListed(sha256, CertTrustStatus.TRUSTED)
    fun reject(sha256: String): TrustedSigner? = decideListed(sha256, CertTrustStatus.REJECTED)

    /** Record an unknown publisher as awaiting review; never downgrades a decision. */
    fun markPending(leaf: X509Certificate, artifact: String? = null, connectionId: String? = null): TrustedSigner = synchronized(lock) {
        val fp = CertFingerprint.sha256Hex(leaf)
        val existing = entries[fp]
        val updated = (existing ?: fromCert(leaf, CertTrustStatus.PENDING)).withSeen(artifact, connectionId)
        entries[fp] = updated
        save()
        if (existing == null) AuditLog.event("publisher.pending", "sha256" to fp, "subject" to updated.subject, "artifact" to (artifact ?: "-"))
        updated
    }

    fun remove(sha256: String) = synchronized(lock) {
        val fp = CertFingerprint.normalize(sha256)
        if (entries.remove(fp) != null) {
            save()
            AuditLog.event("publisher.remove", "sha256" to fp)
        }
    }

    private fun decide(leaf: X509Certificate, status: CertTrustStatus, artifact: String?, connectionId: String?): TrustedSigner =
        synchronized(lock) {
            val fp = CertFingerprint.sha256Hex(leaf)
            val updated = (entries[fp] ?: fromCert(leaf, status)).copy(status = status, decidedAt = now()).withSeen(artifact, connectionId)
            entries[fp] = updated
            save()
            updated
        }

    private fun decideListed(sha256: String, status: CertTrustStatus): TrustedSigner? = synchronized(lock) {
        val fp = CertFingerprint.normalize(sha256)
        val existing = entries[fp] ?: return@synchronized null
        val updated = existing.copy(status = status, decidedAt = now())
        entries[fp] = updated
        save()
        AuditLog.event(if (status == CertTrustStatus.TRUSTED) "publisher.trust" else "publisher.reject", "sha256" to fp, "subject" to updated.subject)
        updated
    }

    private fun fromCert(leaf: X509Certificate, status: CertTrustStatus) = TrustedSigner(
        sha256 = CertFingerprint.sha256Hex(leaf),
        status = status,
        subject = leaf.subjectX500Principal.name,
        issuer = leaf.issuerX500Principal.name,
        notBefore = leaf.notBefore.toInstant().toString(),
        notAfter = leaf.notAfter.toInstant().toString(),
        addedAt = now(),
        decidedAt = if (status == CertTrustStatus.PENDING) null else now(),
        der = runCatching { Base64.getEncoder().encodeToString(leaf.encoded) }.getOrNull(),
    )

    private fun TrustedSigner.withSeen(artifact: String?, connectionId: String?) = copy(
        artifacts = if (artifact == null || artifact in artifacts) artifacts else artifacts + artifact,
        connectionIds = if (connectionId == null || connectionId in connectionIds) connectionIds else connectionIds + connectionId,
    )

    private fun now() = Instant.now().toString()

    private fun load() = synchronized(lock) {
        entries.clear()
        if (!Files.isRegularFile(file)) return@synchronized
        val parsed = runCatching { json.decodeFromString<TrustedSignersFile>(Files.readString(file)) }.getOrNull()
        if (parsed == null) {
            runCatching { Files.move(file, file.resolveSibling(file.fileName.toString() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING) }
            AuditLog.event("publisher.store.corrupt", "file" to file.fileName.toString(), "result" to "PRESERVED")
            return@synchronized
        }
        parsed.signers.forEach { entries[CertFingerprint.normalize(it.sha256)] = it }
    }

    private fun save() {
        val text = json.encodeToString(TrustedSignersFile(signers = entries.values.toList()))
        Files.createDirectories(file.parent)
        val tmp = Files.createTempFile(file.parent, file.fileName.toString() + ".", ".tmp")
        try {
            Files.writeString(tmp, text)
            runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING) }
                .getOrThrow()
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    companion object {
        const val FILE_NAME = "trusted-signers.json"

        /** The per-user store the product uses; tests construct their own on a temp path. */
        val shared: TrustedSignerStore by lazy { TrustedSignerStore() }
    }
}
