# LibRedirect Mobile

LibRedirect Mobile is an Android app that routes links from large online services to privacy-friendly frontends. It can redirect links from services such as YouTube, Reddit, and X/Twitter to compatible Invidious, Redlib, or alternative instances before opening them in your preferred browser.

The app keeps routing rules in a declarative manifest. This design lets the service catalog evolve without adding service-specific routing code to the Android client.

## Features

- **Privacy-focused routing**: Redirect eligible links without accounts, analytics, or a backend.
- **Offline-first behavior**: Route links locally without making a network request during routing.
- **Live instance updates**: Refresh frontend instance lists directly from the upstream [libredirect/instances](https://github.com/libredirect/instances) repository.
- **Safe fallback**: Open the original URL when routing fails for an eligible HTTP(S) link.
- **Custom services**: Add local services with a source hostname and an HTTPS instance or supported URL template.
- **Instance selection**: Choose from the instances available for each supported frontend.
- **Explicit original-site option**: Keep services available even when no public instance is currently listed.

## How routing works

The Android client does not contain a separate code path for each supported service. It reads a manifest that describes source hosts, frontends, instances, and routing strategies.

The manifest supports a fixed set of routing primitives:

- `replace-origin`
- `template`
- `custom-scheme`
- `passthrough`

The app never executes JavaScript or arbitrary expressions from the manifest. The bundled catalog is generated from the upstream [LibRedirect configuration and instance data](https://github.com/libredirect), then converted and validated by the tooling in `tools/`.

## Project structure

| Directory     | Purpose                                                          |
| ------------- | ---------------------------------------------------------------- |
| `android/`  | Kotlin and Jetpack Compose Android application                   |
| `tools/`    | Bun and TypeScript tools for generating and validating manifests |
| `schema/`   | JSON Schema for routing manifests                                |
| `fixtures/` | Real URL regression fixtures for the converter                   |
| `dist/`     | Generated manifest artifacts produced by automation              |

## Requirements

For Android development, install:

- JDK 17
- Android SDK with the compile SDK version pinned in `android/gradle/libs.versions.toml`

For manifest tooling, install [Bun](https://bun.sh/) 1.1 or later.

## Build and test the Android app

Run these commands from the `android/` directory:

```bash
./gradlew ktlintCheck detekt
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

Generate the bundled routing data with the script defined in `tools/package.json`:

```bash
bun run generate
```

## Updating routing data

The bundled manifest ships routes, hosts, and frontend structure. The "Routing data → Update now" action in the app refreshes only the frontend instance lists: it fetches `data.json` directly from the upstream [libredirect/instances](https://github.com/libredirect/instances) repository, merges the HTTPS clearnet instances into the active manifest, and activates it after validation. New services and frontends arrive with app releases.

## Security model

Remote updates change nothing except instance origin lists. Fetched data is parsed strictly (HTTPS-only, origin-only URLs), merged, re-validated, and self-tested before activation. If an update fails any step, the app keeps the last known good manifest.

Routing accepts only safe URL schemes and rejects malformed or private/local redirect targets. For more detail, see the [security policy](SECURITY.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Keep changes focused, run the relevant checks, and preserve the manifest's fixed routing primitive allowlist.

Do not report security vulnerabilities in a public issue. Follow the private reporting process in [SECURITY.md](SECURITY.md).

## Project status

The repository currently includes the Android routing core, Compose settings UI, manifest repository with live instance updates, generated upstream service catalog, local custom-service storage, and Bun tooling for upstream conversion.
