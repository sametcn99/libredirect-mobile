# Contributing to LibRedirect Mobile

Thanks for your interest in contributing.

## Project language policy

All code, comments, commit messages, UI strings, and documentation in this
repository must be written in English, with no exceptions. (User-facing
translations live in `android/app/src/main/res/values-tr/` for the Turkish
locale and are maintained alongside the English source strings.)

## Repository structure

- `android/app/` — the Kotlin/Compose Android application: navigation,
  settings UI, redirect entry point (`RedirectActivity`), built-in browser,
  manifest repository with live instance refresh, custom services, and the
  update checker.
- `android/core/` — the pure-Kotlin routing engine: manifest model and
  validator, URL router, template renderer, exception matcher, and URL
  utilities. It has no Android dependencies, so its tests run on the JVM
  (`:core:test`).
- `tools/` — the Bun/TypeScript tooling that converts upstream LibRedirect
  data into the routing manifest and validates it against
  `schema/routes.schema.json`.
- `schema/` — the JSON Schema the generated manifest must satisfy.
- `fixtures/` — real-URL regression fixtures for the converter.

`android/core` and `android/app` are separate Gradle modules; `tools/` has
its own toolchain. Changes to one do not require touching the others, but
the manifest contract binds all of them together.

## Development setup

### Android (`android/`)

Requirements: JDK 17, Android SDK (compileSdk as pinned in
`android/gradle/libs.versions.toml`).

```bash
cd android
./gradlew ktlintCheck detekt      # static analysis
./gradlew :core:test              # routing engine unit tests (JVM)
./gradlew testDebugUnitTest       # app module unit tests
./gradlew assembleDebug           # build the debug APK
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

### Tooling (`tools/`)

Requirements: [Bun](https://bun.sh) 1.1 or later.

```bash
cd tools
bun install
bun run lint           # Biome lint
bun run format:check   # Biome format check
bun run typecheck      # tsc --noEmit
bun test
```

### Regenerating the bundled manifest

The manifest shipped in the APK is committed at
`android/app/src/main/assets/routes.json`. To regenerate it from live
upstream LibRedirect data:

```bash
cd tools
bun run generate ../../android/app/src/main/assets/routes.json
```

The converter follows a strict "never guess" principle: a target, frontend,
or service that cannot be converted with full confidence is dropped and
reported, not approximated. If `generate` reports dropped conversions,
address them explicitly (extend the adapter or accept the drop) — never
hand-edit the generated manifest. Commit the regenerated manifest together
with the change that required it.

## Architectural invariants

These rules exist to keep the security model intact. A PR that violates
them will be rejected regardless of other merits:

1. **No remote code execution.** The manifest can only express the routing
   primitives the app already implements — the sealed `Strategy` type in
   `android/core` (`replace-origin`, `template`, `custom-scheme`,
   `passthrough`). There is deliberately no expression/eval variant; adding
   a new primitive is a coordinated change across `core`, the validator,
   the schema, the converter, and an app release.
2. **Refreshes move instances, nothing else.** A live "Update Data Now"
   refresh may only merge HTTPS clearnet instance origins into the active
   manifest. Routes, hosts, and frontend structure are frozen at build
   time and change only via a new bundled manifest in a release.
3. **Fail-open routing, fail-safe updates.** Routing failures open the
   original URL; malformed or unsupported-scheme input is dropped. A failed
   manifest update never touches the last known good manifest.
4. **No new network endpoints without review.** The app currently talks to
   exactly two upstream sources (`libredirect/instances` `data.json` for
   instance refresh, GitHub Releases API for the update check) plus whatever
   the user browses to in the built-in browser. Anything else requires an
   explicit justification in the PR.
5. **No telemetry.** No analytics, tracking, or crash-reporting SDKs.

## Code style

- Kotlin: enforced by ktlint and detekt (`./gradlew ktlintCheck detekt`).
  Detekt runs with `buildUponDefaultConfig`; do not suppress rules without a
  written justification next to the suppression.
- TypeScript: enforced by Biome (`bun run lint`, `bun run format:check`).
- Match the surrounding code's comment density and idiom. Document *why*
  comments explain a non-obvious constraint, not *what* the code does.

## Tests

- Routing-engine behavior belongs in `android/core/src/test/` — these are
  fast JVM tests and the right place for route matching, template
  rendering, instance selection, and manifest validation coverage.
- App-level behavior (settings, browser data, parsers, mergers) belongs in
  `android/app/src/test/`.
- Converter changes need fixtures: add real input/output pairs to
  `fixtures/` and cover the case in `tools/` tests.
- New routing behavior must ship with tests before the PR is reviewed.

## Pull requests

- Keep changes focused; avoid mixing unrelated refactors with feature work.
- Run the relevant lint/format/test commands above before opening a PR —
  CI (`android-ci.yml` for `android/**`, `routes-ci.yml` for `tools/**`)
  enforces the same checks.
- Explain the *why* in commit messages and PR descriptions, not just the
  *what*.
- PRs that touch `android/core`, the manifest schema, or the converter
  should call out any security-relevant effect explicitly.

## Releases

Maintainers release as follows (there is no manual tagging):

1. Bump `versionName` and `versionCode` in `android/app/build.gradle.kts`.
2. Update the matching fallback constants in `MainUiState` and
   `MainViewModel` (`FALLBACK_VERSION_*`) so the version display stays
   correct even if package info cannot be read.
3. Commit, then dispatch the `Android Release` GitHub Actions workflow
   (`workflow_dispatch`). It reads the version from the build file, builds
   and signs the APK, smoke-tests the bundled manifest inside the release
   APK, and publishes a GitHub Release tagged `v{versionName}`.
4. New services and frontends reach users through this flow: regenerate the
   bundled manifest (see above) as part of the release preparation.

## Reporting security issues

Do not open a public issue for security vulnerabilities. See
[SECURITY.md](SECURITY.md).
