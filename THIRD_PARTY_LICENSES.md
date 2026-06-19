# Third-party assets & licenses

This file records third-party assets bundled in Open Integration Engine Launcher. (Third-party *code*
dependencies are tracked separately by the CycloneDX SBOM — `./gradlew cyclonedxBom`.)

## Icon sets (`:design-system`)

### Lucide — chrome / functional UI icons
- **Location:** `design-system/src/main/resources/icons/chrome/*.svg`
- **Source:** https://lucide.dev — https://github.com/lucide-icons/lucide
- **License:** ISC
- **Use:** Functional UI glyphs (connect, launch, add, edit, delete, settings, shield/shield-check,
  sort, drag, terminal, refresh, server, folder, search, chevrons, check/alert/x). Rendered as
  tintable SVG painters (`currentColor`), recolored from the active color tokens.

```
ISC License

Copyright (c) for portions of Lucide are held by Cole Bemis 2013-2022 as part of Feather (MIT).
All other copyright (c) for Lucide are held by Lucide Contributors 2022.

Permission to use, copy, modify, and/or distribute this software for any purpose with or without
fee is hereby granted, provided that the above copyright notice and this permission notice appear
in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS
SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE
AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE
OF THIS SOFTWARE.
```

### Health Icons — connection icon library (pathology-lab set)
- **Location:** `design-system/src/main/resources/icons/connection/*.svg`
- **Source:** https://healthicons.org — https://github.com/resolvetosavelives/healthicons
- **License:** CC0 1.0 Universal (public domain dedication) — no attribution required; recorded here
  for provenance.
- **Use:** User-pickable per-connection glyphs — a lean lab/diagnostic set (microscope, specimen,
  test tubes, blood work, cells, nuclei, DNA, bacteria, tissue, biopsy, hematology/biochemistry lab,
  lab search, data, virus). Outline style, recolored from the color tokens.

### Custom icons (user-uploaded, per-user)

Users may assign their own icons to connections (a brand logo, or any licensed set such as Icons8) via
**Connection form → Icon → Upload icon…**. These files are **never bundled into the shipped binary**:
they are sanitized on import and stored only under the per-user data dir (`<data>/icons/<id>.<ext>`,
referenced from a connection as `icon="custom:<id>"`). They are not redistributed, so their licensing is
the user's own responsibility. SVG uploads are sanitized (scripts, event handlers, `<foreignObject>`,
external/`javascript:` refs, and DOCTYPE/entities are stripped or the file rejected); see
`launcher-core/.../icon/IconSanitizer.kt`.

### Optional licensed icon pack (build-time, OFF by default)

A build may bundle an extra icon pack by pointing `-PgestaltIconPackDir=<dir>` (or `GESTALT_ICON_PACK_DIR`)
at a directory containing a `manifest.json` (`name`, `license`, `attribution`, `homepage`) plus the icon
files. When present, the `stageIconPack` task bundles the icons as additional pickable entries (`pack:<key>`)
and records the attribution, which is surfaced in-app under **Settings → Credits** and recorded here for the
specific build. See `docs/ICON_PACKS.md`.

- **Default builds bundle no extra pack** — only Lucide (ISC) + Health Icons (CC0) above. No Icons8 icons
  and **no company/brand logos** are shipped (those are trademarks not licensed for redistribution;
  users add their own per-user via the custom-icon upload above).
- **Icons8** is supported *only* through this attribution mechanism: bundling an Icons8 set requires the
  visible credit "Icons by Icons8 — https://icons8.com" (a paid Icons8 license is recommended; the free
  tier mandates this credit and forbids redistributing the icons as standalone files / reselling).

## Bundled client runtimes (Phase 4a packaging)

### Eclipse Temurin JRE 8 + JRE 17
- **Location (in the package):** `runtimes/jre8` and `runtimes/jre17` under the app resources.
- **Source:** Eclipse Adoptium — https://adoptium.net (binaries fetched at build time from the
  Adoptium API; **not** committed to this repo).
- **License:** **GPL v2 with the Classpath Exception (GPLv2+CE)** — the OpenJDK license. The Classpath
  Exception permits bundling and redistributing the unmodified runtime alongside independent
  application code without that code becoming subject to the GPL.
- **Use:** the two selectable client JVMs the launcher uses to run the OIE / Mirth **Administrator**
  (a connection's `Bundled(8|17)` Java home). They are **not** the launcher's own runtime (jpackage
  jlinks a separate JDK-17 runtime for the launcher itself).
- **Note:** the runtimes are redistributed **unmodified**. The full OpenJDK license text and source
  offer are available from Adoptium; see https://openjdk.org/legal/gplv2+ce.html.

## Notes
- The bundled `branding/gestalt-wordmark.svg` (header wordmark) and
  `branding/gestalt-cloud-connect-icon.svg` (the GD app-icon mark, from which the shipped
  `.ico`/`.icns`/`.png` are generated) are original Gestalt brand marks, not third-party assets. The
  previously-uploaded raster/stock/logo assets are intentionally **not** bundled.
