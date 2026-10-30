/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.gate

import com.gestalt.launcher.AppPaths
import com.gestalt.launcher.digest.DigestVerifier
import com.gestalt.launcher.tls.CertFingerprint
import com.gestalt.trust.TrustMode
import com.gestalt.trust.TrustStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Remembers which jar **bytes** the gate has admitted, and under which trust policy, so a relaunch does
 * not re-run the CMS / PKIX / timestamp verification over ~170 jars it verified last time.
 *
 * What makes this sound: an entry is keyed by the jar's SHA-256 and the gate still hashes every jar on
 * every launch and compares it to the digest the server published *this* launch (the same
 * constant-time check as before). A cached admission is used only when the file's bytes are exactly
 * the bytes that passed the full signature check, under the same **policy fingerprint** — the gate
 * mode, the signer pins, the code-signing anchors, and every publisher decision in the trusted-signer
 * store. Change any of those (reject a publisher, switch posture, add an anchor) and every entry
 * misses; the jars are verified in full again and re-recorded.
 *
 * On disk: `<cache>/admissions.json`, bounded to [MAX_ENTRIES] (oldest admissions dropped), written
 * atomically. Losing or deleting it costs one slow launch, nothing else.
 */
class AdmissionCache(private val file: Path = AppPaths.cacheDir.resolve(FILE_NAME), private val maxEntries: Int = MAX_ENTRIES) {

    /** One admitted jar: its bytes, who signed it, and the policy it was admitted under. */
    @Serializable
    data class Entry(
        val sha256: String,
        val policy: String,
        val signerDN: String,
        val usedTimestamp: Boolean,
        val admittedAt: String,
        val artifactName: String,
    )

    @Serializable
    private data class CacheFile(val version: Int = 1, val entries: List<Entry> = emptyList())

    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var dirty = false

    init {
        load()
    }

    /** The admission recorded for [sha256] under [policy], or null (unknown bytes, or another policy). */
    fun lookup(sha256: String, policy: String): Entry? = synchronized(lock) {
        entries[sha256.lowercase()]?.takeIf { it.policy == policy }
    }

    /** Record an admission. Persisted by [flush]. */
    fun record(entry: Entry) = synchronized(lock) {
        entries.remove(entry.sha256.lowercase())
        entries[entry.sha256.lowercase()] = entry
        while (entries.size > maxEntries) entries.remove(entries.keys.first())
        dirty = true
    }

    /** Write the cache if anything changed. Never throws: the cache is a speed-up, not a dependency. */
    fun flush() {
        val snapshot = synchronized(lock) {
            if (!dirty) return
            dirty = false
            CacheFile(entries = entries.values.toList())
        }
        runCatching {
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(snapshot))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun size(): Int = synchronized(lock) { entries.size }

    private fun load() {
        val parsed = runCatching {
            if (!Files.isRegularFile(file)) return
            json.decodeFromString<CacheFile>(Files.readString(file))
        }.getOrNull() ?: return
        synchronized(lock) {
            parsed.entries.filter { it.sha256.matches(HEX64) }.forEach { entries[it.sha256.lowercase()] = it }
        }
    }

    companion object {
        const val FILE_NAME = "admissions.json"
        const val MAX_ENTRIES = 4000
        private val HEX64 = Regex("^[0-9a-fA-F]{64}$")

        /**
         * A fingerprint of everything the gate's decision depends on besides the jar itself: the mode,
         * the pins, the anchors, and each publisher decision. Any change invalidates every cached
         * admission.
         */
        fun policyFingerprint(
            mode: TrustMode,
            signerPins: List<SignerPin>,
            trustStore: TrustStore,
            trustedSigners: TrustedSignerStore?,
        ): String {
            val sb = StringBuilder()
            sb.append("mode=").append(mode.name).append('\n')
            signerPins.map { pin -> pin.label + ":" + pin.requiredRdns.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" } }
                .sorted().forEach { sb.append("pin=").append(it).append('\n') }
            trustStore.anchors.map { CertFingerprint.sha256Hex(it) }.sorted().forEach { sb.append("anchor=").append(it).append('\n') }
            trustedSigners?.list()?.map { "${it.sha256.lowercase()}:${it.status.name}" }?.sorted()?.forEach { sb.append("publisher=").append(it).append('\n') }
            return DigestVerifier.sha256Hex(sb.toString().toByteArray(Charsets.UTF_8))
        }

        fun now(): String = Instant.now().toString()
    }
}
