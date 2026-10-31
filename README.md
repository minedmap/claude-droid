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
