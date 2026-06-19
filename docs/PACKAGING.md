# Packaging Open Integration Engine Launcher (Phase 4a)

This is **build / packaging only** — it changes how the app is *distributed*, not how it *behaves*.
The packaged app is the same app as `:app:run`.

It produces, from one Gradle config:

- **Per-user installers** (no administrator rights to install **or** run; MDM-deployable): Windows
  `.msi` / `.exe`, macOS `.dmg` / `.pkg`, Linux `.deb` / `.rpm`.
- **A portable app-image zip** that runs unzipped with no install and keeps all of its state beside
  the app.

Every artifact **bundles two client JREs** (Eclipse Temurin 8 + 17) for launching the OIE / Mirth
Administrator, and carries the Gestalt **GD app icon**. The launcher itself runs on its own
Compose/jpackage runtime (JDK 17).

> jpackage is **per-host**: each OS's installers can only be built *on* that OS. The Gradle config is
> identical on every host — building the macOS/Linux artifacts is just a matter of running the same
> tasks on a macOS/Linux box.

---

## Prerequisites

| Need | Why | Notes |
|------|-----|-------|
| **JDK 17 with `jpackage`** | Compose's `nativeDistributions` wraps `jpackage` (and `jlink`). | The Gradle toolchain is pinned to 17. A full JDK (not a JRE) is required — `jpackage` ships in the JDK `bin`. |
| **Network access** | The two client JREs are downloaded from Adoptium at package time. | Not committed to the repo; see *Bundled runtimes* below. |
| **WiX Toolset v3** (Windows only) | `jpackage` shells out to WiX to build the `.msi` / `.exe`. | Only needed for the **installers**. The **portable app-image** and `createDistributable` do **not** need WiX. Install from https://wixtoolset.org/ (v3.x) and ensure `candle`/`light` are on `PATH`. |

> Gradle is not on `PATH` on the dev box; use the wrapper (`./gradlew`, bootstraps Gradle 8.14.5) or
> a standalone dist under `C:\dev\_gradle_dist\`. The pinned toolchain (Gradle / Kotlin / Compose)
> and how to keep the build deprecation-clean are recorded in [BUILDING.md](BUILDING.md).

---

## Build commands

```bash
# Everything for this OS: installer(s) + portable zip, from one config.
./gradlew :app:packageAll

# Just the per-user installer(s) for the current OS (.msi/.exe | .dmg/.pkg | .deb/.rpm).
./gradlew :app:packageDistributionForCurrentOS

# Just the portable app-image zip.
./gradlew :app:packagePortableZip

# The raw app-image (a runnable directory; no installer tooling / no WiX needed).
./gradlew :app:createDistributable

# Building blocks (run automatically by the above; useful in isolation):
./gradlew :app:stageClientRuntimes   # download + checksum-verify + stage Temurin 8 & 17
./gradlew :app:generateAppIcons      # render .ico/.icns/.png from the GD mark
```

Outputs land under `app/build/`:

- Installers → `app/build/compose/binaries/main/<format>/`
- App-image → `app/build/compose/binaries/main/app/GestaltCloudConnect/`
- Portable zip → `app/build/distributions/GestaltCloudConnect-<version>-portable-<os>-<arch>.zip`

---

## What's in the package

```
GestaltCloudConnect/            <- app-image root (the .exe sits here on Windows)
├─ GestaltCloudConnect.exe      <- the launcher
├─ runtime/                     <- the launcher's own JDK-17 runtime (jlink'd by jpackage)
├─ app/                         <- the launcher jars + resources
│  └─ resources/                <- appResources, exposed at runtime as compose.application.resources.dir
│     └─ runtimes/
│        ├─ jre8/               <- bundled Temurin JRE 8  (bin/java…)
│        └─ jre17/              <- bundled Temurin JRE 17 (bin/java…)
└─ (portable zip only) gestalt-portable.txt   <- the portable sentinel
```

### Bundled runtimes (the two client JREs)

`./gradlew :app:stageClientRuntimes` is the **runtime-acquisition build step**:

1. Asks the Adoptium API for the latest GA **Temurin JRE 8** and **JRE 17** for the host OS/arch.
2. Downloads each and **verifies the publisher's SHA-256** before staging (a mismatch fails the build).
3. Normalizes the layout (strips the leading version dir / the macOS `Contents/Home` nesting) so every
   runtime has the uniform shape `runtimes/jre<N>/bin/java[.exe]`.
4. Stages them under the Compose `appResources` OS+arch folder, so the image carries exactly the
   right-architecture runtimes.

JRE binaries are **never committed** to the repo. The staging task is idempotent — an already-staged
runtime is reused.

**How a connection resolves `Bundled(8|17)`** (Phase-1 hook, wired here):
`ChildJvmCommandBuilder` resolves the child-JVM `java` from `runtimes/jre<version>` **relative to**
`compose.application.resources.dir`. Compose/jpackage sets that property to the app's resources dir at
runtime, tracking the launcher's own location — so it resolves correctly for **both** the installed app
and a portable app-image that has been moved/unzipped anywhere. `Default` and `Custom` Java homes are
unchanged. For a dev run without a packaged image, set `-Dgestalt.java.8.home` / `-Dgestalt.java.17.home`
(or `GESTALT_JAVA_8_HOME` / `GESTALT_JAVA_17_HOME`); if no bundled runtime is found, the launcher falls
back to its own JVM.

### App icon

`./gradlew :app:generateAppIcons` renders the GD mark into `app/build/icons/`:

- `gestalt-cloud-connect-icon.ico` — multi-size Windows icon (16…256).
- `gestalt-cloud-connect-icon.png` — 512px, the Linux icon (plus a full PNG set under `png/`).
- `gestalt-cloud-connect-icon.icns` — macOS icon (for the macOS build host).

The **design source** is `design-system/src/main/resources/branding/gestalt-cloud-connect-icon.svg`
(the brand's H&E-staining motif). The raster set is generated from matching geometry in
`buildSrc/.../gestalt/AppIconGenerator.kt` with **no external rasterizer** (ImageMagick / Inkscape are
not assumed present) — keep the SVG and the generator in sync. The in-app header keeps the **wordmark**
logo from `Branding`; only the OS app icon (Start menu / taskbar / Dock / installer) uses this mark.

> macOS note: the `.icns` is generated cross-platform here for convenience. If you prefer the native
> path, regenerate it on the macOS build host from the SVG/1024px PNG with `iconutil`/`png2icns` and
> point `macOS.iconFile` at it.

---

## The two layouts

### Installed (default — installers)

Per-user, no admin. State lives in the conventional per-user location (unchanged from Phase 1):

| OS | Data dir |
|----|----------|
| Windows | `%LOCALAPPDATA%\GestaltCloudConnect` |
| macOS | `~/Library/Application Support/GestaltCloudConnect` |
| Linux | `$XDG_DATA_HOME/GestaltCloudConnect` (or `~/.local/share/GestaltCloudConnect`) |

Connections, the verified-artifact cache, credential blobs, `ui-prefs.properties`, and
`logs/audit.log` all live under that root.

### Portable (the zip)

Unzip anywhere and run — no install, no admin. Because a sentinel file **`gestalt-portable.txt`** sits
next to the executable, the app redirects **all** state to a **`./data`** folder beside the app instead
of the per-user dirs. The portable zip ships with the sentinel included, so it is portable out of the
box.

**Toggle portable mode on an existing copy:**

- **On:** drop an empty (or any) `gestalt-portable.txt` next to the executable.
- **Off:** delete `gestalt-portable.txt` — that copy reverts to the per-user layout.

`-Dgestalt.home=<path>` still forces the data root and takes precedence over both layouts (used by
tests). The current mode is shown in **Settings → About** ("Portable" vs "Installed").

---

## Versioning / metadata

The version is single-sourced from **`gestaltVersion`** in `gradle.properties` (currently `1.0.0`) and
flows into the root project version, the jpackage `packageVersion`, the CycloneDX SBOM, and a generated
`build-info.properties` resource. **Settings → About** shows the app name, version (+ git commit), the
vendor (**Gestalt Diagnostics**), the build time, the bundled runtimes, and the layout mode.

---

## Acceptance checklist

| Acceptance | How |
|------------|-----|
| Package task produces a Windows installer **and** a portable app-image | `./gradlew :app:packageAll` (installer needs WiX) |
| Installs without admin; GD icon in Start menu / taskbar / installer | per-user MSI (`perUserInstall = true`) + `windows.iconFile` |
| Both client runtimes present | `runtimes/jre8` + `runtimes/jre17` staged by `stageClientRuntimes` |
| `Bundled` Java 17 launches the Administrator; `Bundled` 8 resolves to the bundled JRE | `ChildJvmCommandBuilder` → `compose.application.resources.dir/runtimes/jre<N>` |
| Portable: state in `./data`, no writes to `%LOCALAPPDATA%`, no install | `gestalt-portable.txt` sentinel → `AppPaths` `./data` redirect |
| Phase 0–3 tests stay green; packaged app behaves identically | `./gradlew build` |
