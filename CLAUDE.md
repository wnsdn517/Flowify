# Working in this repo

## Workflow
- Do not enter heavy planning mode (EnterPlanMode) for routine bug-fix/feature batches. Track work
  with a simple TODO list and proceed directly to implementation.
- Verify at the end of a batch of changes (build + run relevant tests + install), not after every
  single small edit. Compiling after each file is fine/cheap; full test runs and device installs
  are a wrap-up step.
- Do not use the Agent tool (subagents) in this repo, including forks. Do the research and
  implementation directly.

## Build & install
Use `scripts/build-install.sh [--no-install]` instead of typing out the gradlew/adb
sequence by hand - it builds, installs to the connected device, force-stops Spotify so the module
reloads, and prints a clean success/failure summary (full gradle/adb output only on failure).

- Useful flags: `--no-models` (skip pushing the language model pack on rooted installs),
  `--pull` (fast-forward from upstream first; every run already reports new upstream commits),
  `--offline`, `--force` (reinstall even when the APK/models are unchanged - the script skips
  both, and the Spotify restart, when nothing changed), `--clean`.
- Requires `JAVA_HOME` pointed at a JDK 17 install (AGP 8.8.2 / Gradle 8.14.2 target); the script
  falls back to the common Windows Temurin 17 path if unset.
- The project produces one APK with all language and translation features enabled.
- This is an LSPosed/Xposed module for Spotify, not a standalone app - installing the APK only
  updates the module; it must already be scoped to `com.spotify.music` in LSPosed Manager.

For unit tests: `JAVA_HOME=<jdk17> ./gradlew :app:testDebugUnitTest`.

## Settings panel vs. Layout Editor
Settings the in-app Layout Editor already lets you edit live (position/size/style for the
artwork, track text, focus point, background, skip chip, follow chip, top controls dock - see
`LyricsLayoutEditController#TOUCHED_SETTINGS`) must NOT also be listed in
`SettingsUiSchema.java`'s panel schema. Keep each setting editable from exactly one place - the
Layout Editor for anything visual/positional it already covers, the Settings panel for everything
else - rather than duplicating a control in both surfaces.
