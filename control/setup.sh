#!/data/data/com.termux/files/usr/bin/bash
# Droid Control: install the standalone phone-control MCP server (adb) and register it.
# Needs no Claude Droid chat bridge and no token. Each step may fail; summary at the end.
DIR="$(cd "$(dirname "$0")" && pwd)"
MCP="$DIR/control-mcp.mjs"
[ -f "$MCP" ] || { echo "control-mcp.mjs 없음: $DIR"; exit 1; }
RES=()
step() { local n="$1"; shift; if "$@"; then RES+=("OK   $n"); else RES+=("FAIL $n"); fi; }

termux_pkgs() { pkg install -y nodejs android-tools; }
termux_magick() { command -v magick >/dev/null || pkg install -y imagemagick; }
# lets the Claude Droid app run adb pair/connect through RUN_COMMAND
allow_external() {
  mkdir -p ~/.termux
  if grep -q '^#\?[[:space:]]*allow-external-apps' ~/.termux/termux.properties 2>/dev/null; then
    sed -i 's/^#\?[[:space:]]*allow-external-apps.*/allow-external-apps=true/' ~/.termux/termux.properties
  else
    echo "allow-external-apps=true" >> ~/.termux/termux.properties
  fi
  termux-reload-settings 2>/dev/null || true
}
register_claude() {
  claude mcp remove -s user mobile >/dev/null 2>&1
  claude mcp remove -s user phone >/dev/null 2>&1
  claude mcp add -s user phone -- node "$MCP"
}

step "Termux: nodejs + android-tools" termux_pkgs
step "Termux: imagemagick (선택, 스크린샷 축소)" termux_magick
step "Termux: allow-external-apps" allow_external
command -v claude >/dev/null && step "Termux Claude Code: phone MCP 등록" register_claude

# Claude Code inside proot Ubuntu (the Claude Droid chat setup), if present
if command -v proot-distro >/dev/null && proot-distro login ubuntu -- true >/dev/null 2>&1; then
  INNER="$DIR/.setup-inner.sh"
  cat > "$INNER" <<'INNER_EOF'
export HOME=/root PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin
RES=()
step() { local n="$1"; shift; if "$@"; then RES+=("OK   $n"); else RES+=("FAIL $n"); fi; }
install_node() {
  if command -v node >/dev/null && [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 20 ]; then return 0; fi
  apt-get update && apt-get install -y curl ca-certificates && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs
}
install_adb() { command -v adb >/dev/null || apt-get install -y adb; }
# share Termux's adb key so wireless debugging needs no second pairing
share_keys() {
  local T=/data/data/com.termux/files/home/.android
  [ -f "$T/adbkey" ] || return 0
  mkdir -p /root/.android && cp -f "$T/adbkey" "$T/adbkey.pub" /root/.android/
}
install_magick() { command -v magick >/dev/null || command -v convert >/dev/null || apt-get install -y imagemagick; }
register_claude() {
  command -v claude >/dev/null || { echo "Ubuntu에 claude 없음"; return 1; }
  claude mcp remove -s user mobile >/dev/null 2>&1
  claude mcp remove -s user phone >/dev/null 2>&1
  claude mcp add -s user phone -- node "$MCP"
}
step "Ubuntu: node>=20" install_node
step "Ubuntu: adb" install_adb
step "Ubuntu: adb 키 공유" share_keys
step "Ubuntu: imagemagick (선택)" install_magick
step "Ubuntu Claude Code: phone MCP 등록" register_claude
printf '%s\n' "${RES[@]}"
INNER_EOF
  echo "== Ubuntu(proot) =="
  proot-distro login ubuntu -- env MCP="$MCP" bash -l "$INNER" </dev/null
fi

echo; echo "== control setup 요약 (Termux) =="; printf '%s\n' "${RES[@]}"
adb devices 2>/dev/null
cat <<EOF

다른 MCP 클라이언트(Codex, Gemini CLI 등)는 stdio 서버로 직접 등록하세요:
  command: node
  args:    ["$MCP"]
폰이 목록에 없으면: Claude Droid 앱 -> 폰 제어 (adb) 에서 무선 디버깅 페어링.
한글/이모지 입력(선택): ADBKeyboard APK 설치 (adb install ADBKeyboard.apk) — https://github.com/senzhk/ADBKeyBoard
EOF
