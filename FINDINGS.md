# Open Integration Engine Launcher 1.0 — Hardening Pass FINDINGS

Companion to `Gestalt-Cloud-Connect-Critical-Review.md`. Measured on the **real** app, not guessed.
Branch: `claude/hardening-pass`. JDK 17 (app) / JDK 11 tools. Date: 2026-06-19.

## How it was measured

A soak launched the real product (`gradlew :app:run`) against the **actual 37-connection fixture**; the
startup reachability probe ran over all 37 hosts (mostly unreachable → 1.5 s TCP timeouts, `Semaphore(8)`
capped); then idle. Captured with `jcmd` (`Thread.print`, `GC.run` + `GC.class_histogram`,
`VM.native_memory summary`), `VM.flags`, and a graceful `taskkill` (sends `WM_CLOSE` → `onCloseRequest` →
`exitApplication`) for the shutdown test. Raw captures were written to `build/diagnostics/` (removed at
cleanup once recorded here).

> NMT note: `-XX:NativeMemoryTracking` does **not** take effect via `JAVA_TOOL_OPTIONS` (NMT initializes
> before env-var options) — the flag must be on the command line. NMT was therefore captured by adding it
> to the `run` task's `jvmArgs` for the verify pass, then reverted.
>
> JFR allocation profiling of the **idle** app showed negligible allocation (the app does not allocate in
> the background). A scroll/search allocation profile needs the forked GUI driven interactively (not
> scriptable here); the recomposition concern is addressed structurally instead (see #8).

## Measurements (before → after)

| Metric | Before (JVM defaults) | After (tuned jvmArgs) |
|---|---|---|
| Live heap after forced GC (37 conns) | **16.7 MB** | **12.8 MB** |
| Heap across idle + GC | 16.7 → 19.0 MB (attach/class-load noise) | 12.82 → 12.80 MB (**flat**) |
| Max heap reserved | ~¼ physical RAM (GBs) | **384 MB** |
| Coroutine workers at rest | 8 | 8 (16 shrinking right after probe) |
| Threads during the 37-host probe burst | ~42 | elastic up to ~81 (45 workers) → shrinks back |
| NMT committed (JVM-tracked) | (could not enable) | **158 MB**, all bounded categories |
| OS working set | 252 MB | ~250 MB |
| Clean JVM exit on window close | **yes, < 4 s, exit 0** | **yes, < 4 s, exit 0** |
| GC | G1 (default) | G1 + 50 ms pause target + string dedup |

**Top retained objects** (after GC): `int[]` 7.9 MB and `byte[]` 3.5 MB (Skia/Compose render buffers),
`java.lang.Class` 0.7 MB, `String` 0.47 MB, Compose `LayoutNode`/coordinators ~0.3 MB. Nothing
app-domain retained abnormally.

**NMT committed 158 MB** = Java Heap 32 · GC 51 · Metaspace 26 · Code 13 · Symbol 9 · Shared class space
(CDS) 12 · Thread 5 (reserved 83 / committed 5) · Class 4. The ~100 MB gap to the 252 MB working set is
Skia/graphics native (not tracked by NMT) + direct buffers — bounded.

**Headline:** the app is **already memory- and thread-healthy** — no leak (heap flat 13–19 MB after GC
across load/probe/idle), bounded coroutine pool that elastically expands during the blocking-probe burst
and shrinks back, and a **clean JVM exit on window close**. The real exposure was **robustness/correctness**,
not memory.

## What was actually wrong — fixed (each its own commit)

| # | Issue | Severity | Fix | Commit |
|---|---|---|---|---|
| 4 | `connections.json` written in place (`Files.writeString`), no backup; `load()` threw on a torn file → whole list lost | High | Atomic temp+rename + `.bak`; `load()` recovers (corrupt → `.corrupt` + audit, fall back to `.bak`, else clean) | `f610c17` |
| 5 | Delete orphaned per-connection state: in-memory maps + truststore `.p12` + cached-jar subtree | Medium | `purgeConnectionState(id)` clears the maps (sync) + deletes the truststore & jar subtree (off-thread) | `b07c0f0` |
| 12 | Reused jar cache never evicted across server versions → unbounded growth | Medium | Prune stale-version jars on launch, keep current version (preserves cache-reuse speed) | `24244b6` |
| 7 | No `CoroutineExceptionHandler` → an uncaught coroutine exception was swallowed silently | Medium | Handler audits + logs any non-cancellation exception | `d14b61b` |
| 8 | `LazyColumn` had stable `key` but no `contentType` | Low | Added `contentType` to all three list paths | `513917f` |
| 2 | Self-update child processes used default PIPE stdio (nothing drains → possible stall; tied to parent handles) | Low | Redirect detached updater stdio to `DISCARD` | `1b286ed` |
| 10 | Packaged app ran with JVM defaults (¼-RAM heap) | Medium | `jvmArgs`: `-Xmx384m` (measured ~13–19 MB live), G1 + 50 ms pause, string dedup | `769bb56` |

## What was NOT wrong (measured / verified OK)

- **No memory leak** — heap flat 13–19 MB after GC across repeated load/probe/idle.
- **No thread leak** — coroutine pool bounded (8 at rest); elastic during the blocking-`Socket.connect`
  probe burst then shrinks back; every app thread is daemon.
- **Clean shutdown** (#3) — JVM exits cleanly (< 4 s, exit 0) on window close; no non-daemon hang.
- **Dependencies already current** (#11) — log4j **2.24.3** (not 2.19), BouncyCastle 1.78.1, HttpClient5
  5.4.1, Kotlin 2.4.0, Compose 1.9.3; **no HttpClient 4.x / commons-ssl anywhere**. OWASP + CycloneDX
  plugins already wired; SBOM generated this pass (`build/reports/bom.json`/`.xml`, ~900 KB).
- **HttpClient lifecycle** (#1) — per-call clients, but **all closed** (`use{}`/`finally`); none leaked.
- **Closeable hygiene** (#9) — every stream/KeyStore/socket uses `use{}`/`finally`.
- **Probe concurrency bounded** (#7) — `Semaphore(8)`; startup probe staggered (400 ms) + TTL-cached (30 s).
- **No `GlobalScope`** (#7) — `rememberCoroutineScope`, cancelled on composition dispose.
- **Compose** (#8) — strong-skipping on (Kotlin 2.4), stability config present, `derivedStateOf` for the
  filtered/sorted list, stable `key`s.
- **Lazy init** (#10) — BouncyCastle provider + crypto/HTTP subsystems initialize on first use, not at
  startup.
- **Child launch** (#2) — the main launch uses `inheritIO` (correct; no gobbler/pipe hang).

## Deferred / intentionally not changed (with rationale)

- **Icon `ImageBitmap` LRU (#6)** — low real-world impact: custom-icon bytes are `remember(id)`-keyed and
  cached (stable instance), so `GestaltCustomIcon`'s `remember(bytes,…)` does **not** re-decode per
  recomposition; decoded bitmaps are GC'd on row dispose; re-decode happens only on scroll-out-and-back for
  the rare custom raster icon (fixture has ~1). The new `contentType` + stable keys reduce row re-creation.
  A bounded id-keyed bitmap cache isn't warranted at current icon counts.
- **Pause polling when unfocused (#7)** — low impact: polling is **not continuous** (one staggered,
  TTL-cached probe on list appearance + manual "Check all"); there is no background loop to pause. Revisit
  only if continuous polling is added.
- **AppCDS (#10)** — needs a brittle two-phase archive step at package time for a marginal startup gain.
- **Single-instance lock (#4)** — low value for a per-user desktop tool; the atomic write + recovery already
  guards the corruption it would prevent.
- **Shared async HttpClient / single reactor (#1)** — efficiency refinement, not a correctness fix; per-call
  clients are all closed and the measured footprint is healthy.
- **OWASP dependency-check run (#11)** — plugin wired + gated at CVSS ≥ 7; running it needs an `NVD_API_KEY`
  + network (unavailable here). CycloneDX SBOM generation was exercised successfully.

## Verification

- Re-soak with the tuned `jvmArgs`: heap **flat** (12.82 → 12.80 MB across idle), threads return to
  baseline as the scheduler shrinks, NMT committed bounded at 158 MB, **clean exit on window close**.
- All existing tests stay green; **238 tests, 0 failures**. New regression tests: atomic-write +
  corrupt/`.bak` recovery, per-connection truststore/cache purge + in-memory map pruning, stale-version
  cache pruning.
