# Security Policy

## Supported versions

Only the latest release receives fixes.

## Reporting a vulnerability

Please **do not open a public issue** for security problems.
Report them privately via [GitHub Security Advisories](https://github.com/minedmap/claude-droid/security/advisories/new).
Include steps to reproduce, the affected version, and the impact.

## Threat model

- The bridge listens on `127.0.0.1` only and requires the token in `~/.claude-droid-token`. Anyone with this token can run arbitrary commands through Claude Code.
- `--dangerously-skip-permissions` is enabled by default. Claude can run commands without asking. With wireless debugging connected, that includes adb shell access to the device.
- `allow-external-apps=true` in Termux allows any app holding the `RUN_COMMAND` permission to run Termux commands.
- `/file` and `/stat` are restricted to HOME, the Ubuntu rootfs, shared storage, and tmp. They return 403 for common credential files.
