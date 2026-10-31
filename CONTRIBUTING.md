# Contributing

Issues and pull requests are welcome.

1. Fork the repository and create a branch.
2. Bridge changes: add or update tests in `bridge/test/` and run `cd bridge && node --test`.
3. App changes: keep pure logic (parsers, helpers) unit-testable under `app/src/test/`. CI runs `./gradlew testDebugUnitTest assembleDebug`.
4. Use clear commit messages (e.g. `fix(bridge): ...`, `feat(app): ...`).
5. Do not add GPL-licensed code. The project is Apache-2.0. If you adapt Apache or MIT code, record it in `NOTICE.md`.

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
