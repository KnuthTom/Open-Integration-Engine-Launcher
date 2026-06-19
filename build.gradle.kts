/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

// Root build script. Declares plugin versions for the whole build but applies Kotlin/Compose
// nothing here — each module opts into those. The supply-chain plugins (SBOM + dependency-check)
// ARE applied at the root so they aggregate across every module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false

    // CycloneDX SBOM — `./gradlew cyclonedxBom` writes build/reports/bom.{json,xml}.
    alias(libs.plugins.cyclonedx)
    // OWASP dependency-check — `./gradlew dependencyCheckAggregate` (needs NVD_API_KEY).
    alias(libs.plugins.owasp.depcheck)
}

// Coordinates for the aggregate SBOM (CycloneDX requires group + name + version on the root).
// The version is single-sourced from `gestaltVersion` in gradle.properties so the SBOM, the
// jpackage package metadata, and the in-app About screen never drift apart.
group = "com.gestalt"
version = (findProperty("gestaltVersion") as String?) ?: "1.0.0"

// --- CycloneDX: aggregate SBOM across all modules, emitted as a build artifact ---
tasks.named<org.cyclonedx.gradle.CycloneDxTask>("cyclonedxBom") {
    setProjectType("application")
    setSchemaVersion("1.5")
    setDestination(file("build/reports"))
    setOutputName("bom")
    setOutputFormat("all") // json + xml
    setIncludeBomSerialNumber(true)
    setIncludeConfigs(listOf("runtimeClasspath"))
    setSkipConfigs(listOf("testCompileClasspath", "testRuntimeClasspath"))
}

// --- OWASP dependency-check: gate CI on high-severity CVEs. Runs only when invoked. ---
dependencyCheck {
    // CVSS >= 7.0 (High) fails the build. Tune per policy.
    failBuildOnCVSS = 7.0f
    // The NVD now requires an API key; supply it via env so it never lives in the repo.
    nvd.apiKey = providers.environmentVariable("NVD_API_KEY").orNull
    analyzers.assemblyEnabled = false
    formats = listOf("HTML", "JSON")
    // Only production code paths matter for shipped-artifact CVEs.
    skipConfigurations = listOf(
        "testCompileClasspath", "testRuntimeClasspath",
    )
}
