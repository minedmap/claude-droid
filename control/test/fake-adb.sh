#!/usr/bin/env bash
# Minimal adb stand-in for the stdio test: `devices`, `-s <serial> shell <cmd>` and `exec-out uiautomator dump` (serves settings-screen.xml).
if [ "$1" = devices ]; then printf "List of devices attached\nlocalhost:40123\tdevice\n\n"; exit 0; fi
[ "$1" = -s ] && shift 2
if [ "$1" = exec-out ] && [ "$2" = uiautomator ]; then cat "$(dirname "$0")/settings-screen.xml"; exit 0; fi
[ "$1" = shell ] || exit 1
case "$2" in
  "exit 3") echo "fake: exit 3"; exit 3 ;;
  "wm size") echo "Physical size: 1080x2400"; exit 0 ;;
  *) exit 0 ;;
esac
