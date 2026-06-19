/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.audit

import com.gestalt.launcher.AppPaths
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

/**
 * The dedicated security **audit** trail.
 *
 * Everything security-relevant the launcher does is recorded here as a structured, single-line,
 * key=value record on the log4j2 logger named `AUDIT`, which `log4j2.xml` routes to
 * `logs/audit.log` under the per-user dir (RollingFile, bounded retention, no other appenders).
 *
 * Hard rule: **never log a secret.** Credential access is recorded as an event only — which
 * connection's credential was read/written — never the value. The structured fields are, by
 * convention: `event`, `connection`, `signer`, `digest`, `signature`, `result`, `reason`.
 */
object AuditLog {
    init {
        // log4j2.xml resolves the audit file location from ${sys:gestalt.log.dir}. It must be set
        // before the first getLogger() (below) triggers log4j initialization.
        if (System.getProperty("gestalt.log.dir") == null) {
            System.setProperty("gestalt.log.dir", AppPaths.logDir.toString())
        }
    }

    private val log: Logger = LogManager.getLogger("AUDIT")

    /** Low-level structured emit. Values are rendered safely; nulls become `-`. */
    fun event(event: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder("event=").append(sanitize(event))
        for ((k, v) in fields) {
            sb.append(' ').append(k).append('=').append(render(v))
        }
        log.info(sb.toString())
    }

    // --- connection lifecycle ---------------------------------------------------------------------

    fun connectionAdded(id: String, name: String, server: String) =
        event("connection.add", "connection" to id, "name" to name, "server" to server)

    fun connectionEdited(id: String, name: String, server: String) =
        event("connection.edit", "connection" to id, "name" to name, "server" to server)

    fun connectionDeleted(id: String) =
        event("connection.delete", "connection" to id)

    // --- server-cert trust decisions --------------------------------------------------------------

    fun serverCertPinned(host: String, sha256: String) =
        event("servercert.pin", "host" to host, "sha256" to sha256, "result" to "PINNED")

    fun serverCertTrusted(host: String, subject: String) =
        event("servercert.trust", "host" to host, "subject" to subject, "result" to "TRUSTED")

    fun serverCertRevoked(host: String) =
        event("servercert.revoke", "host" to host, "result" to "REVOKED")

    /** A per-connection PKCS12 truststore (the pinned server leaf) was written for the child JVM. */
    fun serverTrustStoreWritten(connection: String, path: String) =
        event("servercert.truststore", "connection" to connection, "path" to path, "result" to "WRITTEN")

    // --- codebase download + per-jar verification -------------------------------------------------

    fun codebaseDownloaded(connection: String, version: String?, jarCount: Int) =
        event("codebase.download", "connection" to connection, "version" to version, "jars" to jarCount)

    /** Per-jar gate result: the digest check, the signature check, the signer, and the verdict. */
    fun jarVerified(
        connection: String,
        artifact: String,
        signerDN: String?,
        digestPass: Boolean,
        signaturePass: Boolean,
        admitted: Boolean,
        reason: String? = null,
    ) = event(
        "jar.verify",
        "connection" to connection,
        "artifact" to artifact,
        "signer" to signerDN,
        "digest" to pass(digestPass),
        "signature" to pass(signaturePass),
        "result" to if (admitted) "ADMITTED" else "REJECTED",
        "reason" to reason,
    )

    fun signatureEnforcement(mode: String, signerDN: String?, decision: String) =
        event("signature.enforce", "mode" to mode, "signer" to signerDN, "result" to decision)

    fun childLaunched(connection: String, mainClass: String, classpathSize: Int) =
        event("child.launch", "connection" to connection, "mainClass" to mainClass, "classpath" to classpathSize)

    /** Credential access — event only. The value is NEVER recorded. */
    fun credentialAccessed(connection: String, action: String, store: String) =
        event("credential.access", "connection" to connection, "action" to action, "store" to store)

    // --- custom connection icons (user uploads) ---------------------------------------------------

    /** A user-uploaded icon was sanitized and stored. Metadata only — never the image bytes. */
    fun iconAdded(id: String, ext: String, sizeBytes: Long) =
        event("icon.add", "id" to id, "ext" to ext, "size" to sizeBytes, "result" to "STORED")

    /** An upload was refused by the sanitizer (over-size, unsafe SVG, bad raster, unsupported type). */
    fun iconRejected(source: String, reason: String) =
        event("icon.reject", "source" to source, "reason" to reason, "result" to "REJECTED")

    fun iconRenamed(id: String) = event("icon.rename", "id" to id)

    fun iconDeleted(id: String) = event("icon.delete", "id" to id)

    // --- self-update (Phase 4b) -------------------------------------------------------------------

    /** An update check + its verdict (AVAILABLE / UP_TO_DATE / NO_ARTIFACT / SIGNATURE_INVALID / FAILED). */
    fun updateChecked(channel: String, version: String?, result: String) =
        event("update.check", "channel" to channel, "version" to version, "result" to result)

    /** An update artifact/manifest was rejected (fail-closed): bad signature, sha256 mismatch, downgrade. */
    fun updateRejected(target: String, reason: String) =
        event("update.reject", "target" to target, "reason" to reason, "result" to "REJECTED")

    /** An update artifact was downloaded and its signed SHA-256 verified before use. */
    fun updateDownloaded(url: String, sha256: String) =
        event("update.download", "url" to url, "sha256" to sha256, "result" to "VERIFIED")

    /** A verified update is being applied (installer launched, or portable swap staged). */
    fun updateApplied(version: String, mode: String) =
        event("update.apply", "version" to version, "mode" to mode, "result" to "APPLIED")

    // --- rendering --------------------------------------------------------------------------------

    private fun pass(b: Boolean) = if (b) "PASS" else "FAIL"

    private fun render(v: Any?): String {
        if (v == null) return "-"
        // Strip CR/LF (line-forging) then escape backslash and double-quote so an attacker-controlled
        // value (e.g. a downloaded jar's signer DN) cannot break the key="value" framing or forge
        // additional fields. Quote when the value contains whitespace or an escaped character.
        val s = sanitize(v.toString())
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
        return if (esc.any { it.isWhitespace() } || esc != s) "\"$esc\"" else esc
    }

    /** Strip CR/LF so a crafted value can't forge extra audit lines (log injection). */
    private fun sanitize(s: String): String =
        s.replace('\n', '⏎').replace('\r', '⏎')
}
