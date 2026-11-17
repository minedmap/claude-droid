# A11y fast path — measured on redroid (Android 13 arm64, 540x960)

Setup: CI-built debug APK (GitHub Actions, green on first run after merge), `A11yService` enabled via
`settings put secure enabled_accessibility_services`, endpoint `127.0.0.1:8767` reached through `adb forward`
(`CONTROL_A11Y_PORT`), token from the app prefs (`CONTROL_A11Y_TOKEN`, not recorded here).

| Measurement (median, 7 runs, `bench.mjs`) | uiautomator dump | CONTROL_A11Y=1 |
|---|---|---|
| `ui_tree` (near-empty screen) | 2055 ms | **10 ms** |
| `ui_tree` (Settings, 8 actionable, `tap_element` script) | 2174 ms | **113 ms** (incl. `dumpsys`/`wm` size probes) |
| `tap_element` incl. auto-observe | 2982 ms | 1319 ms |

Raw endpoint: HTTP 200 in 33 ms, 39-node XML parses; request without X-Token -> 401.

Verified live
- `@eN` refs from the fast-path tree resolve and tap (`@e1` "Search settings" opened the search screen).
- Fallback: with the service disabled, the same call transparently used `uiautomator dump` and still worked (2173 ms).

Not verified
- Behaviour across app restarts/process kill of the service, and long-term stability.
- Keyboard-focused / WebView-heavy screens (WebView content exposure through a11y not compared with uiautomator).
- Remaining `tap_element` time is dominated by settle + screencap (observe), not the tree.
