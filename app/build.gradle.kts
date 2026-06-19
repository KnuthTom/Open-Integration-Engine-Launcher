/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.process.ExecOperations
import java.io.File
import java.time.Instant
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(17)
}

composeCompiler {
    // The :launcher-core model types are effectively immutable but live in a non-Compose module, so the
    // compiler can't infer their stability. Marking them stable lets connection rows skip recomposition.
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
}

dependencies {
    implementation(compose.desktop.currentOs)
    // The design system (theming engine, tokens, themed components, icon + branding shells).
    implementation(project(":design-system"))
    // The fail-closed launch security core (trust gate, paths, audit) — used by the CLI launch path.
    implementation(project(":launcher-core"))
    // The launcher consumes the trust core once the UI / download path lands.
    implementation(project(":core-trust"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Keep any per-user state under build/ during tests so the suite never touches the real profile.
    systemProperty("gestalt.home", layout.buildDirectory.dir("test-gestalt-home").get().asFile.absolutePath)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// =================================================================================================
// Phase 4a — packaging
//
// Produces (on the current host; jpackage is per-host): per-user installers and a portable
// app-image, each bundling TWO client JREs (Temurin 8 + 17) and carrying the GD app icon. The
// launcher itself runs on its own Compose/jpackage runtime (JDK 17, the toolchain above).
// =================================================================================================

val appVersion = (findProperty("gestaltVersion") as String?) ?: "1.0.0"
val appVendor = (findProperty("gestaltVendor") as String?) ?: "Open Integration Engine Launcher"

// Gradle-9 readiness: reach process/file/archive operations through INJECTED services rather than
// Project.exec / Project.copy / Project.zipTree at execution time, which are deprecated for removal in
// Gradle 9.0. A script-level holder created via ObjectFactory.newInstance is the supported way for
// ad-hoc (script-registered) tasks to obtain these services.
interface InjectedBuildOps {
    @get:Inject val exec: ExecOperations
    @get:Inject val fs: FileSystemOperations
    @get:Inject val archives: ArchiveOperations
}
val buildOps = objects.newInstance<InjectedBuildOps>()

/** Client runtimes bundled for launching the Administrator (NOT the launcher's own runtime). */
val bundledJavaVersions = listOf(8, 17)

// Host OS/arch, mapped to the two naming schemes we need: Compose appResources subfolders and the
// Adoptium API tokens.
private val hostOs = System.getProperty("os.name").orEmpty().lowercase()
private val hostArch = System.getProperty("os.arch").orEmpty().lowercase()
val composeOs = when {
    hostOs.contains("win") -> "windows"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "macos"
    else -> "linux"
}
val composeArch = if (hostArch.contains("aarch64") || hostArch.contains("arm64")) "arm64" else "x64"
val osArchToken = "$composeOs-$composeArch"
val temurinOs = when (composeOs) { "macos" -> "mac"; else -> composeOs }
val temurinArch = if (composeArch == "arm64") "aarch64" else "x64"

val appResourcesDir = layout.buildDirectory.dir("appResources")
val iconsDir = layout.buildDirectory.dir("icons")
val iconIco = iconsDir.map { it.file("${gestalt.AppIconGenerator.BASE_NAME}.ico") }
val iconIcns = iconsDir.map { it.file("${gestalt.AppIconGenerator.BASE_NAME}.icns") }
val iconPng = iconsDir.map { it.file("${gestalt.AppIconGenerator.BASE_NAME}.png") }

// --- App icon: render .ico/.icns/.png from the GD mark (no external rasterizer required) ---------
val generateAppIcons by tasks.registering {
    group = "build"
    description = "Render the app icon (.ico/.icns/.png) from the bundled neutral mark."
    outputs.dir(iconsDir)
    outputs.cacheIf { true }
    doLast { gestalt.AppIconGenerator.generate(iconsDir.get().asFile) }
}

// --- Version + build info, embedded as a classpath resource and shown in About -------------------
val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
val generateBuildInfo by tasks.registering {
    group = "build"
    description = "Write build-info.properties (version, build time, git, bundled runtimes)."
    val out = buildInfoDir
    outputs.dir(out)
    inputs.property("version", appVersion)
    inputs.property("vendor", appVendor)
    inputs.property("runtimes", bundledJavaVersions.joinToString(", "))
    val rootDir = project.rootDir
    doLast {
        val git = { args: List<String> ->
            runCatching {
                val p = ProcessBuilder(listOf("git") + args).directory(rootDir).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText().trim()
                if (p.waitFor() == 0) text else ""
            }.getOrDefault("")
        }
        val props = linkedMapOf(
            "name" to "Open Integration Engine Launcher",
            "version" to appVersion,
            "vendor" to appVendor,
            "buildTime" to Instant.now().toString(),
            "gitCommit" to git(listOf("rev-parse", "--short", "HEAD")).ifBlank { "dev" },
            "gitBranch" to git(listOf("rev-parse", "--abbrev-ref", "HEAD")),
            "bundledRuntimes" to bundledJavaVersions.joinToString(", "),
        )
        val file = File(out.get().asFile, "build-info.properties").apply { parentFile.mkdirs() }
        file.writeText(props.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}
sourceSets.main { resources.srcDir(buildInfoDir) }
tasks.named("processResources") { dependsOn(generateBuildInfo) }

// --- Optional licensed icon pack (Part B; OFF by default) -----------------------------------------
// Opt-in via -PgestaltIconPackDir=<dir> or GESTALT_ICON_PACK_DIR. The dir holds a manifest.json
// (name/license/attribution/homepage) + .svg/.png/.jpg icons; when present its icons are staged as extra
// pickable entries (icons/pack/) and its attribution is recorded for About → Credits (icon-pack.properties).
// Absent ⇒ a default build with only the bundled Lucide + Health Icons (no Icons8 / brand files).
val iconPackDir: String? = (findProperty("gestaltIconPackDir") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("GESTALT_ICON_PACK_DIR")?.takeIf { it.isNotBlank() }
val iconPackStageDir = layout.buildDirectory.dir("generated/iconpack")

val stageIconPack by tasks.registering {
    group = "build"
    description = "Stage an optional licensed icon pack (gestaltIconPackDir) as extra library entries + record attribution."
    outputs.dir(iconPackStageDir)
    iconPackDir?.let { inputs.dir(it).withPropertyName("iconPackSource") }
    val stageRootProvider = iconPackStageDir
    val source = iconPackDir?.let { File(it) }
    doLast {
        val stageRoot = stageRootProvider.get().asFile
        val packDir = File(stageRoot, "icons/pack").apply { deleteRecursively(); mkdirs() }
        File(stageRoot, "icon-pack.properties").delete()

        if (source == null || !source.isDirectory) {
            logger.lifecycle("No icon pack configured — default build (Lucide + Health Icons only).")
            return@doLast
        }
        val manifestFile = File(source, "manifest.json")
        require(manifestFile.isFile) { "icon pack: $source has no manifest.json" }
        @Suppress("UNCHECKED_CAST")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<String, Any?>
        val name = (manifest["name"] as? String)?.takeIf { it.isNotBlank() }
            ?: error("icon pack manifest.json: 'name' is required")
        val license = (manifest["license"] as? String).orEmpty()
        val attribution = (manifest["attribution"] as? String).orEmpty()
        val homepage = (manifest["homepage"] as? String).orEmpty()

        val allowed = setOf("svg", "png", "jpg", "jpeg")
        val iconFiles = (source.listFiles { f -> f.isFile && f.extension.lowercase() in allowed } ?: emptyArray()).sortedBy { it.name }
        require(iconFiles.isNotEmpty()) { "icon pack: no .svg/.png/.jpg icons in $source" }

        val index = StringBuilder()
        iconFiles.forEach { f ->
            require(f.length() <= 512L * 1024) { "icon pack: ${f.name} exceeds 512 KB" }
            val ext = f.extension.lowercase()
            if (ext == "png" || ext == "jpg" || ext == "jpeg") {
                val img = javax.imageio.ImageIO.read(f) ?: error("icon pack: ${f.name} is not a readable image")
                require(img.width <= 512 && img.height <= 512) { "icon pack: ${f.name} is ${img.width}x${img.height}; max 512px" }
            } else {
                // Pack icons are an operator (release-build) input, not an untrusted upload, so this is a
                // build-time sanity gate rather than the full runtime IconSanitizer DOM scrub — but it mirrors
                // the same blocked constructs so a bad pack fails the build loudly.
                val text = f.readText()
                require(
                    !text.contains("<!DOCTYPE", ignoreCase = true) &&
                        !Regex("<script", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                        !Regex("<foreignObject", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                        !text.contains("javascript:", ignoreCase = true) &&
                        !Regex("\\son\\w+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                        !Regex("(?:xlink:)?href\\s*=\\s*[\"'](?!#)", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                        !Regex("url\\(\\s*['\"]?(?!#)", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                        !text.contains("data:", ignoreCase = true),
                ) { "icon pack: ${f.name} has a disallowed construct (DOCTYPE/script/foreignObject/javascript:/on*=/external href or url()/data:)" }
            }
            val key = f.nameWithoutExtension.lowercase().replace(Regex("[^a-z0-9._-]"), "-")
            val label = f.nameWithoutExtension.replace('-', ' ').replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
            val storedExt = if (ext == "jpeg") "jpg" else ext
            f.copyTo(File(packDir, "$key.$storedExt"), overwrite = true)
            index.append("$key|$label|$key.$storedExt\n")
        }
        File(packDir, "index.txt").writeText(index.toString())
        File(stageRoot, "icon-pack.properties").writeText(
            "iconPackName=$name\niconPackLicense=$license\niconPackAttribution=$attribution\niconPackHomepage=$homepage\n",
        )
        logger.lifecycle("Staged icon pack '$name' (${iconFiles.size} icons) + attribution.")
    }
}
sourceSets.main { resources.srcDir(iconPackStageDir) }
tasks.named("processResources") { dependsOn(stageIconPack) }

// --- Bundle two client JREs: download Temurin, verify checksum, stage into appResources ----------
// Staged under the Compose appResources OS+arch folder so the jpackage image carries exactly the
// right-architecture runtimes, reachable at runtime via compose.application.resources.dir.
val stageClientRuntimes by tasks.registering {
    group = "distribution"
    description = "Download + verify Temurin JRE ${bundledJavaVersions.joinToString(" + ")} for " +
        "$temurinOs/$temurinArch and stage them into the package."
    val runtimesRoot = appResourcesDir.map { it.dir("$osArchToken/runtimes") }
    outputs.dir(runtimesRoot)
    val downloadDir = layout.buildDirectory.dir("runtimes-download")
    doLast {
        val root = runtimesRoot.get().asFile
        val dl = downloadDir.get().asFile.apply { mkdirs() }
        for (v in bundledJavaVersions) {
            val staged = File(root, "jre$v")
            if (File(staged, "release").isFile || File(staged, "bin").isDirectory) {
                logger.lifecycle("JRE $v already staged at $staged — skipping download.")
                continue
            }
            logger.lifecycle("Resolving Temurin JRE $v for $temurinOs/$temurinArch …")
            val asset = gestalt.TemurinRuntimes.resolveAsset(v, temurinOs, temurinArch)
            val archive = File(dl, asset.name)
            if (!archive.isFile || gestalt.TemurinRuntimes.sha256(archive) != asset.sha256) {
                logger.lifecycle("Downloading ${asset.link}")
                gestalt.TemurinRuntimes.download(asset, archive)
            }
            gestalt.TemurinRuntimes.verify(archive, asset.sha256)
            logger.lifecycle("Verified ${asset.name} (sha256 ✓)")

            val tmp = File(dl, "extract-jre$v").apply { deleteRecursively(); mkdirs() }
            buildOps.fs.copy {
                from(if (asset.name.endsWith(".zip")) buildOps.archives.zipTree(archive) else buildOps.archives.tarTree(resources.gzip(archive)))
                into(tmp)
            }
            val home = gestalt.TemurinRuntimes.findJavaHome(tmp)
                ?: error("could not locate bin/java in the extracted Temurin JRE $v")
            staged.deleteRecursively()
            buildOps.fs.copy { from(home); into(staged) }
            logger.lifecycle("Staged JRE $v -> $staged")
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.gestalt.app.MainKt"
        // Modest, measured JVM tuning (applies to `run` and the packaged app). A soak showed a ~16-19 MB
        // live heap with 37 connections; jar fetches are written to disk so heap stays small. Capping well
        // below the JRE default (¼ of physical RAM) bounds the reserved footprint, and G1 with a 50 ms pause
        // target keeps the UI smooth. AppCDS is deliberately NOT enabled — it needs a brittle two-phase
        // archive step at package time for a marginal startup gain; revisit only if startup latency matters.
        jvmArgs += listOf(
            "-Xms32m",
            "-Xmx384m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:+UseStringDeduplication",
        )
        nativeDistributions {
            // Installers for every desktop OS + the app-image (the portable build zips the app-image).
            targetFormats(
                TargetFormat.Msi, TargetFormat.Exe,   // Windows (per-user, no admin)
                TargetFormat.Dmg, TargetFormat.Pkg,   // macOS
                TargetFormat.Deb, TargetFormat.Rpm,   // Linux
            )
            packageName = "OpenIntegrationEngineLauncher"
            packageVersion = appVersion
            description = "Secure desktop launcher for the Open Integration Engine (OIE) / Mirth Connect Administrator."
            vendor = appVendor
            copyright = "© the Open Integration Engine Launcher contributors. Licensed under MPL-2.0."

            // The bundled client JREs (and anything else under appResources) are baked into the image.
            appResourcesRootDir.set(appResourcesDir)
            // Ship a complete launcher runtime: the app pulls in BouncyCastle, HttpClient 5, JNA and
            // log4j2, which reach across many JDK modules — module-stripping risks a runtime NoClassDef.
            includeAllModules = true

            windows {
                iconFile.set(iconIco)
                packageVersion = appVersion
                // No administrator rights to install OR run; MDM-deployable.
                perUserInstall = true
                dirChooser = true
                // Start-menu entry + desktop shortcut under the vendor group.
                menu = true
                menuGroup = appVendor
                shortcut = true
                // Stable across releases so upgrades replace in place (do NOT regenerate per build).
                upgradeUuid = "8b6f1c4e-2a7d-4e59-9c3b-7f1a2b3c4d5e"
            }
            macOS {
                iconFile.set(iconIcns)
                packageName = "Open Integration Engine Launcher"
                dockName = "Open Integration Engine Launcher"
                bundleID = "com.gestalt.cloudconnect"
                packageVersion = appVersion
            }
            linux {
                iconFile.set(iconPng)
                packageName = "open-integration-engine-launcher"
                menuGroup = appVendor
                packageVersion = appVersion
            }
        }
    }
}

// Compose's prepareAppResources copies appResourcesRootDir into the image — it must wait for the
// runtimes to be staged there. (This is the task that actually consumes the staged JREs.)
tasks.matching { it.name == "prepareAppResources" }
    .configureEach { dependsOn(stageClientRuntimes) }
// Every installer/app-image build also needs the generated icon in place first.
tasks.matching { it.name.startsWith("package") || it.name.contains("Distributable") }
    .configureEach { dependsOn(generateAppIcons) }

// --- Portable build: zip the app-image with the portable sentinel beside the executable ----------
// Installers stay per-user (no sentinel); only THIS zip carries the sentinel, so the portable build
// is portable out of the box. The sentinel name must match AppPaths.PORTABLE_SENTINEL.
val portableSentinelName = "gestalt-portable.txt"
val portableStageDir = layout.buildDirectory.dir("portable")
val writePortableSentinel by tasks.registering {
    description = "Write the portable-mode sentinel included in the portable zip."
    val out = portableStageDir.map { it.file(portableSentinelName) }
    outputs.file(out)
    doLast {
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(
            """
            Open Integration Engine Launcher — portable mode.

            Because this file sits next to the executable, the app keeps ALL of its state
            (config, logs, cache, credentials, truststore) in the ./data folder beside the app
            instead of your per-user profile. Delete this file to switch this copy back to a
            normal per-user installation layout.
            """.trimIndent() + "\n",
        )
    }
}

// The Compose app-image for the current OS (non-release variant).
val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/OpenIntegrationEngineLauncher")
val packagePortableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Build the portable app-image zip (runs unzipped, no install, state in ./data)."
    dependsOn("createDistributable", writePortableSentinel)
    // Wrap in a top-level folder so unzipping yields a single clean directory.
    from(appImageDir) { into("OpenIntegrationEngineLauncher") }
    from(portableStageDir.map { it.file(portableSentinelName) }) { into("OpenIntegrationEngineLauncher") }
    archiveBaseName.set("OpenIntegrationEngineLauncher")
    archiveVersion.set(appVersion)
    archiveClassifier.set("portable-$osArchToken")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

// Convenience aggregate: the installer for this OS + the portable zip, from one config.
tasks.register("packageAll") {
    group = "distribution"
    description = "Build the current-OS installer(s) AND the portable app-image zip."
    dependsOn("packageDistributionForCurrentOS", packagePortableZip)
}

// =================================================================================================
// Phase 4b — code-signing + signed self-update tooling
// =================================================================================================

// --- Generate a Gestalt update keypair (dev identity) → tools/out (gitignored) -------------------
tasks.register<JavaExec>("generateUpdateKey") {
    group = "distribution"
    description = "Generate an Ed25519 update keypair: tools/out/update-{private,public}.b64. Pin the public key in UpdateKeys."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gestalt.launcher.update.UpdateKeyTool")
    args(rootProject.layout.projectDirectory.dir("tools/out").asFile.absolutePath)
}

// --- Sign an update manifest (latest.json) for the current release -------------------------------
// Reuses the app's UpdateCrypto so the signature is exactly what the app verifies. Pass arguments via
// --args; the private key is read from a FILE (never committed). Example:
//   gradle :app:signUpdateManifest --args="--key tools/out/update-private.b64 --version 1.1.0 \
//     --base-url https://updates.example.com/gestalt/stable \
//     --artifact windows-x64=build/compose/binaries/main/msi/OpenIntegrationEngineLauncher-1.1.0.msi \
//     --out build/update/latest.json"
tasks.register<JavaExec>("signUpdateManifest") {
    group = "distribution"
    description = "Produce a SIGNED update manifest (latest.json). See the task body for --args usage."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.gestalt.launcher.update.UpdateManifestTool")
}

// --- Authenticode-sign the produced Windows .exe/.msi (post-package) -----------------------------
// Dev uses a self-signed cert (tools/gen-win-codesign-cert.ps1); production swaps in a real OV/EV
// .pfx purely via env/secrets. Never commits the cert or password. Configurable:
//   -Pgestalt.signtool=<signtool.exe>  | env GESTALT_SIGNTOOL  (else auto-discovered in the Windows SDK)
//   -Pgestalt.win.pfx=<file.pfx>       | env GESTALT_WIN_PFX            (required)
//   -Pgestalt.win.pfxPassword=<pw>     | env GESTALT_WIN_PFX_PASSWORD
//   -Pgestalt.tsa=<rfc3161-url>        | env GESTALT_TSA_URL    (default http://timestamp.digicert.com)
tasks.register("signWindows") {
    group = "distribution"
    description = "Authenticode-sign + RFC-3161 timestamp the produced Windows .exe/.msi artifacts."
    doLast {
        fun cfg(prop: String, env: String): String? =
            (project.findProperty(prop) as String?)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

        val signtool = cfg("gestalt.signtool", "GESTALT_SIGNTOOL") ?: discoverSigntool()
            ?: error("signtool not found — set -Pgestalt.signtool=<path> or GESTALT_SIGNTOOL")
        val pfx = cfg("gestalt.win.pfx", "GESTALT_WIN_PFX")
            ?: error("set -Pgestalt.win.pfx=<file.pfx> or GESTALT_WIN_PFX (the code-signing certificate)")
        val password = cfg("gestalt.win.pfxPassword", "GESTALT_WIN_PFX_PASSWORD") ?: ""
        val tsa = cfg("gestalt.tsa", "GESTALT_TSA_URL") ?: "http://timestamp.digicert.com"

        val binaries = layout.buildDirectory.dir("compose/binaries/main").get().asFile
        val targets = buildList {
            File(binaries, "app/OpenIntegrationEngineLauncher/OpenIntegrationEngineLauncher.exe").let { if (it.isFile) add(it) }
            listOf("msi", "exe").forEach { fmt ->
                File(binaries, fmt).takeIf { it.isDirectory }?.listFiles { f -> f.extension.equals(fmt, true) }?.forEach { add(it) }
            }
        }
        require(targets.isNotEmpty()) { "no Windows artifacts under $binaries — run a package task first" }

        targets.forEach { artifact ->
            logger.lifecycle("Signing ${artifact.name}")
            runCatching { artifact.setWritable(true, false) } // jpackage marks the launcher .exe read-only
            buildOps.exec.exec {
                commandLine(
                    signtool, "sign",
                    "/fd", "SHA256",
                    "/f", pfx,
                    *(if (password.isNotEmpty()) arrayOf("/p", password) else emptyArray()),
                    "/tr", tsa, "/td", "SHA256",
                    "/d", "Open Integration Engine Launcher",
                    artifact.absolutePath,
                )
            }
        }
        logger.lifecycle("Signed ${targets.size} artifact(s). Verify with: signtool verify /pa <file>")
    }
}

/** Best-effort signtool discovery in an installed Windows SDK (newest x64 first). */
fun discoverSigntool(): String? {
    val kits = File("C:/Program Files (x86)/Windows Kits/10/bin")
    if (!kits.isDirectory) return null
    return kits.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name }
        ?.firstNotNullOfOrNull { File(it, "x64/signtool.exe").takeIf { exe -> exe.isFile } }?.absolutePath
}
