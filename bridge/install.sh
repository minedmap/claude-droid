#!/data/data/com.termux/files/usr/bin/bash
set -e
cd "$(dirname "$0")"
pkg install -y nodejs android-tools termux-api termux-services
mkdir -p ~/claude-droid && cp server.mjs ~/claude-droid/
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
cd "$HOME"
exec /data/data/com.termux/files/usr/bin/node "$HOME/claude-droid/server.mjs" 2>&1
EOF
chmod +x $PREFIX/var/service/claude-droid/run
ln -sf $PREFIX/share/termux-services/svlogger $PREFIX/var/service/claude-droid/log/run
sv-enable claude-droid || true
sv up claude-droid || true
termux-wake-lock
echo "TOKEN: $(cat ~/.claude-droid-token)   <- 앱 설정에 붙여넣기"
