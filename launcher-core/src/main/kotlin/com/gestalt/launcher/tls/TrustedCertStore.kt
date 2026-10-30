/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.tls

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.audit.AuditLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/** What the user decided about a server certificate (or that no decision has been made yet). */
@Serializable
enum class CertTrustStatus { TRUSTED, REJECTED, PENDING }

/**
 * One server certificate the launcher has seen, keyed by its SHA-256 fingerprint. Details are nullable
 * because a legacy per-connection pin migrates as a fingerprint only; they are filled in the next time
 * the certificate is actually observed.
 */
@Serializable
data class TrustedCert(
    /** Lowercase hex SHA-256 of the DER certificate — the key. */
    val sha256: String,
    val status: CertTrustStatus,
    val subject: String? = null,
    val issuer: String? = null,
    /** ISO-8601 instants. */
    val notBefore: String? = null,
    val notAfter: String? = null,
    /** `host:port` endpoints this certificate was presented by. */
    val hosts: List<String> = emptyList(),
    /** Connections it was encountered through. */
    val connectionIds: List<String> = emptyList(),
    val addedAt: String? = null,
    val decidedAt: String? = null,
    /** True when trust was granted by a connection's auto-approve setting rather than a person. */
    val autoApproved: Boolean = false,
    /** Base64 DER of the certificate, so details can be shown without re-connecting. */
    val der: String? = null,
) {
    fun isExpired(now: Instant = Instant.now()): Boolean =
        notAfter?.let { runCatching { Instant.parse(it).isBefore(now) }.getOrDefault(false) } ?: false

    /** Days until expiry (negative once expired); null when the expiry is unknown. */
    fun daysUntilExpiry(now: Instant = Instant.now()): Long? =
        notAfter?.let { runCatching { java.time.Duration.between(now, Instant.parse(it)).toDays() }.getOrNull() }
}

@Serializable
private data class TrustedCertsFile(val version: Int = 1, val certificates: List<TrustedCert> = emptyList())

/**
 * The launcher's store of server certificates the user has reviewed — the trust model from the redesign:
 * trust on first use, reviewed in one place, with a per-connection auto-approve option.
 *
 *  - An unknown https certificate that the system CAs don't validate is recorded as [CertTrustStatus.PENDING]
 *    and the launch stops for review (or is trusted at once when the connection auto-approves).
 *  - [CertTrustStatus.TRUSTED] certificates are accepted by exact fingerprint — regardless of chain and
 *    hostname, which is what self-signed engines and SSH/SSM tunnels need.
 *  - [CertTrustStatus.REJECTED] certificates fail closed until the user changes their mind.
 *  - Expiry never blocks a trusted certificate (self-signed engine certs are routinely expired); it is
 *    surfaced as a warning by the UI via [TrustedCert.isExpired].
 *
 * One JSON file under the data dir, written atomically; every operation is locked and audited.
 */
class TrustedCertStore(private val file: Path = AppPaths.dataDir.resolve(FILE_NAME)) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val entries = LinkedHashMap<String, TrustedCert>()

    init {
        load()
    }

    fun list(): List<TrustedCert> = synchronized(lock) { entries.values.toList() }

    fun get(sha256: String): TrustedCert? = synchronized(lock) { entries[CertFingerprint.normalize(sha256)] }

    fun statusOf(cert: X509Certificate): CertTrustStatus? = get(CertFingerprint.sha256Hex(cert))?.status

    fun trustedFingerprints(): Set<String> = fingerprints(CertTrustStatus.TRUSTED)
    fun rejectedFingerprints(): Set<String> = fingerprints(CertTrustStatus.REJECTED)
    fun pending(): List<TrustedCert> = list().filter { it.status == CertTrustStatus.PENDING }

    private fun fingerprints(status: CertTrustStatus): Set<String> = synchronized(lock) {
        entries.values.filter { it.status == status }.mapTo(LinkedHashSet()) { it.sha256 }
    }

    /** Trust [cert] (a reviewed decision, or an auto-approval when [autoApproved]). Idempotent. */
    fun trust(cert: X509Certificate, host: String? = null, connectionId: String? = null, autoApproved: Boolean = false): TrustedCert =
        decide(cert, CertTrustStatus.TRUSTED, host, connectionId, autoApproved).also {
            AuditLog.event("servercert.trust", "host" to (host ?: "-"), "sha256" to it.sha256, "auto" to autoApproved, "subject" to (it.subject ?: "-"))
        }

    /** Reject [cert]: connections presenting it fail closed until the entry is changed or removed. */
    fun reject(cert: X509Certificate, host: String? = null, connectionId: String? = null): TrustedCert =
        decide(cert, CertTrustStatus.REJECTED, host, connectionId, autoApproved = false).also {
            AuditLog.event("servercert.reject", "host" to (host ?: "-"), "sha256" to it.sha256, "subject" to (it.subject ?: "-"))
        }

    /**
     * Record an unknown certificate as awaiting review (first sight). Never downgrades an existing
     * TRUSTED/REJECTED decision; a PENDING entry just gains the host/connection.
     */
    fun markPending(cert: X509Certificate, host: String? = null, connectionId: String? = null): TrustedCert = synchronized(lock) {
        val fp = CertFingerprint.sha256Hex(cert)
        val existing = entries[fp]
        val updated = if (existing == null) {
            fromCert(cert, CertTrustStatus.PENDING, host, connectionId, autoApproved = false)
        } else {
            existing.withDetails(cert).withSeenAt(host, connectionId)
        }
        entries[fp] = updated
        save()
        if (existing == null) AuditLog.event("servercert.pending", "host" to (host ?: "-"), "sha256" to fp, "subject" to (updated.subject ?: "-"))
        updated
    }

    /** Decide about an entry already on file by its fingerprint (the Certificates dialog acts on listed rows). */
    fun trustListed(sha256: String): TrustedCert? = decideListed(sha256, CertTrustStatus.TRUSTED)
    fun rejectListed(sha256: String): TrustedCert? = decideListed(sha256, CertTrustStatus.REJECTED)

    private fun decideListed(sha256: String, status: CertTrustStatus): TrustedCert? = synchronized(lock) {
        val fp = CertFingerprint.normalize(sha256)
        val existing = entries[fp] ?: return@synchronized null
        val updated = existing.copy(status = status, decidedAt = now(), autoApproved = false)
        entries[fp] = updated
        save()
        AuditLog.event(
            if (status == CertTrustStatus.TRUSTED) "servercert.trust" else "servercert.reject",
            "host" to (updated.hosts.firstOrNull() ?: "-"), "sha256" to fp, "auto" to false, "subject" to (updated.subject ?: "-"),
        )
        updated
    }

    /** Trust by fingerprint alone (legacy pin migration); details arrive on the next observation. */
    fun trustFingerprint(sha256: String, host: String? = null, connectionId: String? = null): TrustedCert = synchronized(lock) {
        val fp = CertFingerprint.normalize(sha256)
        val existing = entries[fp]
        val updated = (existing ?: TrustedCert(fp, CertTrustStatus.TRUSTED, addedAt = now()))
            .copy(status = CertTrustStatus.TRUSTED, decidedAt = existing?.decidedAt ?: now())
            .withSeenAt(host, connectionId)
        entries[fp] = updated
        save()
        AuditLog.event("servercert.trust", "host" to (host ?: "-"), "sha256" to fp, "auto" to false, "subject" to (updated.subject ?: "-"))
        updated
    }

    /** Fill in details for a known fingerprint (e.g. a migrated pin) and note where it was seen. */
    fun observed(cert: X509Certificate, host: String? = null, connectionId: String? = null) = synchronized(lock) {
        val fp = CertFingerprint.sha256Hex(cert)
        val existing = entries[fp] ?: return@synchronized
        val updated = existing.withDetails(cert).withSeenAt(host, connectionId)
        if (updated != existing) { entries[fp] = updated; save() }
    }

    /** Forget a certificate entirely (it becomes unknown again and will prompt on next sight). */
    fun remove(sha256: String) = synchronized(lock) {
        val fp = CertFingerprint.normalize(sha256)
        if (entries.remove(fp) != null) {
            save()
            AuditLog.event("servercert.remove", "sha256" to fp)
        }
    }

    /** Drop [connectionId] from every entry; entries no other connection uses and no person decided are removed. */
    fun forgetConnection(connectionId: String) = synchronized(lock) {
        var changed = false
        entries.values.toList().forEach { e ->
            if (connectionId in e.connectionIds) {
                val rest = e.connectionIds - connectionId
                if (rest.isEmpty() && e.status == CertTrustStatus.PENDING) entries.remove(e.sha256)
                else entries[e.sha256] = e.copy(connectionIds = rest)
                changed = true
            }
        }
        if (changed) save()
    }

    private fun decide(cert: X509Certificate, status: CertTrustStatus, host: String?, connectionId: String?, autoApproved: Boolean): TrustedCert =
        synchronized(lock) {
            val fp = CertFingerprint.sha256Hex(cert)
            val existing = entries[fp]
            val updated = (existing?.withDetails(cert) ?: fromCert(cert, status, host, connectionId, autoApproved))
                .copy(status = status, decidedAt = now(), autoApproved = autoApproved && status == CertTrustStatus.TRUSTED)
                .withSeenAt(host, connectionId)
            entries[fp] = updated
            save()
            updated
        }

    private fun fromCert(cert: X509Certificate, status: CertTrustStatus, host: String?, connectionId: String?, autoApproved: Boolean): TrustedCert =
        TrustedCert(
            sha256 = CertFingerprint.sha256Hex(cert),
            status = status,
            addedAt = now(),
            decidedAt = if (status == CertTrustStatus.PENDING) null else now(),
            autoApproved = autoApproved,
        ).withDetails(cert).withSeenAt(host, connectionId)

    private fun TrustedCert.withDetails(cert: X509Certificate): TrustedCert = copy(
        subject = cert.subjectX500Principal.name,
        issuer = cert.issuerX500Principal.name,
        notBefore = cert.notBefore.toInstant().toString(),
        notAfter = cert.notAfter.toInstant().toString(),
        der = runCatching { Base64.getEncoder().encodeToString(cert.encoded) }.getOrNull() ?: der,
    )

    private fun TrustedCert.withSeenAt(host: String?, connectionId: String?): TrustedCert = copy(
        hosts = if (host == null || host in hosts) hosts else hosts + host,
        connectionIds = if (connectionId == null || connectionId in connectionIds) connectionIds else connectionIds + connectionId,
    )

    private fun now(): String = Instant.now().toString()

    private fun load() = synchronized(lock) {
        entries.clear()
        if (!Files.isRegularFile(file)) return@synchronized
        val parsed = runCatching { json.decodeFromString<TrustedCertsFile>(Files.readString(file)) }.getOrNull()
        if (parsed == null) {
            runCatching { Files.move(file, file.resolveSibling(file.fileName.toString() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING) }
            AuditLog.event("servercert.store.corrupt", "file" to file.fileName.toString(), "result" to "PRESERVED")
            return@synchronized
        }
        parsed.certificates.forEach { entries[CertFingerprint.normalize(it.sha256)] = it }
    }

    private fun save() {
        val text = json.encodeToString(TrustedCertsFile(certificates = entries.values.toList()))
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
        const val FILE_NAME = "trusted-certs.json"

        /** The per-user store the product uses; tests construct their own on a temp path. */
        val shared: TrustedCertStore by lazy { TrustedCertStore() }
    }
}
