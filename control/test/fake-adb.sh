#!/usr/bin/env bash
# Minimal adb stand-in for the stdio test: `devices`, and `-s <serial> shell <cmd>`.
if [ "$1" = devices ]; then printf "List of devices attached\nlocalhost:40123\tdevice\n\n"; exit 0; fi
[ "$1" = -s ] && shift 2
[ "$1" = shell ] || exit 1
case "$2" in
  "exit 3") echo "fake: exit 3"; exit 3 ;;
  *) exit 0 ;;
esac
