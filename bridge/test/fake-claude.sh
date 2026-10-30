#!/usr/bin/env bash
SID="fake-sid-123"; prev=""
for a in "$@"; do [ "$prev" = "--resume" ] && SID="$a"; prev="$a"; done
PROMPT="$(cat)"
B64="$(printf '%s' "$PROMPT" | base64 -w0)"
echo "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"$SID\",\"tmp\":\"$TMPDIR\",\"args\":\"$*\",\"stdin_b64\":\"$B64\"}"
echo "{\"type\":\"assistant\",\"session_id\":\"$SID\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"
case "$PROMPT" in *SLOWCHILD*) sleep 30 & wait;; esac
case "$PROMPT" in *SLOWSHORT*) sleep 1;; esac
case "$PROMPT" in *ERRRESULT*) echo "{\"type\":\"result\",\"subtype\":\"error_during_execution\",\"is_error\":true,\"session_id\":\"$SID\"}"; echo "proot warning: foo" >&2; echo "proot info: bar" >&2; echo "real error" >&2; exit 1;; esac
echo "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"$SID\",\"result\":\"hi\"}"
