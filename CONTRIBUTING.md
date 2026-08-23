# Contributing to LibRedirect Mobile

Thanks for your interest in contributing.

## Project language policy

All code, comments, commit messages, UI strings, and documentation in this
repository must be written in English, with no exceptions.

## Repository structure

- `android/` — the Kotlin/Compose Android app.
- `tools/` — the Bun/TypeScript tooling that generates and validates the
  routing manifest from upstream LibRedirect data.

Each has its own toolchain; changes to one do not require touching the
other.

## Development setup

### Android (`android/`)

Requirements: JDK 17, Android SDK (compileSdk as pinned in
`android/gradle/libs.versions.toml`).

```bash
cd android
./gradlew ktlintCheck detekt
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### Tooling (`tools/`)

Requirements: [Bun](https://bun.sh).

```bash
cd tools
bun install
bun run lint
bun run format:check
bun test
```

## Pull requests

- Keep changes focused; avoid mixing unrelated refactors with feature work.
- Run the relevant lint/format/test commands above before opening a PR — CI
  enforces the same checks.
- New routing behavior must not introduce remote code execution: the
  manifest can only express the routing primitives the app already
  implements (see the project plan for the current allowlist).
- Explain the *why* in commit messages and PR descriptions, not just the
  *what*.

## Reporting security issues

Do not open a public issue for security vulnerabilities. See
[SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions are licensed under the
project's GPL-3.0-or-later license (see [LICENSE](LICENSE)).
