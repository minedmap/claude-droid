# Claude Droid

[![build](https://github.com/minedmap/claude-droid/actions/workflows/build.yml/badge.svg)](https://github.com/minedmap/claude-droid/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/minedmap/claude-droid)](https://github.com/minedmap/claude-droid/releases/latest)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

**English** | [한국어](README.ko.md)

A native Android chat GUI for **Claude Code**, running entirely on your phone.
Claude Code runs inside Termux (proot Ubuntu). A small local bridge streams its output to the app.
With wireless debugging (adb) connected, Claude can operate the phone itself.

## Features

- **Chat sessions**: separate sessions, each with its own working directory. Sessions resume via `--resume`. Rename or delete with a long press.
- **Model and effort picker** per session (`opus` / `sonnet` / `haiku` / custom ID, effort `low`…`max`).
- **Background runs**: a foreground service keeps a run alive when you leave the chat. If the connection drops, the app reattaches to the run (`/chat/<reqId>?from=N`). Runs stop only when you press **Stop**.
- **Live streaming** of text, with compact tool-call summaries (Bash command, file path, pattern) and error and stop markers.
- **Files**
  - Upload files or clipboard images.
  - Claude-generated files show up as cards with previews: images, PDF first page, Markdown, JSON, and text. Images returned in tool results show inline.
  - Download a file to `Download/ClaudeDroid` or share it.
- **Wireless debugging wizard**: pairing via a notification reply, mDNS port discovery, and Termux `RUN_COMMAND` integration. Optional **Shizuku** shell.
- **Settings**: masked token field and a connection test.

## Architecture

```
[Claude Droid app] --HTTP/SSE + X-Token--> [bridge/server.mjs  (Termux, 127.0.0.1:8765)]
                                               └─ spawn → claude -p --output-format stream-json [--resume <id>]
                                                          (inside proot-distro Ubuntu, prompt via stdin)
                                                          └─ adb (wireless debugging, localhost) → phone
[app] --RUN_COMMAND--> Termux : bridge auto-start, adb pair/connect
[app] --Shizuku (optional)--> shell (uid 2000) commands
```

There is no native Claude Code binary for Android (2.1.113+), so the official Linux build runs in a proot Ubuntu inside Termux.

## First-time setup on a new phone (checklist)

The full order for a fresh device. Run the commands from the Installation section below at the matching steps.

1. **Install F-Droid** from https://f-droid.org. Allow "install unknown apps" for your browser when asked.
   - The Play Store Termux is outdated and does not work. Use the F-Droid build.
2. **Install Termux and Termux:API** (F-Droid). Open Termux once and run `pkg upgrade` (press Enter at prompts).
3. **Ubuntu + Claude Code**: Installation step 2. Log in to Claude, then `/exit`.
4. **Bridge**: Installation step 3. Copy the TOKEN printed at the end (`cat ~/.claude-droid-token` shows it again).
5. **Fully exit and reopen Termux** (required): Termux notification → **Exit**, then open Termux again. On a first `termux-services` install the bridge service only starts after this. Check with `bash ~/claude-droid/start.sh`.
6. **Enable developer options**: Settings → About phone → Software information → tap **Build number** 7 times (location varies by vendor).
7. **Enable wireless debugging**: Settings → Developer options → **Wireless debugging** ON (needs Wi-Fi, Android 11+). The app's wizard handles pairing.
8. **Install the app** (`app-debug.apk` from [Releases](https://github.com/minedmap/claude-droid/releases/latest)).
   - Allow **notifications** on first launch (needed for background runs and pairing-code entry).
   - Settings → Apps → Claude Droid and Termux → Battery → **Unrestricted / don't optimize**, so long runs are not killed.
9. **Paste the token in the app → connection wizard**: allow RUN_COMMAND, then pair wireless debugging.
10. *(Optional)* Screen-control MCP: `bash ~/bridge/mcp-setup.sh`.
11. *(Optional)* USB device control: `bash ~/claude-droid/usb-setup.sh`.
    - Some phones (some Samsung, Xiaomi, etc.) need an **OTG** toggle turned on in Settings.
    - Plug the device in with an OTG adapter/cable and allow Claude Droid in the permission popup (you can re-request it under app Settings → USB devices).

### Commands explained

Run everything in **Termux** (not inside Ubuntu). Long-press to paste.

**1) Update Termux packages**
```sh
pkg upgrade -y
```
- Updates the package lists and installed packages. Skipping this causes library errors such as `CANNOT LINK EXECUTABLE` when running `node`.
- If asked whether to overwrite a config file, press Enter (default).

**2) Ubuntu + Claude Code**
```sh
pkg install -y proot-distro && proot-distro install ubuntu && proot-distro login ubuntu -- bash -c "apt update && apt install -y curl adb && curl -fsSL https://claude.ai/install.sh | bash && /root/.local/bin/claude"
```
- `pkg install -y proot-distro`: a tool that runs Linux distributions without root.
- `proot-distro install ubuntu`: downloads the Ubuntu filesystem (a few hundred MB). Claude Code has no Android binary, so it runs inside Ubuntu.
- `proot-distro login ubuntu -- bash -c "..."`: runs the quoted commands inside Ubuntu.
  - `apt install -y curl adb`: download tool and adb (phone control).
  - `curl -fsSL https://claude.ai/install.sh | bash`: the official Claude Code installer, installs to `/root/.local/bin/claude`.
  - `/root/.local/bin/claude`: first run and login. Open the link, paste the code back, then `/exit`.

**3) Bridge**
```sh
cd ~ && curl -LO https://github.com/minedmap/claude-droid/releases/latest/download/bridge.zip && unzip -o bridge.zip && bash bridge/install.sh
```
- `curl -LO ...bridge.zip` downloads the latest bridge into `~`; `unzip -o` extracts to `~/bridge/`, overwriting old files.
- `bash bridge/install.sh`:
  - installs `nodejs android-tools termux-api termux-services`
  - copies `server.mjs`, `start.sh`, `usb-mcp.mjs`, `usb-setup.sh` into `~/claude-droid/`
  - creates the token `~/.claude-droid-token` (kept if it exists)
  - sets `allow-external-apps=true` in `~/.termux/termux.properties` (lets the app run Termux commands via RUN_COMMAND)
  - creates `~/claude-droid/claude-proot`, the wrapper that calls Claude inside Ubuntu
  - registers and starts the `claude-droid` service, runs `termux-wake-lock`
  - prints `TOKEN: ...` — paste this into the app. **It is not your Claude login.**
- Re-run the same command to update (the token is kept).

**4) Check / start the service**
```sh
bash ~/claude-droid/start.sh
```
- Prints `bridge ok` when the bridge answers. Otherwise it starts the service (`sv up`), then falls back to running it directly.
- On `bridge FAILED`, read `cat ~/claude-droid/bridge.log`.

```sh
sv status claude-droid
tail -20 $PREFIX/var/log/sv/claude-droid/current
```
- `run (pid ...)` is healthy. If it keeps going `down`, check the service log with the second command.
- If `sv` says `unable to change to service directory`, fully exit Termux (notification → Exit) and reopen it.

**5) Show the token again**
```sh
cat ~/.claude-droid-token
```

**6) (Optional) Screen-control MCP**
```sh
bash ~/bridge/mcp-setup.sh
```
- Installs Node 22 and [claude-in-mobile](https://github.com/AlexGladkov/claude-in-mobile) inside Ubuntu and registers it as the `mobile` tool (screenshot, tap, swipe, UI tree).
- Copies Termux's adb key into Ubuntu, so no re-pairing. Success when `adb devices` lists the phone at the end.

**7) (Optional) USB device control**
```sh
bash ~/claude-droid/usb-setup.sh
```
- Runs each step inside Ubuntu and prints an `OK`/`FAIL` summary; a failed step does not stop the rest.
  - `node>=20`: Node for the MCP server
  - `usb MCP 등록`: registers the `usb` tool (device list, permission, transfers, serial, AVR flashing)
  - `CLAUDE.md include`: makes Claude read the USB usage notes automatically
  - `arduino-cli`, `arduino:avr core`: Arduino compiler
  - `pyserial+esptool`: Python serial and ESP32/ESP8266 flashing
  - `nmap+iproute2`: network inspection
- The app must be running for its USB service (127.0.0.1:8766) to answer.

**8) List Claude's registered tools**
```sh
proot-distro login ubuntu -- /root/.local/bin/claude mcp list
```
- `mobile` and `usb` should be listed as `Connected`.

## Installation

1. Install **Termux** and **Termux:API** from F-Droid, then run `pkg upgrade` in Termux.
2. Install Ubuntu and Claude Code, then log in to Claude Code and type `/exit`:
   ```sh
   pkg install -y proot-distro && proot-distro install ubuntu && proot-distro login ubuntu -- bash -c "apt update && apt install -y curl adb && curl -fsSL https://claude.ai/install.sh | bash && /root/.local/bin/claude"
   ```
3. Install the bridge (latest `bridge.zip` from [Releases](https://github.com/minedmap/claude-droid/releases)):
   ```sh
   cd ~ && curl -LO https://github.com/minedmap/claude-droid/releases/latest/download/bridge.zip && unzip -o bridge.zip && bash bridge/install.sh
   ```
   Copy the `TOKEN` printed at the end. This is the bridge token, **not** your Claude login. You can show it again with `cat ~/.claude-droid-token`.
   Re-running `install.sh` updates and restarts the bridge.
   *(Optional)* To register the [claude-in-mobile](https://github.com/AlexGladkov/claude-in-mobile) screen-control MCP, run `bash bridge/mcp-setup.sh`.
4. Install the APK from Releases. Paste the token in the app, then follow the connection wizard:
   1. Allow the `RUN_COMMAND` permission.
   2. Pair wireless debugging, either through the notification reply or by entering the port and code manually.

**Custom port:** put `BRIDGE_PORT=9000` in `~/claude-droid/env`. The service script and `start.sh` both read it, and it survives reinstalls. Then change the bridge URL in the app settings.

## USB host control (v0.6)

Claude can inspect and drive any USB device plugged into the phone via OTG. **No root needed**: Android's `UsbManager` lets an app open a device after a one-tap permission, and the app exposes it to Claude through a loopback API.

```
[Claude (proot)] --stdio MCP--> bridge/usb-mcp.mjs --HTTP + X-Token--> [app UsbBridgeService 127.0.0.1:8766]
[esptool / pyserial / avrdude] --rfc2217:// or socket:// 127.0.0.1:<port>--> [app serial TCP bridge]
```

- 12 MCP tools: `usb_list`, `usb_permission`, `usb_descriptors`, `usb_control`, `usb_transfer`, `serial_open/write/read/lines/close`, `serial_tcp`, `avr_flash`.
- Serial chips via [usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android): CDC/ACM, FTDI, CP210x, CH34x, PL2303.
- `avr_flash`: built-in STK500v1/v2 uploader (Uno, Nano, old-bootloader Nano, Mega2560). Typical loop: `arduino-cli compile` -> `avr_flash` -> `serial_read`. Leonardo/Micro (AVR109) is not supported.
- `serial_tcp` exposes a port as RFC 2217 (subset: baud, data/parity/stop, DTR/RTS, purge) or raw TCP so esptool/pyserial work unchanged.
- Setup: run `bash ~/claude-droid/usb-setup.sh` in Termux after `install.sh` (registers the `usb` MCP in Ubuntu, installs arduino-cli, pyserial, esptool, nmap). The app starts the USB service on launch; **Settings -> USB 장치** shows status and a permission button.
- Same token as the chat bridge; the API binds 127.0.0.1 only and rejects requests without `X-Token`.

Limits: only devices Android lets an app claim (some HID/storage devices are held by the system and fail to claim); devices that need a kernel driver beyond raw USB/serial are out of reach; OTG power and hubs are your responsibility; each device needs the permission tap on the phone.

## Bridge API

All requests need the `X-Token` header. The bridge listens on `127.0.0.1` only.

| Method | Path | Description |
|---|---|---|
| GET | `/health` | `{"ok":true}` |
| GET | `/adb` | `{connected, devices:[{serial,state}]}` |
| POST | `/chat` | Body `{prompt, sessionId, cwd, reqId, skipPerms, model, effort}` → SSE. Each event has the form `id: N` + `data: <json>`. The last event is `{type:"done", code, stderr}`. |
| GET | `/chat/<reqId>?from=N` | Replays events from index `N`, then continues live. Returns 404 if the reqId is unknown. |
| GET | `/runs` | `{runs:[{reqId, startedAt, done, code, events}]}` |
| POST | `/cancel` | `{reqId}`. This is the only way to kill a run; a client disconnect does not. |
| POST | `/upload?cwd=&name=` | Raw body, max 50 MB. Saved to `<cwd>/.claude-droid/uploads/`. |
| GET | `/file?path=` / `/stat?path=` | Read or stat a file under HOME, the Ubuntu rootfs, `/sdcard`, or tmp. Credentials (`.credentials.json`, `.ssh/`, `*.pem`, `*.key`, `.env`, …) return 403. |

- Prompts go to `claude -p` via **stdin**, so prompts starting with `-` and prompts larger than 128 KB work.
- Event buffers are capped at about 20 MB per run and kept for 15 minutes after a run finishes.
- Every 4xx response has a JSON body `{error}`, for example `invalid model`.
- Guest paths such as `/root/...` and `/tmp/...` map to the proot rootfs. The bridge auto-detects `containers/ubuntu/rootfs` and `installed-rootfs/ubuntu`, or uses `UBUNTU_ROOTFS` if set.

## ⚠ Security

- **`--dangerously-skip-permissions` is on by default**, so Claude runs commands **without asking**. With wireless debugging connected, it can control the whole phone. You can turn this off in Settings, but runs then stop at permission prompts.
- proot Ubuntu runs as root, so the wrapper sets `IS_SANDBOX=1`.
- `install.sh` enables `allow-external-apps=true` in Termux. **Any app that is granted `RUN_COMMAND`** can then run Termux commands, so grant it only to apps you trust.
- The bridge binds to `127.0.0.1` only and needs a random token (`~/.claude-droid-token`).

See [SECURITY.md](SECURITY.md) for reporting vulnerabilities.

## Development

- Bridge tests: `cd bridge && node --test`
- App unit tests and APK: built by GitHub Actions (`.github/workflows/build.yml`). Run `./gradlew testDebugUnitTest assembleDebug` locally with the Android SDK.
- Requirements: JDK 17, Node 20+, minSdk 26 / targetSdk 34.

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE). Third-party code adapted from Shizuku (Apache-2.0) is listed in [NOTICE.md](NOTICE.md).

Claude Droid is an unofficial project. It is not affiliated with or endorsed by Anthropic. "Claude" and "Claude Code" are trademarks of Anthropic.
