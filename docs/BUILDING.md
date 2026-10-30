# Building Open Integration Engine Launcher — toolchain & maintenance log

This is the **single source of truth for the build toolchain** (Gradle / Kotlin / Compose) and for
keeping the build *deprecation-clean* as Gradle marches toward 9.0. It is build-tooling only — nothing
here changes how the app behaves.

## Pinned toolchain

| Tool | Version | Where it's pinned |
|------|---------|-------------------|
| **Gradle** | **8.14.5** (latest 8.x) | `gradle/wrapper/gradle-wrapper.properties` (`distributionUrl`) |
| **Kotlin** | **2.4.0** | `gradle/libs.versions.toml` → `kotlin` (drives `kotlin-jvm`, the bundled `kotlin.plugin.compose` compiler, and `kotlin.plugin.serialization` — all one version) |
| **Compose Multiplatform** | **1.9.3** | `gradle/libs.versions.toml` → `compose` (the `org.jetbrains.compose` Gradle plugin) |
| **JVM toolchain** | **17** | each module's `kotlin { jvmToolchain(17) }`; a full JDK 17 (with `jpackage`) is needed for packaging |

> Since Kotlin 2.0 the Compose **compiler** ships *inside* Kotlin (`org.jetbrains.kotlin.plugin.compose`,
> versioned with `kotlin`), so it is always aligned to the Kotlin version automatically. The
> `org.jetbrains.compose` plugin (`compose` above) only carries the Compose **runtime/BOM + the desktop
> packaging DSL** — its version is chosen independently as "latest stable".

## Gradle-9 readiness

The build is **clean of deprecation warnings originating in our own build scripts**. To re-verify after
any change:

```bash
./gradlew build --warning-mode all      # must show zero "is deprecated" lines from build.gradle.kts
```

Conventions that keep it clean (re-check these when editing `*.gradle.kts`):

- Use `layout.buildDirectory` — never `project.buildDir` / `buildDir`.
- In ad-hoc (`tasks.register {}`) task actions, reach process/file/archive operations through the
  **injected services** holder `InjectedBuildOps` in `app/build.gradle.kts`
  (`ExecOperations` / `FileSystemOperations` / `ArchiveOperations`), **not** `Project.exec {}` /
  `Project.copy {}` / `Project.zipTree(...)` (deprecated for removal in Gradle 9.0).
- No `archivesBaseName`, no `*Convention` accessors, no `withJava()` in a `kotlin {}` block.

The only deprecation the build ever surfaced was **plugin-origin** (the Compose plugin calling
`Provider.forUseAtConfigurationTime` while configuring WiX); it is gone as of Compose 1.9.3. There are
currently **no remaining upstream/plugin-origin deprecations**.

## Wrapper stays on 8.x for now

The wrapper is deliberately on the **latest 8.x (8.14.5)**, not 9.x. Moving to Gradle 9 is a separate
step to be taken **only after** the build is warning-clean (done) **and** the native installers
(`packageMsi`/`packageExe`/`packageDmg`/`packageDeb`) are verified on Gradle 9.x.

## Verifying packaging

`./gradlew :app-fx:createDistributable` (jpackage app-image) and `./gradlew :app-fx:packageMsi` are the fast
and full packaging checks. **Heads-up:** the bundled MSI is large (~170 MB — it carries two Temurin
client JREs + all JDK modules), and WiX's `light.exe` linker needs a couple of **GB of free disk** for
its temp while building it. If `packageMsi` fails with `light.exe ... exited with 1 code`, check free
disk first (`df -h`) — that is almost always the cause, not the build config.

## Upgrade history

| Date | Change |
|------|--------|
| 2026-06-18 | Gradle-9-readiness pass: Gradle wrapper **8.10.2 → 8.14.5**, Kotlin **2.0.21 → 2.4.0**, Compose MP **1.7.3 → 1.9.3**; migrated `Project.exec {}` / `Project.copy {}` to injected services. Removed the Compose `forUseAtConfigurationTime` deprecation; build is now warning-clean. All tests + MSI packaging green. |
