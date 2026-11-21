# 해봄 앱

스마트폰이 낯선 사람 옆에서 다음에 누를 곳을 짚어 주는 Android 앱입니다. 하고 싶은 일을 말로 적으면("블루투스 켜는 화면 열어줘") 화면 위에 지금 누를 곳을 고리로 표시하고, 안내 문장("‘설정’ 누르세요")을 말풍선으로 보여 주며 소리 내어 읽습니다. 사용자가 직접 누르면 다음 단계로 넘어갑니다.

앱은 대신 누르거나 입력하지 않습니다. 접근성 서비스에 제스처 권한을 넣지 않았으므로 할 수도 없습니다. 화면을 읽고 그 위에 그리기만 합니다.

2026 서울AI디지털배움터 청년AI해커톤, 팀 Toolus의 저장소 셋 가운데 하나입니다.

```
 해봄 앱 (이 저장소) ──화면 상태──▶ artemis 서버 ──▶ 모델
        ▲            ◀──동작 하나──
        │
        └──문장──▶ TTS 서버 ──▶ 소리
```

| 저장소 | 하는 일 |
|---|---|
| haebom-app (여기) | 화면 위에 다음에 누를 곳을 안내하는 Android 앱 |
| [haebom-artemis](https://github.com/youth-hackathon/haebom-artemis) | 화면 상태를 받아 다음 동작 하나를 고르는 서버 |
| [haebom-tts](https://github.com/youth-hackathon/haebom-tts) | 안내 문장을 소리로 바꾸는 서버 |

서로 아는 것은 주고받는 형식뿐이고, 그 형식은 세 저장소가 같은 글([`docs/interface.md`](docs/interface.md))로 가지고 있습니다.

## 지금 들어 있는 것

길은 하나입니다. 말로 요청하면 artemis 서버의 `baseline` 방식이 단계를 하나씩 고르고, TTS 서버의 `baseline` 엔진이 문장을 읽습니다.

- 들어 있는 것: 말로 요청하기, 단계별 안내(누르기, 길게 누르기, 입력, 밀기, 뒤로·홈 버튼, 앱 열기, 기다리기), 완료 판단, 안내 문장 읽기, TTS 서버가 답하지 않을 때 폰 음성으로 읽기.
- 빠져 있는 것: 미리 정한 순서를 따라가는 매뉴얼 모드. 새 구조로 다시 넣을 예정이어서 이 저장소에는 넣지 않았습니다.
- 폰에는 모델 키가 없습니다. 모델은 artemis 서버가 부릅니다. 앱에는 외부 라이브러리가 없고 Android SDK만 씁니다(디버그 APK 2.8MB).

## 한 단계가 도는 순서

1. **화면 읽기.** 접근성 트리를 artemis가 쓰는 XML 형식으로 뜨고, 스크린샷을 찍습니다. 찍기 전에 앱이 그린 고리와 말풍선은 내립니다.
2. **묻기.** `POST /v1/guide/next`로 요청 문장, 방금 단계가 어떻게 끝났는지(`event`), 화면(XML과 JPEG)을 보냅니다. 폰의 설정값(`facts`: 글자 크기 배율, 다음 알람 시각, 밝기, 음량 등)도 같이 보냅니다. 화면만 봐서는 알 수 없는 "글자가 커졌는지", "알람이 맞춰졌는지"를 서버가 이 값으로 판단할 수 있게 하기 위해서입니다. 권한을 묻지 않고 읽을 수 있는 값만 보냅니다.
3. **동작 하나 받기.** 서버는 artemis의 동작 하나(`click`, `input_text` 등)와 누를 좌표(`target_point_px`)를 돌려줍니다.
4. **안내로 바꾸기.** 그 좌표에서 실제로 눌리는 화면 요소를 접근성 트리에서 찾아 고리를 두르고, 말풍선 문장을 만들고, 그 문장을 TTS 서버에 보내 읽습니다. 대상을 하나로 정하지 못하면 짐작하지 않고 서버에 다시 묻습니다(`unaimable`).
5. **사람이 하는 것을 지켜보기.** 그 요소의 클릭 이벤트가 오거나, 터치 뒤에 화면이 바뀌면 단계가 끝난 것으로 봅니다(`met`). 다른 곳을 눌러도 단계는 끝나지 않고 고리는 대상을 계속 가리킵니다.
6. 1로 돌아갑니다. 서버가 `report_task_status`를 돌려주면 "다 했어요" 카드를 띄우고 끝냅니다.

| 서버가 고른 동작 | 화면의 안내 | 끝났다고 보는 조건 |
|---|---|---|
| `click` | 고리 + "‘이름’ 누르세요" | 그 요소의 클릭 이벤트, 또는 터치 뒤 화면 변화 |
| `long_press` | 고리 + "‘이름’ 길게 누르세요" | 길게 누름 이벤트 |
| `input_text` | 입력란에 고리 + 입력할 글자(복사 버튼 포함). 끝에 줄바꿈이 있으면 이어서 키보드의 실행 키에 고리 | 입력란의 글자가 요청한 글자와 같아짐. 실행 키까지면 그 뒤의 화면 변화 |
| `swipe` | 밀 방향 화살표 + "화면을 위로 밀어 올리세요" | 그 영역의 스크롤 이벤트 |
| `press_key` | 내비게이션 바 버튼에 고리, 제스처 내비게이션이면 문장만 | 그 버튼의 클릭, 또는 화면 전환 |
| `manage_app` | 홈 화면의 앱 아이콘에 고리 | 그 앱이 앞에 나옴 |
| `wait_for_delay` | "잠시 기다려 주세요" | 정해진 시간이 지남 |
| `ask_user` | 질문 카드. 답은 채팅 화면에 적습니다 | 답을 보냄 |
| `report_task_status` | "다 했어요" 또는 "여기서 멈출게요" 카드 | 안내 끝 |

단계가 끝난 방식은 다음 요청의 `event`로 서버에 갑니다. 지금 앱이 보내는 값은 `start`, `met`, `already-satisfied`, `declared_done`(사용자가 ✓를 누름), `declared_stuck`, `unaimable`, `wait_done`, `user_message`입니다.

화면에 보이는 것 가운데 알아 둘 점입니다.

- 고리는 대상을 따라 움직입니다. 대상이 화면 밖으로 나가면 그쪽을 가리키는 화살표로 바뀝니다.
- 누른 뒤 화면이 바뀌기 시작했는데 단계가 아직 끝나지 않았으면 고리를 거두고 "확인하고 있어요"를 보입니다. 5초 안에 끝나지 않으면 다시 대상을 가리킵니다.
- 알림이나 팝업이 대상을 가리면 고리를 바로 거둡니다. 가린 창을 누르게 하지 않기 위해서입니다.
- 화면 구석에 버튼 두 개가 있습니다. ✓는 "했어요"(지금 화면을 다시 보고 다음 단계로), ✕는 안내 끝내기입니다.
- 대상을 네 번 연달아 찾지 못하면 채팅으로 물어봅니다.

## 서버와의 약속

전체는 [`docs/interface.md`](docs/interface.md)에 있습니다. 앱의 코드가 실제로 하는 것만 추리면 이렇습니다.

| | artemis 서버 | TTS 서버 |
|---|---|---|
| 기본 주소 | `http://127.0.0.1:8765` | `http://127.0.0.1:8766` |
| 요청 | `POST /v1/guide/next` (JSON) | `POST /tts` `{"text", "speed": 0.9, "engine"}` |
| 방식 고르기 | 요청의 `mode`. 앱 기본값 `baseline` | 요청의 `engine`. 앱 기본값 `baseline` |
| 기다리는 시간 | 연결 3초, 응답 120초 | 연결 1초, 소리가 다 오기까지 1.5초 |
| 답이 없으면 | "안내를 준비하지 못했어요" 카드와 다시 시도 버튼 | 폰에 든 음성으로 읽고 안내는 그대로 이어 감 |
| 상태 확인 | `GET /health`가 1.5초 안에 200 | 쓰지 않음 |

기본 주소가 `127.0.0.1`인 것은 개발할 때 서버를 같은 컴퓨터에 띄우고 `adb reverse`로 폰과 잇기 때문입니다. 다른 곳에 띄운 서버는 아래 "설정"으로 가리킵니다.

## 빌드와 설치

필요한 것: JDK 17, Android SDK(플랫폼 36), `adb`. Gradle은 저장소의 래퍼(9.5.0)가 받습니다. SDK 위치는 `ANDROID_HOME` 환경 변수나 `local.properties`의 `sdk.dir`로 알려 줍니다.

```bash
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

두 서버를 띄웁니다. 각 저장소의 README를 따르면 됩니다.

```bash
# haebom-artemis 폴더에서 (relay/.env에 키가 있어야 합니다)
docker compose up -d --build   # http://127.0.0.1:8765
# haebom-tts 폴더에서
docker compose up -d --build   # http://127.0.0.1:8766
```

에뮬레이터나 USB로 연결한 폰에 설치하고 서버를 가리킵니다.

```bash
python -I scripts/setup_device.py --serial emulator-5554
```

이 스크립트는 APK 설치, 접근성 서비스 켜기, `adb reverse`(8765, 8766), 앱 설정을 한 번에 하고 마지막에 앱이 답한 설정을 보여 줍니다.

```
install: Success
accessibility service: on
guide server: http://127.0.0.1:8765 (answers /health)
tts server: http://127.0.0.1:8766 (answers /health)
app: CONFIG guide=http://127.0.0.1:8765 mode=baseline tts=http://127.0.0.1:8766 engine=baseline
```

일부 폰(One UI)은 adb로 접근성 서비스를 켤 수 없습니다. 그때는 앱을 열어 "동의하고 설정 열기"를 누르고 목록에서 "해봄 (화면 안내)"를 켭니다.

## 써 보기

앱을 열고 하고 싶은 일을 적어 보냅니다. 앱은 뒤로 물러나고, 화면 위에 안내가 나옵니다.

사람 대신 안내를 따라가는 스크립트도 있습니다. 로그에 찍힌 고리의 위치를 읽어 그 자리를 `adb`로 누릅니다. 누르는 것은 이 스크립트이고 앱이 아닙니다.

```bash
python -I scripts/follow.py "설정에서 블루투스 화면 열어줘" --home --serial emulator-5554
python -I scripts/follow.py "설정에서 battery를 검색해줘" --home --deviate step2=back   # 2번째 단계에서 딴 길로 가 보기
```

단계마다 스크린샷과 기록(`transcript.txt`)을 `runs/` 아래에 남깁니다. Windows에서 한글이 깨지면 `python -X utf8 -I …`로 실행합니다.

## 설정

설정은 앱 화면에 없고 adb로 바꿉니다. `setup_device.py`의 옵션이 같은 일을 합니다.

```bash
python -I scripts/setup_device.py --serial <기기> --no-install \
    --guide https://artemis.example.com --tts https://tts.example.com \
    --mode baseline --engine baseline
```

| 옵션 | 뜻 | 기본값 |
|---|---|---|
| `--guide` | artemis 서버 주소 | `http://127.0.0.1:8765` |
| `--mode` | artemis 서버의 방식. 서버의 `/health`가 `modes`로 알려 주는 이름 | `baseline` |
| `--tts` | TTS 서버 주소. `''`이면 폰 음성만 씁니다 | `http://127.0.0.1:8766` |
| `--engine` | TTS 엔진. 서버의 `/health`가 `engines`로 알려 주는 이름 | `baseline` |

서버에 새 방식이나 새 엔진이 생기면 이 옵션으로 바꿔 보고, 말썽이면 `baseline`으로 돌립니다. 앱은 다시 빌드하지 않아도 됩니다.

## 디버그 명령과 로그

모두 `adb shell`에서만 보낼 수 있습니다(`android.permission.DUMP`로 막혀 있습니다).

```bash
adb shell "am broadcast -a ai.toolus.mobileguide.DEBUG_START -p ai.toolus.mobileguide --es task '와이파이 설정 화면 열어줘'"
```

| 명령 | 하는 일 |
|---|---|
| `DEBUG_START --es task <문장>` | 안내 시작 |
| `DEBUG_REPLY --es text <문장>` | 질문 카드에 답하기 |
| `DEBUG_STOP` | 안내 중단 |
| `DEBUG_DECLARE --es kind done\|stuck\|stop` | ✓ 누르기, 막혔다고 알리기, 끝내기 |
| `DEBUG_ACTION --es json <동작>` | 서버 없이 동작 하나를 안내로 띄워 보기 |
| `DEBUG_DUMP` | 지금 화면에서 읽은 요소 목록을 로그로 |
| `DEBUG_BENCH` | 화면 읽기에 걸리는 시간 재기 |
| `DEBUG_CONFIG [--es guide_base … --es mode … --es tts_base … --es engine …]` | 설정 바꾸기. 값 없이 보내면 지금 설정만 로그로 |

로그 태그는 `ToolusGuide`입니다(`adb logcat -s ToolusGuide`).

| 줄 | 뜻 |
|---|---|
| `plan event=…` | 서버에 물음. 어떤 일로 묻는지와 화면 요약 |
| `step=N action=… -> Guide (…ms)` | 서버의 답과 걸린 시간 |
| `ARM step=N verb=…` | 안내를 띄움 |
| `AIM step=N ring=…` | 고리나 화살표의 위치 |
| `outcome met :: …` | 단계가 끝난 방식과 근거 |
| `TTS server …ms` / `TTS phone voice (…)` | 문장을 서버 소리로 읽었는지, 폰 음성으로 읽었는지 |

## 확인한 결과

2026-11-21, 에뮬레이터(Pixel, Android 16, 한국어, 1080×2400). 같은 컴퓨터에 두 서버를 Docker로 띄웠고 둘 다 `baseline`입니다. 사람 역할은 `scripts/follow.py`가 했습니다.

| 요청 | 결과 | 걸린 시간 | 소리 |
|---|---|---|---|
| "설정에서 블루투스 화면 열어줘" | 안내 5단계 모두 확인되고 완료 | 84.0초 | 안내 문장 5개 모두 TTS 서버 소리 (0.09~0.29초) |
| "설정에서 battery를 검색해줘" | 안내 6단계(누르기 5, 입력 1) 모두 확인되고 완료 | 76.3초 | 안내 문장 6개 모두 TTS 서버 소리 (0.09~0.23초) |
| TTS 서버를 끈 채 안내 한 단계 | 안내는 그대로 뜸 | | 1.5초 뒤 폰 음성으로 읽음 |

걸린 시간의 대부분은 서버가 모델의 답을 기다리는 시간입니다. 한 단계에 5.6~15.3초였습니다. 두 번째 요청에서 단계가 여섯인 것은 설정 앱이 앞의 실행에서 열어 둔 블루투스 화면에서 다시 열려, 뒤로 가는 단계가 세 번 들어갔기 때문입니다.

## 파일

| 파일 | 무엇 |
|---|---|
| `app/src/main/java/ai/toolus/mobileguide/GuideSession.kt` | 한 단계가 도는 순서 전체. 묻고, 안내를 띄우고, 사람이 한 일을 판단합니다 |
| `…/GuideCatalog.kt` | 서버의 동작을 안내로 바꾸는 표. 좌표에서 화면 요소를 찾고 끝나는 조건을 정합니다 |
| `…/GuideOverlay.kt` | 고리, 말풍선, 카드, 버튼을 그립니다 |
| `…/ScreenSnapshot.kt`, `Screenshotter.kt` | 접근성 트리를 XML로 뜨기, 스크린샷 |
| `…/DeviceFacts.kt` | 폰의 설정값 읽기 (요청의 `facts`) |
| `…/PlannerClient.kt` | artemis 서버 호출 |
| `…/Speaker.kt` | TTS 서버 호출과 재생, 폰 음성으로 넘어가기 |
| `…/GuideSettings.kt` | 두 서버의 주소와 방식 |
| `…/GuideAccessibilityService.kt` | 접근성 서비스와 디버그 명령 |
| `…/MainActivity.kt`, `GuideHub.kt` | 채팅 화면 |
| `scripts/setup_device.py` | 설치와 설정 |
| `scripts/follow.py` | 사람 대신 안내를 따라가는 스크립트 |
| `docs/interface.md` | 세 저장소가 같이 가지는 규약 |

## 알려진 한계

- 매뉴얼 모드가 없습니다. 모든 단계를 서버의 모델이 고르므로 단계마다 5~15초를 기다립니다.
- 패키지 이름(`ai.toolus.mobileguide`)과 로그 태그(`ToolusGuide`)는 이전 이름 그대로입니다.
- 서버 주소는 adb로만 바꿀 수 있고, 앱은 `http` 주소도 받습니다. 개발용 설정입니다.
- 스크린샷을 막아 둔 화면(은행 앱 등)은 접근성 트리만 서버에 갑니다.
- ✕ 뒤의 "강사에게 물어보기"는 로그와 앱 안의 브로드캐스트만 남깁니다. 받는 쪽이 아직 없습니다.
- `declared_stuck`은 화면에 버튼이 없고 디버그 명령으로만 보낼 수 있습니다.

## 라이선스

Apache-2.0 ([`LICENSE`](LICENSE)). 화면을 XML로 뜨는 부분은 google/artemis의 코드를 Kotlin으로 옮긴 것입니다([`NOTICE`](NOTICE)).
