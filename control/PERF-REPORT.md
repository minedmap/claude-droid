# control-mcp performance report

Device: Redroid (Android 13, sdk 33, 720x1280, 2 cores) over `adb connect 127.0.0.1:5555`, Settings app on screen, node 22.23, ImageMagick `convert` present.
Method: `control/bench.mjs <server> 5` (median of 5 end-to-end JSON-RPC calls). Before = `HEAD` (commit 1b39596) with its defaults, after = this branch. `CONTROL_TIMING=1` gives the per-call breakdown.

## Profile (where the time goes)

| piece | measured |
|---|---|
| `adb -s X shell true` (process spawn + round trip) | **17 ms** |
| `adb exec-out uiautomator dump /dev/tty` (also `--compressed`) | **~2060 ms** (JVM start ~1 s + dump ~1 s; same with `--compressed`) |
| `adb exec-out screencap -p` | 240 ms (raw PNG 192 KB) |
| ImageMagick resize 960 + JPEG q60 | 60-80 ms, 192 KB -> 34 KB |
| `screencap \| md5sum` on device (no transfer) | 145 ms |
| wake check / rotation / focus `dumpsys ... \| grep` | 30-55 ms each |

Consequence: **the per-call adb process overhead is negligible (17 ms), so a persistent `adb shell` session was NOT implemented** (it cannot touch the 2 s that matter: uiautomator starts a fresh JVM per dump). One `ui_tree` = one dump = ~2.1 s, and every observed action pays one. This is a Redroid-on-2-cores number; real phones are typically faster but the ratio is likely similar.

## Before / after (median ms, Redroid)

| scenario | before | after | note |
|---|---|---|---|
| screenshot | 203 ms, 95 KB PNG (was not resized: 1280 side == default cap) | 250 ms, **39 KB JPEG** 540x960 | +47 ms for resize; ~60% smaller payload / fewer vision tokens |
| ui_tree | 2106 | 2073 | unchanged (dump-bound) |
| scroll + observe auto | 3274 | 3443 | no gain: the scroll keeps the screen moving, adaptive settle runs to its 700 ms cap and pays ~3 frame hashes (~0.4 s) on top |
| key + observe ui | 2801 | 2568 | **-8%** (settle 700 ms -> ~0.5 s adaptive on a static screen) |
| wait_for (element already there) | 2110 | 2063 | unchanged |
| batch (key, shell, wait_for) + observe ui | 4929 | 4694 | -5% |
| wait_for gone: latency after the change | 2.4 s | 2.3-2.5 s | **no latency gain** (dump-bound); it now issues far fewer dumps while waiting (cheap frame hash polls, dump only when the frame changed) = less device CPU, but the same wake-up time |

Honest summary: on this device the native-UI path is **~0-8% faster**; it is bound by uiautomator, which cannot be sped up from the host. Adaptive settle helps only when the screen is idle after the action (and costs up to ~0.4 s extra when it is animating forever). The real speedup is the DOM route:

| web (CDP) | measured |
|---|---|
| `web_snapshot` (cold: socket scan + forward + connect) | 115-140 ms |
| `web_snapshot` / `web_eval` / `web_type` (warm, socket cached 5 s, forward reused) | **10-25 ms** |
| `web_click` incl. 3 mouse events + follow-up snapshot | 70-450 ms (incl. 300 ms settle) |
| native `ui_tree` for the same screen | ~2070 ms, and it contains none of the page's DOM |

i.e. ~20-100x faster than a uiautomator dump for WebView/Chrome content, with real DOM refs.

## What changed
- `CONTROL_TIMING=1`: per call `[timing] tool total=..ms | adb:uiautomator-dump=.. adb:screencap=.. resize=.. parse=.. settle=.. cdp:*=..` on stderr (AsyncLocalStorage, so concurrent calls do not mix; a batch is one record).
- Screenshot: default max side 960 (was 1280), `-strip -quality 60` (was 80) -> JPEG; scale mapping unchanged (`scale = deviceMax / 960`, tests cover coordinates).
- Settle: `settleMs` is now a maximum; polls an on-device `screencap | md5sum` and stops after two identical frames; falls back to the fixed wait when frames are unreadable. `CONTROL_SETTLE_ADAPTIVE=0` restores the fixed wait.
- `wait_for`: first look dumps immediately; later polls use the frame hash and only re-dump when the frame changed; poll interval 100 ms (was 400).
- Wake-check TTL 3 s -> 5 s. `batch`: description documents wait_for steps + single final observe (behaviour already did this; no extra per-step overhead found in the profile).
- New tools `web_snapshot`, `web_click`, `web_type`, `web_eval` (CDP, zero dependencies; built-in `WebSocket` if available, else a minimal RFC 6455 client; `CONTROL_WS=minimal` forces it).

## Live verification (Redroid)
- `org.chromium.webview_shell` DOES expose `@webview_devtools_remote_<pid>` while a page is open (no extra setup needed on this image).
- Verified live with both WebSocket clients (built-in and minimal): snapshot, click by ref and by text, type with Korean text (`안녕 world`) with and without `clear`, eval, page exception -> error, stale/unknown ref errors, forward reuse across runs.
- Not verified live: Chrome itself (`chrome_devtools_remote`) and iframes (elements inside iframes are not listed; use `web_eval`), pages in several tabs (`target` selection is unit-tested only), Android versions other than 13.
- Unit tests: `node --test test/control-mcp.test.mjs` -> 35 pass (27 original + 8 new; 4 original tests adjusted: the intentional 960 px default, adaptive-settle probes, and the 4 new tools in the tool list/count).
