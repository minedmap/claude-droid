#!/data/data/com.termux/files/usr/bin/bash
# Claude Droid: register the USB MCP in Ubuntu(proot) Claude Code + optional tools. Each step may fail; summary at the end.
MCP=/data/data/com.termux/files/home/claude-droid/usb-mcp.mjs
DOC=/data/data/com.termux/files/home/claude-droid/CLAUDE-USB.md
[ -f "$MCP" ] || { echo "usb-mcp.mjs 없음: 먼저 bash bridge/install.sh 실행"; exit 1; }
INNER=/data/data/com.termux/files/home/claude-droid/.usb-inner.sh
cat > "$INNER" <<'INNER_EOF'
export HOME=/root PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin
RES=()
step() { local n="$1"; shift; if "$@"; then RES+=("OK   $n"); else RES+=("FAIL $n"); fi; }

install_node() {
  if command -v node >/dev/null && [ "$(node -p 'process.versions.node.split(".")[0]')" -ge 20 ]; then return 0; fi
  apt-get update && apt-get install -y curl ca-certificates && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs
}
register_mcp() {
  claude mcp remove -s user usb >/dev/null 2>&1
  claude mcp add -s user usb -- node "$MCP" && claude mcp list | grep -q '^usb'
}
include_doc() {
  mkdir -p /root/.claude && touch /root/.claude/CLAUDE.md
  grep -qF "$DOC" /root/.claude/CLAUDE.md || printf '\n@%s\n' "$DOC" >> /root/.claude/CLAUDE.md
}
install_arduino_cli() {
  command -v arduino-cli >/dev/null && return 0
  apt-get install -y curl ca-certificates tar || return 1
  curl -fsSL https://downloads.arduino.cc/arduino-cli/arduino-cli_latest_Linux_ARM64.tar.gz | tar -xz -C /usr/local/bin arduino-cli
}
install_avr_core() { arduino-cli core update-index && arduino-cli core install arduino:avr; }
install_python_tools() { apt-get install -y python3-pip && pip3 install --break-system-packages pyserial esptool 2>/dev/null || pip3 install pyserial esptool; }
install_net_tools() { apt-get install -y nmap iproute2; }

step "node>=20" install_node
step "usb MCP 등록" register_mcp
step "CLAUDE.md include" include_doc
step "arduino-cli" install_arduino_cli
step "arduino:avr core" install_avr_core
step "pyserial+esptool" install_python_tools
step "nmap+iproute2" install_net_tools
echo; echo "== usb-setup 요약 =="; printf '%s\n' "${RES[@]}"
INNER_EOF
proot-distro login ubuntu -- env MCP="$MCP" DOC="$DOC" bash -l "$INNER" </dev/null
