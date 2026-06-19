/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Ed25519 signing/verification for the update manifest, plus the canonical encoding that the signer
 * (the build/release tool) and the verifier (the running app) must agree on byte-for-byte.
 *
 * The signature covers the security-relevant fields — a domain tag, the version, the notes, and every
 * `(os-arch, url, sha256)` triple in sorted key order — using a **length-prefixed** encoding so no
 * field value can be smuggled across a delimiter. It is computed over the parsed object, NOT the JSON
 * text, so manifest key order / whitespace / unknown extra keys do not affect verification. The
 * `signature` field itself is excluded from what is signed.
 */
object UpdateCrypto {
    private const val DOMAIN = "gestalt.update.manifest.v1"
    private const val ALG = "Ed25519"

    fun signableBytes(manifest: UpdateManifest): ByteArray {
        val out = ByteArrayOutputStream()
        fun field(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            writeInt(out, b.size)
            out.write(b)
        }
        field(DOMAIN)
        field(manifest.version)
        field(manifest.notes)
        val keys = manifest.perOS.keys.sorted()
        writeInt(out, keys.size)
        for (k in keys) {
            val a = manifest.perOS.getValue(k)
            field(k); field(a.url); field(a.sha256)
        }
        return out.toByteArray()
    }

    /** Sign [manifest] with the Ed25519 [privatePkcs8] key; returns the base64 signature. */
    fun sign(privatePkcs8: ByteArray, manifest: UpdateManifest): String {
        val key = KeyFactory.getInstance(ALG).generatePrivate(PKCS8EncodedKeySpec(privatePkcs8))
        val sig = Signature.getInstance(ALG).apply { initSign(key); update(signableBytes(manifest)) }.sign()
        return Base64.getEncoder().encodeToString(sig)
    }

    /**
     * True iff [manifest].signature is a valid Ed25519 signature over [manifest]'s canonical bytes by
     * ANY of [publicKeys] (X.509 SubjectPublicKeyInfo). Fail-closed: a malformed signature, a bad key,
     * or no keys all return false.
     */
    fun verify(manifest: UpdateManifest, publicKeys: List<ByteArray>): Boolean {
        if (publicKeys.isEmpty()) return false
        val sig = runCatching { Base64.getDecoder().decode(manifest.signature) }.getOrNull() ?: return false
        val data = signableBytes(manifest)
        return publicKeys.any { spki ->
            runCatching {
                val key = KeyFactory.getInstance(ALG).generatePublic(X509EncodedKeySpec(spki))
                Signature.getInstance(ALG).apply { initVerify(key); update(data) }.verify(sig)
            }.getOrDefault(false)
        }
    }

    /** Generate a fresh Ed25519 keypair: (PKCS#8 private, X.509 public). */
    fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val kp = KeyPairGenerator.getInstance(ALG).generateKeyPair()
        return kp.private.encoded to kp.public.encoded
    }

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }
}
