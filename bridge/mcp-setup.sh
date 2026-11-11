#!/data/data/com.termux/files/usr/bin/bash
# Kept for old instructions: phone control moved to the standalone control/ folder.
exec bash "$(cd "$(dirname "$0")" && pwd)/../control/setup.sh" "$@"
