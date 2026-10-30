/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package gestalt

import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * Acquires the two **client** JREs the Administrator runs on — Azul Zulu **with JavaFX** (the `-fx-`
 * bundles; GPLv2+CPE, redistributable) — using only the JDK that runs Gradle.
 *
 * Why Zulu FX and not a plain OpenJDK build: the Mirth / OIE Administrator is a Swing client whose tag
 * editor is a JavaFX `JFXPanel`. The `openjfx.jar` the server publishes carries the JavaFX *classes* but
 * no native libraries, so the JavaFX runtime has to come from the JRE itself: on a JRE without it the
 * client logs in and then dies with "Graphics Device initialization failed … no suitable pipeline" (or,
 * on Java 8, an `UnsupportedClassVersionError` as it falls through to that jar). The original launcher
 * shipped a JavaFX-bearing JRE for the same reason.
 *
 * The flow per runtime: ask the Azul metadata API for the latest GA FX JRE for the target OS/arch, read
 * its official **SHA-256**, download, verify, stage. Binaries are never committed — fetched at package
 * time.
 */
object ClientRuntimes {

    /** A resolved download: the binary [link], its official [sha256], and the archive [name]. */
    data class Asset(val link: String, val sha256: String, val name: String)

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private const val USER_AGENT = "open-integration-engine-launcher-build"
    private const val API = "https://api.azul.com/metadata/v1/zulu/packages/"

    /**
     * Resolve the latest GA Zulu FX JRE for [featureVersion] (8 / 17), [os] in the Compose plugin's
     * naming (`windows` | `macos` | `linux`) and [arch] (`x64` | `aarch64`). Returns the binary link,
     * official checksum and archive name. Windows is a zip, the others a tar.gz.
     */
    fun resolveAsset(featureVersion: Int, os: String, arch: String): Asset {
        val azulOs = when (os) { "mac", "macos", "osx" -> "macos"; "win", "windows" -> "windows"; else -> os }
        val archiveType = if (azulOs == "windows") "zip" else "tar.gz"
        val query = listOf(
            "java_version" to featureVersion.toString(),
            "os" to azulOs,
            "arch" to arch,
            "archive_type" to archiveType,
            "java_package_type" to "jre",
            "javafx_bundled" to "true",
            "latest" to "true",
            "release_status" to "ga",
            "availability_types" to "CA",
            "page" to "1",
            "page_size" to "1",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }

        @Suppress("UNCHECKED_CAST")
        val list = getJson("$API?$query") as? List<Map<String, Any?>>
            ?: error("unexpected Azul response for FX JRE $featureVersion $azulOs/$arch")
        require(list.isNotEmpty()) { "no Zulu FX JRE $featureVersion found for $azulOs/$arch" }
        val uuid = list[0]["package_uuid"] as? String ?: error("Azul package without a uuid")

        // The listing has no checksum; the package detail does.
        @Suppress("UNCHECKED_CAST")
        val detail = getJson("$API$uuid") as? Map<String, Any?>
            ?: error("unexpected Azul package detail for $uuid")
        val link = detail["download_url"] as? String ?: error("Azul package $uuid without a download_url")
        val sha = (detail["sha256_hash"] as? String)?.lowercase() ?: error("Azul package $uuid without a sha256_hash")
        val name = detail["name"] as? String ?: link.substringAfterLast('/')
        require(detail["javafx_bundled"] == true) { "Azul package $name is not a JavaFX bundle" }
        require(sha.matches(Regex("^[0-9a-f]{64}$"))) { "Azul package $name has a malformed sha256: $sha" }
        return Asset(link, sha, name)
    }

    private fun getJson(url: String): Any? {
        val req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(60))
            .GET().build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        require(resp.statusCode() == 200) { "Azul API HTTP ${resp.statusCode()} for $url" }
        return JsonSlurper().parseText(resp.body())
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
     * Find the Java home inside an extracted archive — the directory that directly contains `bin/java`
     * (or `bin/java.exe`). This normalizes away both the leading version directory (`zulu8…-win_x64/`)
     * and the macOS `Contents/Home/` nesting, so every staged runtime has the uniform shape
     * `<jreN>/bin/java`.
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

    /**
     * True when the staged runtime at [home] carries JavaFX: `lib/ext/jfxrt.jar` on Java 8; on 11+ the
     * `javafx.*` modules' marker (`lib/javafx.properties`) or their windowing native (`glass`). A staged
     * runtime without it is one of the earlier plain builds and must be replaced, or the Administrator
     * fails right after login.
     */
    fun hasJavaFx(home: File): Boolean =
        listOf("lib/ext/jfxrt.jar", "lib/javafx.properties", "bin/glass.dll", "lib/libglass.so", "lib/libglass.dylib")
            .any { File(home, it).isFile }
}
