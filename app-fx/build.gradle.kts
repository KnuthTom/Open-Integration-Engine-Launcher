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
import java.util.zip.ZipFile
import javax.inject.Inject

// The product UI, rebuilt on JavaFX (the design's build notes target "Java 17+ / JavaFX 17+": stock
// controls and JavaFX CSS, no WebView, no icon fonts). JavaFX 21 is the newest line that runs on the
// JDK 17 runtime the launcher already ships with. This module sits beside :app (Compose) until it
// reaches parity, then :app and :design-system retire.
//
// Packaging reuses the Compose Multiplatform Gradle plugin purely as a jpackage wrapper (installers,
// app-image, WiX download, bundled resources) — no Compose runtime is on the classpath. JavaFX runs
// from the classpath in the packaged app (a plain `main` starts it; see Main.kt).
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Applied only because the Compose GRADLE plugin below refuses to configure without it (Kotlin >= 2.0).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
    alias(libs.plugins.javafx)
}

kotlin {
    jvmToolchain(17)
}

// Keep the Compose COMPILER out of every compilation in this module. Left on, it aborts the build because
// the Compose runtime is (deliberately) not on the classpath: "The Compose Compiler requires the Compose
// Runtime to be on the class path". With no target platform it is never attached to compileKotlin or
// compileTestKotlin, so no Compose artifact enters :app-fx at all.
composeCompiler {
    targetKotlinPlatforms.empty()
}

javafx {
    version = libs.versions.javafx.get()
    modules = listOf("javafx.controls", "javafx.graphics", "javafx.base")
}

val appVersion = (findProperty("gestaltVersion") as String?) ?: "1.0.0"
val appVendor = (findProperty("gestaltVendor") as String?) ?: "Open Integration Engine Launcher"

dependencies {
    // The fail-closed launch path, connection model/store, trust stores, credentials, audit.
    implementation(project(":launcher-core"))
    implementation(project(":core-trust"))

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    // log4j-core on the test classpath so the AUDIT logger has a backend during tests.
    testRuntimeOnly(libs.log4j.core)
}

// Version shown in the title bar; single-sourced from gradle.properties like the SBOM and installers.
tasks.processResources {
    inputs.property("version", appVersion)
    filesMatching("com/gestalt/fx/app-fx.properties") {
        expand("version" to appVersion)
    }
}

tasks.test {
    useJUnitPlatform()
    // Keep any per-user state under build/ during tests so the suite never touches the real profile.
    systemProperty("gestalt.home", layout.buildDirectory.dir("test-gestalt-home").get().asFile.absolutePath)
    // Model tests run headless: no toolkit, no window.
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// =================================================================================================
// Packaging: per-user installers and a portable app-image, each bundling the TWO client JREs
// (Zulu FX 8 + 17 — JavaFX-bearing JREs the Administrator runs on, NOT the launcher's own runtime) and the app icon.
// =================================================================================================

// Gradle-9 readiness: reach process/file/archive operations through INJECTED services rather than
// Project.exec / Project.copy / Project.zipTree at execution time.
interface InjectedBuildOps {
    @get:Inject val exec: ExecOperations
    @get:Inject val fs: FileSystemOperations
    @get:Inject val archives: ArchiveOperations
}
val buildOps = objects.newInstance<InjectedBuildOps>()

/** Client runtimes bundled for launching the Administrator (NOT the launcher's own runtime). */
val bundledJavaVersions = listOf(8, 17)

private val hostOs = System.getProperty("os.name").orEmpty().lowercase()
private val hostArch = System.getProperty("os.arch").orEmpty().lowercase()
val composeOs = when {
    hostOs.contains("win") -> "windows"
    hostOs.contains("mac") || hostOs.contains("darwin") -> "macos"
    else -> "linux"
}
val composeArch = if (hostArch.contains("aarch64") || hostArch.contains("arm64")) "arm64" else "x64"
val osArchToken = "$composeOs-$composeArch"
val temurinOs = composeOs // Azul names the platforms windows | macos | linux
val temurinArch = if (composeArch == "arm64") "aarch64" else "x64"

val appResourcesDir = layout.buildDirectory.dir("appResources")
val packagingDir = layout.projectDirectory.dir("packaging")

// --- Bundle two client JREs: download Zulu FX, verify checksum, stage into appResources ----------
// Staged under the appResources OS+arch folder so the jpackage image carries exactly the
// right-architecture runtimes, reachable at runtime via compose.application.resources.dir
// (ChildJvmCommandBuilder resolves runtimes/jre<version> under it).
val stageClientRuntimes by tasks.registering {
    group = "distribution"
    description = "Download + verify Zulu FX JRE ${bundledJavaVersions.joinToString(" + ")} (with JavaFX) for " +
        "$temurinOs/$temurinArch and stage them into the package."
    val runtimesRoot = appResourcesDir.map { it.dir("$osArchToken/runtimes") }
    // The vendor is an input so a switch of runtime (plain → JavaFX-bearing) re-runs the staging.
    inputs.property("runtimeFlavour", "zulu-fx")
    inputs.property("versions", bundledJavaVersions.joinToString(","))
    // The Administrator's process host is compiled into each runtime's bin on Windows (see brandClientRuntimes below).
    inputs.file(packagingDir.file("AdministratorHost.cs"))
    inputs.file(packagingDir.file("app.ico"))
    inputs.property("hostVersion", appVersion)
    inputs.property("hostVendor", appVendor)
    outputs.dir(runtimesRoot)
    val downloadDir = layout.buildDirectory.dir("runtimes-download")
    doLast {
        val root = runtimesRoot.get().asFile
        val dl = downloadDir.get().asFile.apply { mkdirs() }
        for (v in bundledJavaVersions) {
            val staged = File(root, "jre$v")
            if (File(staged, "release").isFile || File(staged, "bin").isDirectory) {
                if (gestalt.ClientRuntimes.hasJavaFx(staged)) {
                    logger.lifecycle("JRE $v already staged at $staged — skipping download.")
                    continue
                }
                // A runtime staged by an earlier build without JavaFX: the Administrator can't run on it.
                logger.lifecycle("JRE $v at $staged has no JavaFX — replacing it.")
                staged.deleteRecursively()
            }
            logger.lifecycle("Resolving Zulu FX JRE $v for $temurinOs/$temurinArch …")
            val asset = gestalt.ClientRuntimes.resolveAsset(v, temurinOs, temurinArch)
            val archive = File(dl, asset.name)
            if (!archive.isFile || gestalt.ClientRuntimes.sha256(archive) != asset.sha256) {
                logger.lifecycle("Downloading ${asset.link}")
                gestalt.ClientRuntimes.download(asset, archive)
            }
            gestalt.ClientRuntimes.verify(archive, asset.sha256)
            logger.lifecycle("Verified ${asset.name} (sha256 ✓)")

            val tmp = File(dl, "extract-jre$v").apply { deleteRecursively(); mkdirs() }
            buildOps.fs.copy {
                from(if (asset.name.endsWith(".zip")) buildOps.archives.zipTree(archive) else buildOps.archives.tarTree(resources.gzip(archive)))
                into(tmp)
            }
            val home = gestalt.ClientRuntimes.findJavaHome(tmp)
                ?: error("could not locate bin/java in the extracted Zulu FX JRE $v")
            staged.deleteRecursively()
            buildOps.fs.copy { from(home); into(staged) }
            logger.lifecycle("Staged JRE $v -> $staged")
        }
        if (composeOs == "windows") brandClientRuntimes(root)
    }
}

compose.desktop {
    application {
        mainClass = "com.gestalt.fx.MainKt"
        // Modest JVM tuning for the launcher's own process (applies to `run` and the packaged app).
        jvmArgs += listOf(
            "-Xms32m",
            "-Xmx384m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:+UseStringDeduplication",
            "-Djava.awt.headless=true",
        )
        nativeDistributions {
            targetFormats(
                TargetFormat.Msi, TargetFormat.Exe,   // Windows (per-user, no admin)
                TargetFormat.Dmg, TargetFormat.Pkg,   // macOS
                TargetFormat.Deb, TargetFormat.Rpm,   // Linux
            )
            packageName = "OpenIntegrationEngineLauncher"
            packageVersion = appVersion
            // Task Manager and the file's Properties name the launcher's process by this.
            description = "Open Integration Engine Launcher"
            vendor = appVendor
            copyright = "© the Open Integration Engine Launcher contributors. Licensed under MPL-2.0."

            // The bundled client JREs (and anything else under appResources) are baked into the image.
            appResourcesRootDir.set(appResourcesDir)
            // Ship a complete launcher runtime: BouncyCastle, HttpClient 5, JNA, log4j2, ImageIO and
            // JavaFX reach across many JDK modules — module-stripping risks a runtime NoClassDef.
            includeAllModules = true

            windows {
                iconFile.set(packagingDir.file("app.ico"))
                packageVersion = appVersion
                // No administrator rights to install OR run; MDM-deployable.
                perUserInstall = true
                dirChooser = true
                menu = true
                menuGroup = appVendor
                shortcut = true
                // Stable across releases so upgrades replace in place (do NOT regenerate per build).
                upgradeUuid = "8b6f1c4e-2a7d-4e59-9c3b-7f1a2b3c4d5e"
            }
            macOS {
                iconFile.set(packagingDir.file("app.icns"))
                packageName = "Open Integration Engine Launcher"
                dockName = "Open Integration Engine Launcher"
                bundleID = "com.gestalt.cloudconnect"
                packageVersion = appVersion
            }
            linux {
                iconFile.set(layout.projectDirectory.file("src/main/resources/com/gestalt/fx/icon/app-512.png"))
                packageName = "open-integration-engine-launcher"
                menuGroup = appVendor
                packageVersion = appVersion
            }
        }
    }
}

// --- Brand the Administrator's process (Windows) --------------------------------------------------
// Task Manager names a process by its executable's FileDescription, and a Zulu javaw.exe says "Zulu
// Platform x64 Architecture". So each bundled runtime gets a host of its own in bin\ — a tiny program
// that hands the command line to the runtime's jli.dll exactly as javaw.exe does and runs the client in
// its own process (packaging/AdministratorHost.cs) — compiled by stageClientRuntimes (one task owns the
// runtimes directory) with the C# compiler every Windows carries (.NET Framework 4.x, through a
// PowerShell script file — never a -Command string, whose inner quotes the CRT would strip), its
// version resource carrying the product name. ChildJvmCommandBuilder starts the client through it when
// it is there. No Azul binary is altered.
val administratorHostName = "OpenIntegrationEngineAdministrator"
val administratorHostSource = packagingDir.file("AdministratorHost.cs")
val administratorHostIcon = packagingDir.file("app.ico")

/** Compile the host into `<runtime>/bin` for every bundled runtime under [runtimesRoot]. Windows only. */
fun brandClientRuntimes(runtimesRoot: File) {
    val work = layout.buildDirectory.dir("administrator-host").get().asFile.apply { mkdirs() }
    val stamped = File(work, "AdministratorHost.cs")
    val fourPart = appVersion.split('.').map { it.filter(Char::isDigit).ifEmpty { "0" } }.let { (it + listOf("0", "0", "0", "0")).take(4) }.joinToString(".")
    stamped.writeText(
        administratorHostSource.asFile.readText()
            .replace("@VERSION@", fourPart)
            .replace("@VENDOR@", appVendor.replace("\"", "\\\""))
            .replace("@COPYRIGHT@", "© the Open Integration Engine Launcher contributors. MPL-2.0."),
    )
    fun ps(s: String) = "'" + s.replace("'", "''") + "'"
    for (v in bundledJavaVersions) {
        val bin = File(runtimesRoot, "jre$v/bin")
        check(File(bin, "jli.dll").isFile) { "no jli.dll in $bin — the runtime is not a Windows JRE" }
        for ((name, console) in listOf("$administratorHostName.exe" to false, "$administratorHostName-console.exe" to true)) {
            val out = File(bin, name)
            val options = (if (console) "/target:exe" else "/target:winexe") +
                " /platform:anycpu /optimize+ /nologo /win32icon:\"" + administratorHostIcon.asFile.absolutePath + "\"" +
                (if (console) " /define:CONSOLE" else "")
            val script = File(work, "build-${if (console) "console" else "windowed"}.ps1")
            script.writeText(
                """
                ${'$'}ErrorActionPreference = 'Stop'
                ${'$'}cp = New-Object System.CodeDom.Compiler.CompilerParameters
                ${'$'}cp.GenerateExecutable = ${'$'}true
                ${'$'}cp.GenerateInMemory = ${'$'}false
                ${'$'}cp.OutputAssembly = ${ps(out.absolutePath)}
                ${'$'}cp.CompilerOptions = ${ps(options)}
                ${'$'}cp.ReferencedAssemblies.Add('System.dll') | Out-Null
                ${'$'}provider = New-Object Microsoft.CSharp.CSharpCodeProvider
                ${'$'}result = ${'$'}provider.CompileAssemblyFromFile(${'$'}cp, ${ps(stamped.absolutePath)})
                if (${'$'}result.Errors.HasErrors) { ${'$'}result.Errors | ForEach-Object { Write-Host ${'$'}_.ToString() }; exit 1 }
                """.trimIndent(),
            )
            buildOps.exec.exec {
                commandLine("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
            }
            check(out.isFile) { "the Administrator host was not produced at $out" }
            logger.lifecycle("Built ${out.name} into $bin")
        }
    }
}

// prepareAppResources copies appResourcesRootDir into the image — it must wait for the runtimes (and their hosts).
tasks.matching { it.name == "prepareAppResources" }
    .configureEach { dependsOn(stageClientRuntimes) }

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
            (config, logs, cache, credentials, trust stores) in the ./data folder beside the app
            instead of your per-user profile. Delete this file to switch this copy back to a
            normal per-user installation layout.
            """.trimIndent() + "\n",
        )
    }
}

val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/OpenIntegrationEngineLauncher")
val packagePortableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Build the portable app-image zip (runs unzipped, no install, state in ./data)."
    dependsOn("createDistributable", writePortableSentinel)
    from(appImageDir) { into("OpenIntegrationEngineLauncher") }
    from(portableStageDir.map { it.file(portableSentinelName) }) { into("OpenIntegrationEngineLauncher") }
    archiveBaseName.set("OpenIntegrationEngineLauncher")
    archiveVersion.set(appVersion)
    archiveClassifier.set("portable-$osArchToken")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

// --- Guard: nothing from a user profile ever rides in a package ---------------------------------
// Connections, credentials, trust decisions, prefs, logs and the admission cache live in the per-user
// data dir (or ./data beside a portable copy) and are never build inputs. This checks the finished
// app-image and the staged resources anyway — every file, and every entry of every jar — and fails
// the package if any such file is present (e.g. a ./data folder left beside an app-image that was run
// in place). Every package* task depends on it.
val profileFileNames = setOf(
    "connections.json", "connections.json.bak", "ui-prefs.properties", "trusted-certs.json", "trusted-signers.json",
    "admissions.json", "audit.log", "instance.lock", "ui-fonts.css",
)
val profileFileSuffixes = listOf(".dpapi", ".corrupt", "-tunnel.log")
val profileFilePrefixes = listOf("ui-theme-") // the generated palette stylesheets (ui-theme-<bg>-<accent>.css)
val profileDirName = "data" // the portable layout's state folder, should one be left beside an app-image that was run in place
val verifyNoProfileData by tasks.registering {
    group = "verification"
    description = "Fail if the app-image or the staged resources carry anything from a user profile (connections, credentials, trust, prefs, logs)."
    dependsOn("createDistributable", stageClientRuntimes)
    val roots = listOf(appImageDir, appResourcesDir)
    doLast {
        val offenders = ArrayList<String>()
        fun check(name: String, where: String) {
            if (name in profileFileNames || profileFileSuffixes.any { name.endsWith(it) } || profileFilePrefixes.any { name.startsWith(it) }) offenders += where
        }
        for (root in roots) {
            val dir = root.get().asFile
            if (!dir.isDirectory) continue
            dir.walkTopDown().forEach { f ->
                if (f.isDirectory) {
                    if (f.name == profileDirName && f.parentFile == dir && f.listFiles()?.isNotEmpty() == true) offenders += f.path
                    return@forEach
                }
                check(f.name, f.path)
                if (f.name.endsWith(".jar")) {
                    // (Imported at the top: inside a build script `java` is the JavaPluginExtension, not the package.)
                    runCatching {
                        ZipFile(f).use { zip ->
                            for (e in zip.entries()) check(e.name.substringAfterLast('/'), "${f.path}!${e.name}")
                        }
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("Profile data must never be packaged — remove these before packaging:\n  " + offenders.joinToString("\n  "))
        }
        println("verifyNoProfileData: no profile data in the app-image or the staged resources.")
    }
}
tasks.matching { it.name.startsWith("package") && it.name != "packageAll" }
    .configureEach { dependsOn(verifyNoProfileData) }

tasks.register("packageAll") {
    group = "distribution"
    description = "Build the current-OS installer(s) AND the portable app-image zip."
    dependsOn("packageDistributionForCurrentOS", packagePortableZip)
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
    val kits = File(System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)", "Windows Kits\\10\\bin")
    if (!kits.isDirectory) return null
    return kits.listFiles { f -> f.isDirectory && f.name.startsWith("10.") }
        ?.sortedByDescending { it.name }
        ?.map { File(it, "x64\\signtool.exe") }
        ?.firstOrNull { it.isFile }
        ?.absolutePath
}
