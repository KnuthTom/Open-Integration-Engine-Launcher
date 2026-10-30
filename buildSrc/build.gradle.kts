/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

// The packaging helpers (Java2D icon rendering, Zulu FX runtime download/verify) deliberately use
// ONLY the JDK that runs Gradle — java.desktop + javax.imageio for icons, java.net.http for the
// download, java.security.MessageDigest for the checksum — plus the Groovy bundled with the Gradle
// API for parsing the Azul JSON. No third-party build dependencies, so the build bootstraps on a
// plain JDK 17 with nothing else installed.
dependencies {
    implementation(localGroovy())
}
