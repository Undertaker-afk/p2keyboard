# Vendored keyboard: HeliBoard

This directory is [HeliBoard](https://github.com/HeliBorg/HeliBoard) (GPL-3.0-only; based on
the Apache-2.0 AOSP LatinIME keyboard), copied from upstream commit
`106386356f0ff5b13ab6617bca4d1cea4d9513e6` (version 4.3-beta2, 2026-11-15).

Modifications for p2keyboard:
- `build.gradle.kts`: built as an Android library module instead of an app (library plugin,
  release/debug build types only, `APPLICATION_ID` / `VERSION_*` added to BuildConfig).
- Removed upstream unit/instrumentation tests and the debug-only resource overrides.
- The launcher entry, keyboard name and content-provider authorities are overridden from the
  app module (`app/src/main/AndroidManifest.xml`, `app/src/main/res/values/keyboard_overrides.xml`)
  so the source here stays as upstream wrote it.

To update: copy upstream `app/src` over `src` (minus `test`/`androidTest`/`debug*`) and re-apply
the `build.gradle.kts` changes above.
