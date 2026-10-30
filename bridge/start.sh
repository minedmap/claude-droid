#!/data/data/com.termux/files/usr/bin/bash
# claude-droid bridge auto-start: health 확인 -> sv up -> 직접 실행
P=/data/data/com.termux/files/usr
export HOME=/data/data/com.termux/files/home
export PATH=$P/bin:$PATH
export TMPDIR=$P/tmp
for d in /data/data/com.termux/files/usr/var/lib/proot-distro/containers/ubuntu/rootfs /data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu; do [ -d "$d" ] && export UBUNTU_ROOTFS="$d" && break; done
health() { curl -s -H "X-Token: $(cat $HOME/.claude-droid-token)" 127.0.0.1:8765/health 2>/dev/null | grep -qi ok; }
if health; then echo "bridge ok"; exit 0; fi
SVDIR=$P/var/service sv up $P/var/service/claude-droid >/dev/null 2>&1
sleep 3
if health; then echo "bridge ok (sv)"; exit 0; fi
cd "$HOME"
[ -x "$HOME/claude-droid/claude-proot" ] && export CLAUDE_BIN="$HOME/claude-droid/claude-proot"
nohup node "$HOME/claude-droid/server.mjs" >"$HOME/claude-droid/bridge.log" 2>&1 </dev/null &
for i in 1 2 3 4 5; do
  sleep 1
  if health; then echo "bridge ok (direct)"; exit 0; fi
done
echo "bridge FAILED (see ~/claude-droid/bridge.log)"; exit 1
