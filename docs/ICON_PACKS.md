# Connection icons — custom uploads & licensed packs

Open Integration Engine Launcher resolves a connection's `icon` field across three sources:

| Source | Key form | Where it lives | Tinting |
| --- | --- | --- | --- |
| Built-in | `microscope`, `dna`, … | bundled (`:design-system` resources) — Lucide (ISC) + Health Icons (CC0) | recolored from the theme (all use `currentColor`) |
| **Custom** (per-user upload) | `custom:<id>` | `<data>/icons/<id>.<ext>` (per-user, never the install dir) | SVG with `currentColor` recolors; raster / fixed-color render as authored |
| **Pack** (build-time, optional) | `pack:<key>` | bundled at build time when configured | SVG tints; raster renders as authored |

## A) Custom icons (the main feature — no build step)

In the connection form, **Icon → Upload icon…** accepts `.svg`, `.png`, or `.jpg`. The file is:

1. **Sanitized** (`launcher-core/.../icon/IconSanitizer.kt`). SVG is parsed with an XXE-hardened parser
   (DOCTYPE / external entities rejected) and scrubbed: `<script>`, `<foreignObject>`, SMIL animation,
   every `on*` handler, `javascript:`/external/`data:` `href`s and `url(…)`s are removed. Raster files are
   decoded with ImageIO to prove they are genuine images. Anything that can't be made safe is rejected.
2. **Bounded** — ≤ 512 KB; raster ≤ 512 px per side.
3. **Stored** under `<data>/icons/<id>.<ext>` (content-addressed id ⇒ duplicates collapse) with metadata in
   `<data>/icons/index.json`. The connection records only `icon="custom:<id>"`. **Export/import round-trips the
   key and never embeds the image bytes**; on a machine without the file, the connection falls back to the
   default glyph.

Manage uploads (rename / remove) from the same picker. Files live only in the per-user data dir, so they are
never part of the shipped binary and are the user's own to license.

## B) Optional licensed icon pack (build-time, OFF by default)

A downstream or custom build can bundle an extra set (e.g. an Icons8 pack) as additional library entries:

```
./gradlew :app-fx:packageAll -PgestaltIconPackDir=/path/to/pack     # or GESTALT_ICON_PACK_DIR
```

The pack directory must contain:

- `manifest.json`:
  ```json
  {
    "name": "Icons8 Lab Set",
    "license": "Icons8 Universal Multimedia License",
    "attribution": "Icons by Icons8 — https://icons8.com",
    "homepage": "https://icons8.com"
  }
  ```
  `name` is required; `license` / `attribution` / `homepage` are recommended (Icons8's free tier **requires**
  the visible "Icons by Icons8 — https://icons8.com" credit and forbids redistributing icons as standalone
  files / reselling — a paid license is recommended).
- icon files: `.svg` / `.png` / `.jpg`, each ≤ 512 KB and ≤ 512 px. The file name (minus extension) becomes the
  pack key. SVGs are checked for obvious unsafe constructs and the build fails loudly on a violation.

At build time `stageIconPack` copies the icons to `icons/pack/` on the classpath, writes `icons/pack/index.txt`,
and records the attribution in `icon-pack.properties`. At runtime the icons appear in the picker's **Icon pack**
section, and the attribution shows in **Settings → Credits**.

With no `gestaltIconPackDir` configured, the build bundles **only** Lucide + Health Icons — no Icons8 icons and
no third-party brand logos.
