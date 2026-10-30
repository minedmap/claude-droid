#!/usr/bin/env bash
SID="fake-sid-123"; prev=""
for a in "$@"; do [ "$prev" = "--resume" ] && SID="$a"; prev="$a"; done
echo "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"$SID\",\"tmp\":\"$TMPDIR\",\"args\":\"$*\"}"
echo "{\"type\":\"assistant\",\"session_id\":\"$SID\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}"
echo "{\"type\":\"result\",\"subtype\":\"success\",\"session_id\":\"$SID\",\"result\":\"hi\"}"
