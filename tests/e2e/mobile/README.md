# tests/e2e/mobile — 앱 화면 e2e (Maestro)

iOS 시뮬레이터와 Android 에뮬레이터의 앱을 **같은 흐름 파일 하나**로 눌러 본다.
서버는 건드리지 않는다. 앱이 DEV 서버(`https://api-dev.scenetrip.io/v1`)에 붙은 채로 **읽기만** 한다.
Bazel 레인이 아니다 — 켜 둔 기기가 있어야 돌아서 `just` 명령으로만 부른다.

## 준비 (한 번)

```bash
brew tap mobile-dev-inc/tap
brew install mobile-dev-inc/tap/maestro openjdk@17   # 자바 17 이상이 필요하다
```

앱은 DEV 서버로 빌드해 기기에 깔아 둔다.

```bash
just ios-run-cloud https://api-dev.scenetrip.io/v1            # 시뮬레이터에 설치·실행
just mobile-build-cloud android https://api-dev.scenetrip.io/v1
just android-device install -r bazel-bin/apps/scenetrip-android/bin.apk
```

## 돌리기

```bash
just e2e-mobile <기기> [출력 폴더] [흐름 파일...]   # 기기: simctl UDID 또는 adb 이름(emulator-5554)
just e2e-mobile-compare <iOS 출력> <Android 출력> <결과 폴더>   # 단계별 나란히 보기
```

출력 폴더 안에 단계 이름별 스크린샷이 남는다. 두 앱이 같은 이름으로 찍으므로 `compare.py` 가
`<흐름>__<단계>.jpg` 한 장(왼쪽 iOS, 오른쪽 Android)으로 합친다.

## 흐름

| 파일 | 보는 것 |
| --- | --- |
| `01-onboarding` | 언어 고르기 → 사용법 넉 장 → 홈 |
| `02-home` | 인사말·내 여행·지금 뜨는 작품·오늘의 성지 |
| `03-search-work` | 검색 탭 → 작품 상세 → 촬영지 상세 (찜·담기는 누르지 않는다) |
| `04-profile-community` | 마이페이지·커뮤니티 |
| `05-course-wizard` | 코스 첫 화면·내 코스 (AI 계획 생성은 누르지 않는다) |
| `06-guest-wipe-dialog` | 「이 기기의 기록 지우기」 대화상자 — 열어서 문구만 보고 취소 |
| `07-profile-full` | 마이페이지 끝까지 |
| `08-english` | 영어 선택 후 온보딩·홈 |

## 쓰는 규칙

- **저장·삭제·유료 호출을 흐름에 넣지 않는다.** 시작·길찾기·안내 단추, 가이드 대화, 계획 생성, 로그인·탈퇴,
  찜·담기·글쓰기 금지. 지우기 대화상자는 `취소`로 끝낸다.
- 글자는 **두 앱이 같은 한국어 문구**로 건다. 한쪽에만 있는 글자·접근성 이름에 기대면 한 앱에서만 통과한다.
- 단계 이름(`takeScreenshot`)은 숫자만 쓰지 않는다 — YAML 이 정수로 읽어 파싱이 깨진다.
- 첫 화면은 `subflows/first-run.yaml`(깨끗한 설치 → 한국어 → 사용법 건너뛰기)로 시작한다.
