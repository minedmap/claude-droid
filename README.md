# Claude Droid

Android에서 **Claude Code**를 채팅 GUI로 쓰는 앱. 세션 분리/재개, 모델·effort 선택, 무선 디버깅(adb) 연결 마법사 포함.
Claude가 adb shell 권한으로 폰을 직접 조작할 수 있다.

## 구조
```
[Claude Droid 앱] --HTTP/SSE + X-Token--> [bridge/server.mjs (Termux, 127.0.0.1:8765)]
                                              └─ spawn → claude -p --output-format stream-json --resume <id>
                                                         (Termux 안 proot-distro Ubuntu에서 실행)
                                                         └─ adb (무선 디버깅 localhost) → 폰 조작
[앱] --RUN_COMMAND--> Termux : 브리지 자동 시작, adb pair/connect
[앱] --Shizuku(선택)--> shell(uid 2000) 명령
```
- Android용 Claude Code 네이티브 바이너리가 없어(2.1.113+), Termux의 proot Ubuntu 안에 공식 설치본을 둔다.
- 세션 = Claude `session_id` + 작업 폴더. 앱 재시작 후에도 `--resume`으로 이어짐.

## 설치
1. F-Droid에서 **Termux**, **Termux:API** 설치. Termux에서 `pkg upgrade`.
2. Ubuntu + Claude Code:
   ```
   pkg install -y proot-distro && proot-distro install ubuntu && proot-distro login ubuntu -- bash -c "apt update && apt install -y curl adb && curl -fsSL https://claude.ai/install.sh | bash && /root/.local/bin/claude"
   ```
   로그인 후 `/exit`.
3. 브리지 ([Releases](https://github.com/minedmap/claude-droid/releases) 최신 `bridge.zip`):
   ```
   cd ~ && curl -LO https://github.com/minedmap/claude-droid/releases/latest/download/bridge.zip && unzip -o bridge.zip && bash bridge/install.sh
   ```
   마지막에 출력되는 TOKEN을 복사. (`termux-services` 첫 설치 시 Termux 완전 종료 후 재실행)
4. 앱 APK 설치 → 설정에 토큰 붙여넣기 → 연결 마법사 순서대로.
   - RUN_COMMAND 권한 허용, 무선 디버깅 페어링(알림 입력 또는 수동 포트/코드 입력).

포트 변경: 서비스 run 스크립트에 `export BRIDGE_PORT=9000` 추가 후 앱 설정의 브리지 URL도 변경.

## ⚠ 보안
- 기본값으로 `--dangerously-skip-permissions` 사용 → Claude가 **확인 없이** 명령 실행. 무선 디버깅 연결 시 폰 전체 조작 가능. 설정에서 끌 수 있음(끄면 권한이 필요한 작업에서 멈춤).
- proot Ubuntu는 root로 실행되므로 `IS_SANDBOX=1`을 설정한다.
- `install.sh`는 Termux에 `allow-external-apps=true`를 켠다. **RUN_COMMAND 권한을 받은 모든 앱**이 Termux 명령을 실행할 수 있게 되니 해당 권한은 신뢰하는 앱에만 허용할 것.
- 브리지는 `127.0.0.1`에만 열리고, 무작위 토큰(`~/.claude-droid-token`)이 없으면 401.

## 개발
- 브리지 테스트: `cd bridge && node --test`
- APK는 GitHub Actions에서 빌드(`.github/workflows/build.yml`).

## 라이선스
Apache License 2.0 (`LICENSE`). Shizuku(Apache-2.0)에서 차용한 코드 목록은 `NOTICE.md`.
