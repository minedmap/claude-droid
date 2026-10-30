#!/data/data/com.termux/files/usr/bin/bash
# Ubuntu(proot) 안 Claude Code에 claude-in-mobile MCP(Android 화면 조작 도구) 등록
set -e
proot-distro login ubuntu -- bash -lc '
set -e
export HOME=/root PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin
if ! command -v node >/dev/null || [ "$(node -p "process.versions.node.split(\".\")[0]")" -lt 20 ]; then
  apt update && apt install -y curl ca-certificates
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt install -y nodejs
fi
command -v adb >/dev/null || apt install -y adb
npm i -g claude-in-mobile
claude mcp remove -s user mobile >/dev/null 2>&1 || true
claude mcp add -s user mobile -- claude-in-mobile
claude mcp list
adb devices
'
echo "완료: Claude가 mobile MCP 도구(스크린샷/탭/스와이프/UI트리 등) 사용 가능"
