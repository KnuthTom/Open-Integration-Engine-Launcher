/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package com.gestalt.launcher.update

import java.io.File
import java.util.Base64

/**
 * Release tool: produce a SIGNED update manifest (`latest.json`) for the current release. It computes
 * each artifact's SHA-256, builds the manifest, and signs it with the Gestalt update private key —
 * reusing [UpdateCrypto], so the signature it writes is exactly what the app verifies.
 *
 * Usage (the private key is read from a FILE — never passed on the command line or committed):
 * ```
 *   UpdateManifestTool --key <pkcs8-base64-file> --version 1.1.0 \
 *     --base-url https://updates.example.com/gestalt/stable \
 *     --artifact windows-x64=build/compose/binaries/main/msi/GestaltCloudConnect-1.1.0.msi \
 *     [--artifact macos-arm64=<file>] [--notes "..."] \
 *     --out build/update/latest.json
 * ```
 */
object UpdateManifestTool {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = parseOptions(args)
        val keyFile = opts["key"] ?: error("--key <pkcs8-base64-file> is required")
        val version = opts["version"] ?: error("--version is required")
        val baseUrl = (opts["base-url"] ?: "").trimEnd('/')
        val notes = opts["notes"] ?: ""
        val out = File(opts["out"] ?: "latest.json")
        val privateKey = Base64.getDecoder().decode(File(keyFile).readText().trim())

        val perOS = LinkedHashMap<String, ArtifactRef>()
        for ((osArch, path) in parseArtifacts(args)) {
            val file = File(path)
            require(file.isFile) { "artifact not found: $path" }
            val sha = UpdateCrypto.sha256(file.readBytes())
            val url = if (baseUrl.isEmpty()) file.name else "$baseUrl/${file.name}"
            perOS[osArch] = ArtifactRef(url, sha)
            println("  $osArch  ${file.name}  sha256=$sha")
        }
        require(perOS.isNotEmpty()) { "at least one --artifact os-arch=<file> is required" }

        val unsigned = UpdateManifest(version = version, notes = notes, perOS = perOS)
        val signed = unsigned.copy(signature = UpdateCrypto.sign(privateKey, unsigned))
        out.parentFile?.mkdirs()
        out.writeText(UpdateManifest.render(signed))
        println("Wrote signed manifest: ${out.absolutePath} (version=$version, ${perOS.size} artifact(s))")
    }

    private fun parseOptions(args: Array<String>): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && a != "--artifact") {
                map[a.removePrefix("--")] = args.getOrNull(i + 1) ?: ""
                i += 2
            } else i++
        }
        return map
    }

    private fun parseArtifacts(args: Array<String>): List<Pair<String, String>> {
        val list = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "--artifact") {
                val spec = args.getOrNull(i + 1) ?: ""
                val osArch = spec.substringBefore('=')
                val path = spec.substringAfter('=')
                if (osArch.isNotBlank() && path.isNotBlank()) list += osArch to path
                i += 2
            } else i++
        }
        return list
    }
}
