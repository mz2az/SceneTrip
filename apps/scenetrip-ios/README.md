# scenetrip-ios

> 모듈 종류: `app` · 언어: `swift` · 경로: `apps/scenetrip-ios`

## 목적

SceneTrip 의 iOS 네이티브 앱. 첫 화면인 **작품검색 탭**(지도 + 바텀시트 + 검색)을
만든다 — 화면 구조·검색 규칙의 기준은
[검색 탭 네이티브 구현 계획](../../docs/project/plans/mobile-native-search-tab.md) §3 이다.

현재 구현된 동선:

- 지도 핀은 **번호 박힌 그라데이션 핀**(파스텔 하늘→보라, 흰 배지에 컬러 번호)이고,
  시트의 장소 목록이 같은 배열을 같은 순서로 그리므로 행 번호가 곧 핀 번호다
  (`NaverMapView.PinImage` — 색은 `light`/`deep` 상수 두 줄로 바꾼다).
- 장소를 고르면(목록 행·핀) 카메라가 그 장소로 **확대**되고, 검색을 확정하면 결과
  도착 시점에 결과 전체 범위로 fit 된다 — 확정 즉시 fit 하면 직전 결과에 맞는다(실측).
- 작품을 고르면 **작품 상세**(포스터·출연·별칭·촬영지 목록)로 드릴다운한다. 검색
  상태는 건드리지 않으므로 뒤로 가면 고르기 전 화면 그대로다.
- 자동완성은 검색창 바로 아래 붙는 드롭다운이고, 뜨는 동안 지도는 스크림으로
  덮인다(밖을 누르면 닫힘). 첫 작품은 포스터 카드로(누르면 작품 상세로 직행),
  장소는 장소 절로, 전체를 연관 검색어 칩으로 배치한다. 별칭으로 걸리면
  (`matchedTerm`) 그 표기를 배지로 보여 준다. **장소 제안을 누르면 장소 탭으로
  넘어가 그 장소를 바로 선택·확대한다** — 검색어 커밋만 하고 작품 탭에 남아
  있으면 고른 것과 무관한 작품이 보인다.

Flutter 프로토타입(`~/workspace/mobile`, 저장소 밖)이 화면 동작의 대조군이다 —
코드는 옮기지 않고 규칙만 가져온다 (ADR 0002).

## 인터페이스

| 항목 | 값 |
| --- | --- |
| 프로토콜 | 해당 없음 (클라이언트 앱) |
| 산출물 | `:bin` — 시뮬레이터/기기에 설치하는 .app 번들 |
| 계약 | `contracts/openapi/scene-api-v1.yaml` — swift5 생성 클라이언트로 소비한다 (생성 타깃은 아직 없음, 계획서 §5-5) |

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| `services/scene-api` | 직접 import 가 아니라 계약(`contracts/openapi/`)을 통해 |

## 빌드가 되는 조건

- **macOS + 정식 Xcode 만.** 모든 타깃에 `tags = ["ios"]` 가 달려 있고 `.bazelrc` 가
  리눅스에서만 그 태그를 걸러내므로 리눅스 러너는 이 모듈을 건너뛴다. **새 타깃을
  추가하면 `tags = ["ios"]` 도 함께 단다** — 상세와 실측 근거는 `BUILD.bazel` 머리말
  과 계획서 §5-3.
- `.swift` 파일이 있으므로 `just check` 가 `swiftformat`·`swiftlint` 를 요구한다 —
  없으면 게이트가 빨간불이다. `brew install swiftformat swiftlint`.
- 규칙 설정 파일(`.swiftlint.yml`·`.swiftformat`)은 아직 만들지 않았다 — 화면을 몇 개
  옮긴 뒤 실제로 나온 경고를 근거로 쓴다 (계획서 §6).

## 명령

```bash
just build-module apps/scenetrip-ios    # 빌드
just ios-run                            # 시뮬레이터에 띄운다
just ios-xcode                          # Xcode 로 연다 (코드 탐색·디버깅·실기기)
```

`just ios-xcode` 는 `SceneTrip.xcodeproj` 를 만들고 연다. **그 파일은 생성물이라
커밋하지 않는다** — 사람마다 서명 설정과 기기 목록이 달라 서로 덮어쓴다. 정본은
`BUILD.bazel` 의 `xcodeproj` 타깃이다.

프로젝트를 열어도 **실제 빌드는 그 안에서 Bazel 이 한다.** 빌드 경로가 둘로 갈라지지
않는다 (ADR 0001 제1법칙).

> **네이버 지도 키 주의** — Xcode 가 부르는 빌드에는 `just ios-run` 의 `--define` 이
> 닿지 않는다. `.bazelrc.user` 에 아래 한 줄을 넣어야 지도가 뜬다. 그 파일은
> gitignore 대상이라 키가 저장소에 들어가지 않는다.
>
> ```
> build --define=naver_client_id=<발급받은 키>
> ```

## 실기기에 올리기

**시뮬레이터가 아니라 진짜 아이폰에서 보려면** 애플 서명이 필요하다. 서명 파일은
사람마다·기기마다 다르고 무료 애플 ID 로 만들면 **7일마다 새로 발급된다.** 저장소에
넣을 수 있는 물건이 아니라서 **기본으로 켜 두지 않았다** — 켜 두면 아이폰이 없는
팀원의 빌드까지 깨진다.

아이폰을 가진 사람이 자기 기계에서 한 번 켜면 된다.

### 1. 애플 ID 를 Xcode 에 등록한다

`Xcode → Settings → Accounts → +` 에서 애플 ID 를 넣는다. **유료 개발자 계정이 아니어도
된다.** 무료 계정으로도 자기 기기에는 설치할 수 있다.

### 2. BUILD.bazel 에서 device 를 켠다

`apps/scenetrip-ios/BUILD.bazel` 의 `xcodeproj` 타깃에서 한 줄을 고친다.

```python
target_environments = ["simulator"],
# ↓
target_environments = ["device", "simulator"],
```

그리고 `ios_application` 에 서명을 붙인다. `local_provisioning_profile` 이 **내 기계에
깔린 프로파일을 찾아 준다** — 경로를 손으로 적지 않아도 된다.

```python
load("@rules_apple//apple:apple.bzl", "local_provisioning_profile")
load("@rules_xcodeproj//xcodeproj:defs.bzl", "xcode_provisioning_profile")

local_provisioning_profile(
    name = "local_profile",
    profile_name = "iOS Team Provisioning Profile: com.mz2az.scenetrip",
    tags = ["ios", "manual"],
)

xcode_provisioning_profile(
    name = "provisioning_profile",
    managed_by_xcode = True,          # 서명은 Xcode 가 한다
    provisioning_profile = ":local_profile",
    tags = ["ios", "manual"],
)
```

`ios_application(name = "bin", ...)` 에 `provisioning_profile = ":provisioning_profile"`
를 더한다.

> **이 변경은 커밋하지 않는다.** 프로파일 이름이 사람마다 다르다. 팀 전원이 아이폰으로
> 확인하게 되면 그때 `--define` 으로 이름을 받는 형태로 정리한다.

### 3. Xcode 에서 실행한다

```
just ios-xcode
→ 아이폰을 USB 로 연결 (한 번 연결하면 이후 같은 와이파이에서 무선으로도 된다)
→ Xcode 상단에서 기기를 내 아이폰으로 선택 → Run
→ 아이폰: 설정 → 일반 → VPN 및 기기 관리 → 개발자 앱 신뢰
```

### 알아 둘 것

| 항목 | 내용 |
| --- | --- |
| iOS 버전 | 이 앱은 **iOS 17 이상**이다 (`minimum_os_version`) |
| 무료 계정 만료 | **7일 뒤 앱이 안 열린다.** 다시 설치하면 된다 |
| 무료 계정 앱 수 | 기기당 3개까지 |
| 남에게 보내기 | 무료 계정으로는 **불가능**하다. 링크로 배포하려면 유료 개발자 계정($99/년)과 TestFlight 이 필요하다 |

`Daily Todo.md` 의 「개발자 등록」이 그 유료 계정 얘기다. 멘토·심사위원에게 앱을
돌려야 하는 시점이 오면 그때 필요하다.

## 설정

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |

네이버 지도 클라이언트 ID 는 지도 SDK 연동(MZ2AZ-161) 때 빌드 시점 주입으로 붙는다 —
소스에 박지 않는다. 시크릿은 시크릿 매니저에서 온다.

## 운영

스토어 배포 없음 — MZ2AZ-148 의 완료 조건이 "팀원이 로컬에서 실행"까지다.
