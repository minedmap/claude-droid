# Claude Droid

[English](README.md) | **한국어**

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

## 처음 설치하는 기기 (체크리스트)

새 폰 기준 전체 순서입니다. 아래 「설치」 절의 명령은 이 순서 안에서 실행합니다.

1. **F-Droid 설치**: https://f-droid.org 에서 APK를 받아 설치합니다. 브라우저가 "출처를 알 수 없는 앱 설치" 허용을 요구하면 허용합니다.
   - Play 스토어판 Termux는 오래돼서 동작하지 않습니다. 반드시 F-Droid판을 쓰세요.
2. **Termux, Termux:API 설치**(F-Droid) → Termux를 한 번 실행한 뒤 `pkg upgrade` (질문이 나오면 Enter).
3. **Ubuntu + Claude Code 설치**: 아래 「설치」 2번 명령. Claude 로그인 후 `/exit`.
4. **브리지 설치**: 아래 「설치」 3번 명령. 마지막에 나오는 TOKEN을 복사합니다(다시 보기: `cat ~/.claude-droid-token`).
5. **Termux 완전 종료 후 재실행**(필수): 알림창의 Termux 알림 → **Exit** → Termux를 다시 엽니다. `termux-services`를 처음 설치하면 이걸 해야 브리지 서비스가 자동 실행됩니다. 이후 `bash ~/claude-droid/start.sh`로 확인합니다.
6. **개발자 옵션 켜기**: 설정 → 휴대전화 정보 → 소프트웨어 정보 → **빌드번호** 7번 탭(제조사마다 위치가 조금 다름).
7. **무선 디버깅 켜기**: 설정 → 개발자 옵션 → **무선 디버깅** ON(Wi-Fi 연결 필요, Android 11+). 페어링은 앱의 연결 마법사가 안내합니다.
8. **앱 APK 설치**: [Releases](https://github.com/minedmap/claude-droid/releases/latest)의 `app-debug.apk`.
   - 첫 실행 시 **알림 권한 허용**(백그라운드 실행, 페어링 코드 입력에 필요).
   - 설정 → 앱 → Claude Droid, Termux → 배터리 → **제한 없음/최적화 안 함**(긴 작업 중 종료 방지).
9. **앱에서 토큰 붙여넣기 → 연결 마법사**: RUN_COMMAND 권한 허용 → 무선 디버깅 페어링.
10. **(선택) 화면 조작 MCP**: `bash ~/bridge/mcp-setup.sh`.
11. **(선택) USB 장치 제어**: `bash ~/claude-droid/usb-setup.sh` 실행.
    - 일부 기기(삼성 일부, 샤오미 등)는 설정에서 **OTG** 항목을 따로 켜야 합니다.
    - OTG 젠더/케이블로 장치를 꽂고, 뜨는 권한 팝업에서 Claude Droid를 허용합니다(앱 설정 → USB 장치에서 다시 요청 가능).

### 명령어 상세 설명

모든 명령은 **Termux**에서 실행합니다(Ubuntu 안이 아님). 길게 눌러 붙여넣기 하면 됩니다.

**1) Termux 패키지 최신화**
```sh
pkg upgrade -y
```
- Termux 패키지 목록과 설치된 패키지를 최신으로 올립니다. 이걸 건너뛰면 `node` 실행 시 `CANNOT LINK EXECUTABLE` 같은 라이브러리 오류가 납니다.
- 중간에 설정 파일을 덮어쓸지 물으면 Enter(기본값)를 누릅니다.

**2) Ubuntu + Claude Code 설치**
```sh
pkg install -y proot-distro && proot-distro install ubuntu && proot-distro login ubuntu -- bash -c "apt update && apt install -y curl adb && curl -fsSL https://claude.ai/install.sh | bash && /root/.local/bin/claude"
```
- `pkg install -y proot-distro`: 루팅 없이 리눅스 배포판을 돌리는 도구를 설치합니다.
- `proot-distro install ubuntu`: Ubuntu 파일시스템(수백 MB)을 받습니다. Claude Code에는 Android용 바이너리가 없어서 Ubuntu 안에서 돌립니다.
- `proot-distro login ubuntu -- bash -c "..."`: Ubuntu 안에서 따옴표 속 명령을 실행합니다.
  - `apt install -y curl adb`: 다운로드 도구와 adb(폰 조작용)를 설치합니다.
  - `curl -fsSL https://claude.ai/install.sh | bash`: 공식 Claude Code 설치 스크립트입니다. `/root/.local/bin/claude`에 설치됩니다.
  - `/root/.local/bin/claude`: Claude Code를 처음 실행해 로그인합니다. 브라우저 링크가 뜨면 로그인 후 코드를 붙여넣고, 끝나면 `/exit`.

**3) 브리지 설치**
```sh
cd ~ && curl -LO https://github.com/minedmap/claude-droid/releases/latest/download/bridge.zip && unzip -o bridge.zip && bash bridge/install.sh
```
- `curl -LO ...bridge.zip`: 최신 브리지 묶음을 홈(`~`)에 받습니다. `unzip -o`는 `~/bridge/`에 풀고 기존 파일을 덮어씁니다.
- `bash bridge/install.sh`가 하는 일:
  - `nodejs android-tools termux-api termux-services` 설치
  - `~/claude-droid/`에 `server.mjs`, `start.sh`, `usb-mcp.mjs`, `usb-setup.sh` 복사
  - 토큰 `~/.claude-droid-token` 생성(이미 있으면 유지)
  - `~/.termux/termux.properties`에 `allow-external-apps=true` 설정(앱이 Termux 명령을 실행하도록, RUN_COMMAND)
  - Ubuntu의 Claude를 부르는 래퍼 `~/claude-droid/claude-proot` 생성
  - 상주 서비스 `claude-droid` 등록·시작, `termux-wake-lock`(절전 중에도 유지)
  - 마지막 줄에 `TOKEN: ...` 출력 → 앱에 붙여넣을 값입니다. **Claude 로그인 정보가 아닙니다.**
- 업데이트할 때도 같은 명령을 다시 실행하면 됩니다(토큰은 유지).

**4) 서비스 상태 확인 / 수동 시작**
```sh
bash ~/claude-droid/start.sh
```
- 브리지가 응답하면 `bridge ok`. 꺼져 있으면 서비스(`sv up`)로 켜고, 그래도 안 되면 직접 실행합니다.
- `bridge FAILED`가 나오면 `cat ~/claude-droid/bridge.log`로 원인을 확인합니다.

```sh
sv status claude-droid
tail -20 $PREFIX/var/log/sv/claude-droid/current
```
- `run (pid ...)`이면 정상. `down`이 반복되면 두 번째 명령으로 서비스 로그를 봅니다.
- `sv`가 `unable to change to service directory`를 내면 Termux를 완전 종료(알림 → Exit) 후 다시 여세요.

**5) 토큰 다시 보기**
```sh
cat ~/.claude-droid-token
```

**6) (선택) 화면 조작 MCP 등록**
```sh
bash ~/bridge/mcp-setup.sh
```
- Ubuntu 안에 Node 22와 [claude-in-mobile](https://github.com/AlexGladkov/claude-in-mobile)을 설치하고 Claude에 `mobile` 도구로 등록합니다(스크린샷, 탭, 스와이프, UI 트리).
- Termux의 adb 키를 Ubuntu로 복사해서, 무선 디버깅을 다시 페어링하지 않아도 됩니다. 마지막에 `adb devices`에 기기가 보이면 성공입니다.

**7) (선택) USB 장치 제어 설정**
```sh
bash ~/claude-droid/usb-setup.sh
```
- Ubuntu 안에서 단계별로 실행하고, 끝에 `OK`/`FAIL` 요약을 보여줍니다. 한 단계가 실패해도 나머지는 계속합니다.
  - `node>=20`: MCP 실행용 Node 설치
  - `usb MCP 등록`: Claude에 `usb` 도구 등록(장치 목록, 권한, 전송, 시리얼, AVR 업로드)
  - `CLAUDE.md include`: Claude가 USB 사용법 문서를 자동으로 읽도록 연결
  - `arduino-cli`, `arduino:avr core`: 아두이노 코드 컴파일 도구
  - `pyserial+esptool`: 파이썬 시리얼 / ESP32·ESP8266 업로드 도구
  - `nmap+iproute2`: 네트워크 조사 도구
- 앱이 켜져 있어야 USB 서비스(127.0.0.1:8766)가 응답합니다.

**8) 등록된 Claude 도구 확인**
```sh
proot-distro login ubuntu -- /root/.local/bin/claude mcp list
```
- `mobile`, `usb`가 목록에 있고 `Connected`이면 Claude가 해당 도구를 쓸 수 있습니다.

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
   마지막에 출력되는 TOKEN을 복사.
   (선택) 화면 조작 MCP [claude-in-mobile](https://github.com/AlexGladkov/claude-in-mobile) 등록: `bash bridge/mcp-setup.sh` (`termux-services` 첫 설치 시 Termux 완전 종료 후 재실행)
4. 앱 APK 설치 → 설정에 토큰 붙여넣기 → 연결 마법사 순서대로.
   - RUN_COMMAND 권한 허용, 무선 디버깅 페어링(알림 입력 또는 수동 포트/코드 입력).

포트 변경: `~/claude-droid/env` 파일에 `BRIDGE_PORT=9000` 작성(서비스 run 스크립트와 start.sh가 읽음, 재설치해도 유지) 후 앱 설정의 브리지 URL도 변경.

### 브리지 API
- 프롬프트는 argv가 아니라 **stdin**으로 `claude -p`에 전달(`-`로 시작/128KB 초과도 가능).
- `POST /chat` → SSE, 각 이벤트 `id: <N>\ndata: <json>`(N=0부터). 클라이언트가 끊겨도 claude는 계속 실행, 이벤트는 런당 ~20MB까지 버퍼링하고 종료 후 15분 보관.
- `GET /chat/<reqId>?from=N` → N번 이후 이벤트 재전송 후 실시간 이어받기. 없으면 404 `{error:'unknown reqId'}`.
- `GET /runs` → `{runs:[{reqId,startedAt,done,code,events}]}`. `POST /cancel {reqId}`만 프로세스를 종료.
- 잘못된 `model`/`effort` → 400 `{error:'invalid model'|'invalid effort'}`. 모든 4xx는 JSON `{error}`.
- `/file`·`/stat`은 자격증명류(`.credentials.json`, `.ssh/`, `*.pem`, `*.key`, `.env` 등) 403.

## USB 호스트 제어 (v0.6)

OTG로 폰에 꽂은 USB 장치를 Claude가 조회·조작합니다. **루팅 불필요**: Android `UsbManager`는 권한 한 번 탭하면 앱이 장치를 직접 열 수 있고, 앱이 이를 루프백 API로 Claude에 노출합니다.

```
[Claude (proot)] --stdio MCP--> bridge/usb-mcp.mjs --HTTP + X-Token--> [앱 UsbBridgeService 127.0.0.1:8766]
[esptool / pyserial / avrdude] --rfc2217:// 또는 socket:// 127.0.0.1:<포트>--> [앱 시리얼 TCP 브리지]
```

- MCP 도구 12개: `usb_list`, `usb_permission`, `usb_descriptors`, `usb_control`, `usb_transfer`, `serial_open/write/read/lines/close`, `serial_tcp`, `avr_flash`.
- 시리얼 칩은 usb-serial-for-android 사용: CDC/ACM, FTDI, CP210x, CH34x, PL2303.
- `avr_flash`: 내장 STK500v1/v2 업로더 (Uno, Nano, 구형 부트로더 Nano, Mega2560). `arduino-cli compile` -> `avr_flash` -> `serial_read` 순환. Leonardo/Micro(AVR109)는 미지원.
- `serial_tcp`: 포트를 RFC 2217(보드레이트·데이터/패리티/스톱·DTR/RTS·purge 부분집합) 또는 raw TCP로 노출 → esptool/pyserial을 그대로 사용.
- 설정: Termux에서 `install.sh` 후 `bash ~/claude-droid/usb-setup.sh` (Ubuntu에 `usb` MCP 등록, arduino-cli·pyserial·esptool·nmap 설치). 앱 실행 시 USB 서비스가 자동 시작되고 **설정 -> USB 장치**에서 상태/권한 버튼을 볼 수 있습니다.
- 채팅 브리지와 같은 토큰 사용. API는 127.0.0.1만 bind하고 `X-Token` 없으면 거부합니다.

한계: Android가 앱에 허용하는 장치만 가능(키보드·저장장치 등 시스템이 잡은 장치는 claim 실패 가능), 커널 드라이버가 필요한 장치는 불가, OTG 전원/허브는 사용자 몫, 장치마다 폰에서 권한 탭 필요.

## 파일 / 복사 (v0.5)

- **파일 업로드**: 입력창 옆 클립 버튼 → 파일 선택(여러 개) 또는 `[클립보드 이미지 붙여넣기]`. 칩으로 표시되며 전송 시 프롬프트에 `첨부 파일:` 경로 목록이 붙습니다. 파일은 세션 cwd의 `.claude-droid/uploads/`에 저장(최대 50MB).
- **미리보기**: Claude가 만든/언급한 파일(Write·Edit·MCP 결과·본문 경로)이 카드로 표시됩니다. 이미지 · md · json · 텍스트 미리보기, 이미지 탭 시 확대(핀치 줌).
- **다운로드 / 공유**: 카드의 `다운로드`는 `Download/ClaudeDroid`에 저장(Android 9 이하는 저장 위치 선택), `공유`는 공유 시트.
- **복사**: 말풍선 길게 누르면 전체 복사, 어시스턴트 메시지에 복사 버튼, 텍스트 선택 가능. (코드블록 개별 복사 버튼은 미지원)
- 브리지 API: `POST /upload`, `GET /file?path=`, `GET /stat?path=` — HOME · Ubuntu rootfs · /sdcard · tmp 아래만 허용.

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
