/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import com.gestalt.launcher.audit.AuditLog
import com.gestalt.launcher.net.ArtifactFetcher
import com.gestalt.launcher.net.HttpArtifactFetcher
import com.gestalt.launcher.tls.HttpClientFactory
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** The outcome of an update check. Only [Available] ever leads to applying anything. */
sealed interface UpdateOutcome {
    /** A strictly-newer, signature-verified update with an artifact for this platform. */
    data class Available(val manifest: UpdateManifest, val artifact: ArtifactRef, val portable: Boolean) : UpdateOutcome

    /** The running build is current (or newer). */
    data object UpToDate : UpdateOutcome

    /** Benign: no channel configured, or a valid manifest with no build for this platform. */
    data class Unavailable(val reason: String) : UpdateOutcome

    /** Fail-closed: a signature / integrity / network / parse failure. Nothing is applied. */
    data class Failed(val reason: String) : UpdateOutcome
}

/**
 * Fail-closed signed self-update.
 *
 * A manifest is trusted **only** if its Ed25519 signature verifies against a [pinnedKeys] public key;
 * an artifact is applied **only** if its SHA-256 matches the value in that signed manifest; and an
 * update applies **only** if its version is strictly newer (no downgrade, no re-apply). Any failure
 * yields [UpdateOutcome.Failed] (or throws on download) and changes nothing. Every check and decision
 * is written to the audit log.
 *
 * This reuses the launcher's existing hardened HTTP stack ([HttpClientFactory] → modern-TLS,
 * hostname-verified) for both the manifest and artifact fetches.
 *
 * @param currentVersion the running app version (e.g. BuildInfo.version).
 * @param osArch the platform token used as the manifest `perOS` key (e.g. "windows-x64").
 * @param portable whether this is the portable layout (AppPaths.isPortable) — surfaced on [Available].
 */
class UpdateService(
    private val currentVersion: String,
    private val osArch: String,
    private val portable: Boolean,
    private val pinnedKeys: List<ByteArray> = UpdateKeys.pinned,
    private val fetcherFactory: () -> ArtifactFetcher = { HttpArtifactFetcher(HttpClientFactory.standardClient()) },
) {
    /** Fetch + verify the manifest at [channelUrl] and decide. Fail-closed; never throws. */
    fun check(channelUrl: String): UpdateOutcome {
        val channel = channelUrl.trim()
        if (channel.isEmpty()) return UpdateOutcome.Unavailable("No update channel configured")

        val manifestUri = runCatching { manifestUri(channel) }.getOrNull()
            ?: return failed(channel, "Invalid update channel URL")
        if (!manifestUri.scheme.equals("https", ignoreCase = true)) {
            return failed(channel, "Update channel must be https")
        }

        val text = runCatching { withFetcher { it.fetchText(manifestUri) } }
            .getOrElse { return failed(channel, "Could not reach the update channel") }
        val manifest = runCatching { UpdateManifest.parse(text) }
            .getOrElse { return failed(channel, "Update manifest was malformed") }

        if (!UpdateCrypto.verify(manifest, pinnedKeys)) {
            AuditLog.updateChecked(channel, manifest.version, "SIGNATURE_INVALID")
            return UpdateOutcome.Failed("Update manifest signature is not trusted")
        }
        if (!SemVer.isNewer(manifest.version, currentVersion)) {
            AuditLog.updateChecked(channel, manifest.version, "UP_TO_DATE")
            return UpdateOutcome.UpToDate
        }
        // Installed builds want the installer; portable builds want the portable archive. They are
        // distinct manifest entries: "<os>-<arch>" vs "<os>-<arch>-portable".
        val platformKey = if (portable) "$osArch-portable" else osArch
        val artifact = manifest.perOS[platformKey]
        if (artifact == null) {
            AuditLog.updateChecked(channel, manifest.version, "NO_ARTIFACT")
            return UpdateOutcome.Unavailable("Update ${manifest.version} has no build for $platformKey")
        }
        AuditLog.updateChecked(channel, manifest.version, "AVAILABLE")
        return UpdateOutcome.Available(manifest, artifact, portable)
    }

    /**
     * Download [artifact] into [destDir], verifying its signed SHA-256 BEFORE the file is kept (it is
     * written to a temp file and only moved into place after the digest matches). Throws
     * [UpdateIntegrityException] on mismatch — fail-closed, nothing usable is left behind.
     */
    fun download(artifact: ArtifactRef, destDir: Path): Path {
        Files.createDirectories(destDir)
        val uri = URI.create(artifact.url)
        require(uri.scheme.equals("https", ignoreCase = true)) { "refusing non-https artifact URL: $uri" }

        val bytes = withFetcher { it.fetchBytes(uri) }
        val actual = UpdateCrypto.sha256(bytes)
        if (!actual.equals(artifact.sha256, ignoreCase = true)) {
            AuditLog.updateRejected(artifact.url, "sha256 mismatch")
            throw UpdateIntegrityException("artifact SHA-256 mismatch: expected ${artifact.sha256}, got $actual")
        }
        val tmp = Files.createTempFile(destDir, "dl-", ".part")
        Files.write(tmp, bytes)
        val dest = destDir.resolve(uri.path.substringAfterLast('/').ifBlank { "gestalt-update" })
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        AuditLog.updateDownloaded(artifact.url, actual)
        return dest
    }

    /** The OS command that launches a downloaded installer to upgrade in place (installed mode). */
    fun installerCommand(installer: Path): List<String> {
        val p = installer.toAbsolutePath().toString()
        // MSI must go through msiexec; a self-extracting .exe runs directly.
        return if (p.endsWith(".msi", ignoreCase = true)) listOf("msiexec", "/i", p) else listOf(p)
    }

    /** `<channel>/latest.json` (or the channel itself if it already points at a .json). */
    private fun manifestUri(channelUrl: String): URI {
        val base = channelUrl.trimEnd('/')
        return URI.create(if (base.endsWith(".json", ignoreCase = true)) base else "$base/latest.json")
    }

    private fun failed(channel: String, reason: String): UpdateOutcome.Failed {
        AuditLog.updateChecked(channel, null, "FAILED")
        return UpdateOutcome.Failed(reason)
    }

    private inline fun <R> withFetcher(block: (ArtifactFetcher) -> R): R {
        val fetcher = fetcherFactory()
        try {
            return block(fetcher)
        } finally {
            (fetcher as? AutoCloseable)?.let { runCatching { it.close() } }
        }
    }
}
