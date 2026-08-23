# LibRedirect Mobile

LibRedirect Mobile is an Android URL routing app. It intercepts links to
services such as YouTube, Reddit, or X/Twitter and redirects them to
privacy-friendly frontends (Invidious, Redlib, Nitter-style alternatives, and
similar) before opening them in the browser you choose.

The app does not know about YouTube, Reddit, or any specific service. It
reads a declarative routing manifest and applies a small set of routing
primitives (`replace-origin`, `template`, `custom-scheme`, `passthrough`).
New services are added by publishing a new manifest, not by shipping a new
APK.

The bundled catalog is generated from the upstream LibRedirect service and
instance data. It includes hostname patterns for wildcard services and keeps
services without a currently published public instance available as an
explicit `Original site` choice.

Users can also add custom services from the Android app. A custom service
stores its source hostnames and an HTTPS instance (or a supported URL
template) locally; it never modifies or bypasses the signed upstream
manifest.

Routing data is derived from the [LibRedirect](https://github.com/libredirect)
project's browser extension config and instance list, converted and
validated by the tooling in `tools/`.

## Design principles

- **Declarative routing.** No per-service `if (url.contains(...))` branches;
  routes are data, not code.
- **No remote code execution.** The manifest can only express a fixed set of
  routing primitives implemented in the app itself. It never contains
  JavaScript, Kotlin, or arbitrary expressions.
- **Fail-open for eligible links.** If routing fails for a valid HTTP(S) link,
  the original URL opens in the selected browser. Unsafe or malformed schemes
  are rejected rather than being handed to another app.
- **Offline-first.** Routing never makes a network request. A manifest is
  bundled in the APK; remote updates are fetched, validated, and swapped in
  separately.
- **Zero telemetry.** No accounts, no backend, no analytics. Routing happens
  entirely on-device.

See the project plan artifact for the full architecture, manifest schema,
signing model, and phased roadmap.

## Repository layout

```text
android/    Kotlin/Compose Android app (the routing client)
tools/      Bun/TypeScript tooling that generates and validates the routing manifest
schema/     JSON Schema for the routing manifest (added in Phase 1)
fixtures/   Real-URL regression fixtures for the converter (added in Phase 3)
dist/       Generated manifest artifacts (CI output, not committed by hand)
```

## Status

The repository contains the Android routing core, signed manifest repository,
Compose settings UI, generated upstream service catalog, local custom-service
storage, and Bun tooling for upstream conversion and signing.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE) and
[THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES).
