# LibRedirect Mobile

LibRedirect Mobile is an Android app that routes links from large online services to privacy-friendly frontends. It can redirect links from services such as YouTube, Reddit, and X/Twitter to compatible Invidious, Redlib, or alternative instances, and open them either in your preferred external browser or in the app's built-in multi-tab browser.

Routing rules live in a declarative manifest. This design lets the service catalog evolve without adding service-specific routing code to the Android client.

## Features

### Routing

- **Privacy-focused routing**: Redirect eligible links without accounts, analytics, or a backend.
- **Offline-first routing**: Route links locally — routing itself never makes a network request.
- **System-wide link handling**: Registered as an http/https link handler and as a share target, so links from other apps can be routed either by opening them with LibRedirect Mobile or by sharing text containing a URL to it.
- **Live instance updates**: Refresh frontend instance lists directly from the upstream [libredirect/instances](https://github.com/libredirect/instances) repository; routes and hosts update with app releases.
- **Per-service control**: Enable or disable individual services, choose the frontend for each service, and pin a specific instance or define a custom one (Automatic / Pinned / Custom).
- **Routing exceptions**: Add host or host/path rules that are never redirected.
- **Custom services**: Add local services with source hostnames and an HTTPS instance or an output template supporting `{instance}`, `{path}`, `{query:name}`, and `{fragment}`.
- **Safe fallback**: Open the original URL when routing fails for an eligible link (fail-open); malformed or unsupported-scheme input is never opened at all.
- **Redirect-loop prevention**: When handing a link to an external browser, the app can never be chosen as its own target.

### Built-in browser

- **Multi-tab browsing**: A tabbed WebView browser ("LibRedirect Browser") that receives routed links directly, with a tab switcher, navigation controls, and a combined URL/search address bar.
- **Search engines**: Choose DuckDuckGo, SearXNG, Brave Search, Google, Startpage, or add a custom engine via a `%s` URL template.
- **Bookmarks and history**: Local bookmark list with standard HTML and JSON import/export; browsing history capped at 200 entries, individually removable and clearable.
- **Downloads**: Handed to the system Download Manager with progress notifications (on Android < 10, files go to the app's private downloads directory instead).
- **Page appearance**: Light/dark page rendering that follows the system or is forced, plus an option to auto-darken sites that ship no dark theme of their own.
- **Browsing tools**: Find in page, desktop-site toggle, share page/link, open in external browser, clear cookies and cache, HTTPS security indicator, and a banner showing the original → redirected URL mapping.

### App

- **Update check**: A best-effort check of the project's GitHub Releases (no push notifications; purely informational, fail-safe, and off by default when offline).
- **In-app language selection**: English and Turkish, independent of the system language.

## How routing works

The Android client does not contain a separate code path for each supported service. It reads a manifest that describes source hosts, frontends, instances, and routing strategies.

The manifest supports a fixed set of routing primitives, enforced by a sealed type in the core routing module:

- `replace-origin`
- `template`
- `custom-scheme`
- `passthrough`

There is no expression or eval variant: adding a new primitive requires an app release, by design. The app never executes JavaScript or arbitrary expressions from the manifest. The bundled catalog is generated from the upstream [LibRedirect configuration and instance data](https://github.com/libredirect), then converted and validated by the tooling in `tools/`.

When a link cannot be routed (service disabled, no instance available, or an exception matches), the original URL opens normally. Routing itself is fail-open; only input that was never a valid eligible URL (bad scheme, unparseable) is dropped.

## Project structure

| Path | Purpose |
| --- | --- |
| `android/app/` | Kotlin + Jetpack Compose application (UI, redirect handling, browser, settings, updates) |
| `android/core/` | Pure-Kotlin routing engine: manifest model, validator, router, template renderer, URL utilities — no Android dependencies |
| `tools/` | Bun and TypeScript tools that generate and validate the routing manifest from upstream data |
| `schema/` | JSON Schema for routing manifests |
| `fixtures/` | Real URL regression fixtures for the converter |
| `dist/` | Generated manifest artifacts (gitignored; produced by automation, not committed by hand) |

## Requirements

For Android development:

- JDK 17
- Android SDK with the compileSdk version pinned in `android/gradle/libs.versions.toml`

For manifest tooling, [Bun](https://bun.sh/) 1.1 or later.

## Build and test the Android app

Run these commands from the `android/` directory:

```bash
./gradlew ktlintCheck detekt
./gradlew :core:test
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

To install the debug build on a connected device or emulator:

```bash
./gradlew installDebug
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

## Run the manifest tooling

Install dependencies from the `tools/` directory:

```bash
cd tools
bun install
```

Run checks and tests:

```bash
bun run lint
bun run format:check
bun run typecheck
bun test
```

Generate the routing manifest from live upstream data:

```bash
bun run generate [output-path]
```

Without an argument this writes `dist/routes.json`. The manifest bundled into the app is committed at `android/app/src/main/assets/routes.json`; pass that path to regenerate it.

## Updating routing data

The bundled manifest ships routes, hosts, and frontend structure. The "Routing Database → Update Data Now" action in Settings refreshes only the frontend instance lists: it fetches `data.json` directly from the upstream [libredirect/instances](https://github.com/libredirect/instances) repository, merges the HTTPS clearnet instances into the active manifest, and activates it after validation and a self-test. New services and frontends arrive with app releases.

## Releases and updates

Releases are built by the `Android Release` GitHub Actions workflow, which reads the version from `android/app/build.gradle.kts`, builds a signed APK, smoke-tests the bundled manifest inside it, and publishes the APK as a GitHub Release tagged `v{versionName}`.

The app checks GitHub's "latest release" endpoint and, if a newer version exists, shows a banner on the Home and Settings screens linking to the release page. Installation is manual (download the APK from GitHub Releases). See [CONTRIBUTING.md](CONTRIBUTING.md) for the release checklist.

## Privacy and permissions

- The app requests a single permission: `INTERNET`.
- No telemetry, analytics, or crash reporting.
- Routing happens locally on device; the only outbound requests are the optional instance-list refresh and the optional update check, both direct HTTPS fetches to their upstream sources.
- Bookmarks, history, settings, and the active manifest are stored locally in app-private storage. Backup is disabled (`allowBackup="false"`).

## Security model

Remote updates change nothing except instance origin lists. Fetched data is parsed strictly (HTTPS-only, origin-only URLs), merged, re-validated, and self-tested before activation. If an update fails any step, the app keeps the last known good manifest.

Routing accepts only safe URL schemes and rejects malformed or private/local redirect targets. The built-in browser stores all browsing data locally and never syncs it. For the full policy and threat model, see [SECURITY.md](SECURITY.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Keep changes focused, run the relevant checks, and preserve the manifest's fixed routing primitive allowlist.

Do not report security vulnerabilities in a public issue. Follow the private reporting process in [SECURITY.md](SECURITY.md).

## License

This project is released into the public domain under the [Unlicense](LICENSE).
