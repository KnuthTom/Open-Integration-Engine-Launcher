/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package gestalt

import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Acquires Eclipse Temurin client JREs (GPLv2+CE — redistributable) for bundling, using only the
 * JDK that runs Gradle. The flow per runtime is: ask the Adoptium API for the latest GA JRE binary
 * for the target OS/arch, download it, and **verify the publisher's SHA-256** before it is staged.
 * JRE binaries are never committed to the repo — they are fetched at package time.
 */
object TemurinRuntimes {

    /** A resolved download: the binary [link], its official [sha256], and the archive [name]. */
    data class Asset(val link: String, val sha256: String, val name: String)

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private const val USER_AGENT = "gestalt-cloud-connect-build"

    /**
     * Resolve the latest GA Temurin JRE for [featureVersion] (8 / 17), [os] (windows|mac|linux) and
     * [arch] (x64|aarch64) via the Adoptium assets API. Returns the binary link + official checksum.
     */
    fun resolveAsset(featureVersion: Int, os: String, arch: String): Asset {
        val api = "https://api.adoptium.net/v3/assets/latest/$featureVersion/hotspot" +
            "?architecture=$arch&image_type=jre&os=$os&vendor=eclipse"
        val req = HttpRequest.newBuilder(URI.create(api))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60))
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "Adoptium API HTTP ${resp.statusCode()} for $api" }

        @Suppress("UNCHECKED_CAST")
        val list = JsonSlurper().parseText(resp.body()) as? List<Map<String, Any?>>
            ?: error("unexpected Adoptium response for JRE $featureVersion $os/$arch")
        require(list.isNotEmpty()) { "no Temurin JRE $featureVersion found for $os/$arch" }

        @Suppress("UNCHECKED_CAST")
        val pkg = (list[0]["binary"] as Map<String, Any?>)["package"] as Map<String, Any?>
        val link = pkg["link"] as String
        val sha = (pkg["checksum"] as String).lowercase()
        val name = pkg["name"] as String
        return Asset(link, sha, name)
    }

    /** Stream [asset] to [dest] (creating parent dirs). Overwrites any existing file. */
    fun download(asset: Asset, dest: File) {
        dest.parentFile?.mkdirs()
        val req = HttpRequest.newBuilder(URI.create(asset.link))
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofMinutes(10))
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofFile(dest.toPath()))
        require(resp.statusCode() == 200) { "download HTTP ${resp.statusCode()} for ${asset.link}" }
    }

    /** Hex lowercase SHA-256 of [file]. */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Verify [file] against [expectedSha256], throwing with a clear message on mismatch. */
    fun verify(file: File, expectedSha256: String) {
        val actual = sha256(file)
        require(actual.equals(expectedSha256, ignoreCase = true)) {
            "checksum mismatch for ${file.name}: expected $expectedSha256 but got $actual"
        }
    }

    /**
     * Find the Java home inside an extracted Temurin archive — the directory that directly contains
     * `bin/java` (or `bin/java.exe`). This normalizes away both the leading version directory
     * (e.g. `jdk8u…-jre/`) and the macOS `Contents/Home/` nesting, so every staged runtime has the
     * uniform shape `<jreN>/bin/java`.
     */
    fun findJavaHome(extracted: File): File? {
        extracted.walkTopDown().forEach { dir ->
            if (dir.isDirectory) {
                val bin = File(dir, "bin")
                if (File(bin, "java").isFile || File(bin, "java.exe").isFile) return dir
            }
        }
        return null
    }
}
