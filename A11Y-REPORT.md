# A11y fast UI tree (branch `droid-a11y`) — status report

## What was added
- `app/.../a11y/A11yXml.kt`: platform-free serializer producing `uiautomator dump`-shaped XML (`<hierarchy><node ... bounds="[l,t][r,b]">`), so `control-mcp.mjs` parses it with its existing parser; output is identical by construction.
- `app/.../a11y/A11yService.kt`: optional `AccessibilityService`; read-only (`canRetrieveWindowContent`, no gestures). Serves `GET /a11y/tree` and `/a11y/health` on `127.0.0.1:8767`; requires `X-Token` == the existing bridge token (`Prefs.token`, `HttpParse.tokenOk`); empty token => always 401. Active window only, max 5000 nodes / depth 60, visible-to-user nodes only.
- Manifest service entry, `res/xml/a11y_service_config.xml`, description string.
- `control/control-mcp.mjs`: `uiXml()` tries the service first. `CONTROL_A11Y=0` off, `=1` on, unset => on only when `CONTROL_A11Y_TOKEN` is set. Tries 127.0.0.1 directly (Termux), else one `adb forward tcp:PORT tcp:8767` and retry. Any failure/garbage => `uiautomator dump` fallback; failures back off 30 s.
- README section on enabling it (Settings > Accessibility > Claude Droid UI tree).

## Verified
- `node --test control/test/control-mcp.test.mjs`: 31/31 pass, incl. 4 new tests with a fake endpoint (off by default, identical output vs. uiautomator path, token/port/forward-once, fallback + backoff, no token => no call).

## NOT verified
- **Android code is unbuilt and untested.** No Android SDK platform / Gradle cache on this server (only build-tools + platform-tools), so `./gradlew assembleDebug` was not attempted. Kotlin may have compile errors. `A11yXmlTest.kt` was written but not run.
- Not run on a device: real tree fidelity vs. uiautomator (e.g. `visibleToUser` filtering may drop nodes uiautomator keeps; `rootInActiveWindow` can be null on some screens/secure windows -> 503 -> fallback), real speedup (no measurement), port 8767 binding, token flow, the real `adb forward` path.
- The endpoint returns XML with a JSON content-type (reuses `HttpParse.response`); the JS client ignores the header.
- Token must be supplied to the MCP server via env (`CONTROL_A11Y_TOKEN`); the app does not expose it to adb.
- The service only binds the port while enabled; Android may kill/rebind it, in which case the fallback covers it.
