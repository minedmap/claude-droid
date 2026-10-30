#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
pkg install -y nodejs android-tools termux-api termux-services
mkdir -p ~/claude-droid && cp server.mjs ~/claude-droid/
cp start.sh ~/claude-droid/start.sh && chmod +x ~/claude-droid/start.sh
[ -f ~/.claude-droid-token ] || head -c 24 /dev/urandom | base64 | tr -d '/+=' > ~/.claude-droid-token
chmod 600 ~/.claude-droid-token
# 앱의 RUN_COMMAND 허용
mkdir -p ~/.termux
grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null || echo "allow-external-apps=true" >> ~/.termux/termux.properties
termux-reload-settings || true
# 상주 서비스
mkdir -p $PREFIX/var/service/claude-droid/log
cat > $PREFIX/var/service/claude-droid/run <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
export HOME=/data/data/com.termux/files/home
export PATH=/data/data/com.termux/files/usr/bin:$PATH
export TMPDIR=/data/data/com.termux/files/usr/tmp
for d in /data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu; do [ -d "$d" ] && export UBUNTU_ROOTFS="$d" && break; done
cd "$HOME"
[ -x "$HOME/claude-droid/claude-proot" ] && export CLAUDE_BIN="$HOME/claude-droid/claude-proot"
exec /data/data/com.termux/files/usr/bin/node "$HOME/claude-droid/server.mjs" 2>&1
EOF
chmod +x $PREFIX/var/service/claude-droid/run
ln -sf $PREFIX/share/termux-services/svlogger $PREFIX/var/service/claude-droid/log/run
sv-enable claude-droid || true
sv up claude-droid || true
# Ubuntu(proot) 안의 최신 Claude Code 사용 (Android용 네이티브 바이너리 없음)
rm -f ~/claude-droid/claude-proot
if proot-distro login ubuntu -- test -x /root/.local/bin/claude 2>/dev/null; then
  cat > ~/claude-droid/claude-proot <<'EOF'
#!/data/data/com.termux/files/usr/bin/bash
H=/data/data/com.termux/files/home
exec proot-distro login ubuntu --work-dir "${CLAUDE_DROID_CWD:-$PWD}" -- env IS_SANDBOX=1 HOME=/root PATH=/root/.local/bin:/usr/local/bin:/usr/bin:/bin /root/.local/bin/claude "$@"
EOF
  chmod +x ~/claude-droid/claude-proot
  echo "claude: proot ubuntu 사용"
fi
termux-wake-lock
echo "TOKEN: $(cat ~/.claude-droid-token)   <- 앱 설정에 붙여넣기"
