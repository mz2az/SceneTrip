# scenetrip-android

> 모듈 종류: `app` · 언어: `kotlin` · 경로: `apps/scenetrip-android`

## 목적

SceneTrip 의 Android 네이티브 앱. 첫 화면인 **작품검색 탭**(지도 + 바텀시트 + 검색)을
만든다 — 화면 구조·검색 규칙의 기준은
[검색 탭 네이티브 구현 계획](../../docs/project/plans/mobile-native-search-tab.md) §3 이다.
**iOS 와 같은 문서를 기준으로 삼는다.** 거기 없는 동작은 어느 쪽에서도 임의로 만들지
않는다 — 두 앱이 갈리는 것은 화면을 만들 때가 아니라 규칙이 한쪽에만 적혀 있을 때다.

## 화면은 Jetpack Compose 로 짓는다

**SwiftUI 와 같은 선언형이라 iOS 코드가 구조를 유지한 채 옮겨진다** — `@State` 가
`remember`, `VStack` 이 `Column` 이 된다. 뷰 + XML 로 가면 같은 화면에 RecyclerView ·
Adapter · ViewHolder · DiffUtil 이 붙어 코드가 두세 배가 되고, 그만큼 두 앱의 구조가
갈려 위의 "iOS 와 같은 문서를 기준으로" 를 지키기 어려워진다.

지도만 예외다. 네이버가 Compose 용 지도를 내놓지 않아 `MapView` 를 `AndroidView` 로
감싼다 (`searchtab/NaverMap.kt`) — iOS 가 `UIViewRepresentable` 로 하는 것과 같은 일이다.

컴파일러 플러그인이 필요하다. `//tools/bazel/kotlin:compose_compiler_plugin` 이며
**버전이 Kotlin 컴파일러와 같아야 한다.** 빠뜨리면 컴파일은 되는데 화면이 갱신되지
않는다 — 조용히 잘못 도는 쪽이라 찾기 어렵다.

## 지금 어디까지 됐나

| | 상태 |
| --- | --- |
| 지도 · 첫 진입 카메라(남한 전체) | 됨 |
| 작품 / 장소 두 탭, 첫 화면 「인기 N」 표기 | 됨 |
| 카테고리 칩 — 목록과 지도를 **같이** 좁힌다 | 됨 |
| 장바구니 담기(＋ → ✓), 작품 찜(♡ → ♥) | 화면 안에서만. 서버 없음 |
| 핀 번호 — 첫 화면 작품 탭에서만 민 핀 | 됨 |
| 자동완성 · 드릴다운 · 반경 검색 · 현위치 | 아직 |
| 시트 높이 끌기 + 지도 여백 연동 | 아직 — iOS 에서 가장 손이 많이 간 부분이라 나중에 |

**서버를 부르지 않는다.** 아래 "계약" 참고.

화면 구현은 iOS 에서 확정된 순서(§3-1 화면 구조 → §3-2 검색 범위 → §3-3 자동완성 →
§3-5 칩 → §3-6 오류 화면)를 그대로 따라간다.

`apps/scenetrip-ios` 가 먼저 만들어졌으므로 그쪽이 실질적인 대조군이다. Flutter
프로토타입(`~/workspace/mobile`, 저장소 밖)도 여전히 참고본이다 — 코드는 옮기지 않고
규칙만 가져온다 (ADR 0002).

## 인터페이스

| 항목 | 값 |
| --- | --- |
| 프로토콜 | 해당 없음 (클라이언트 앱) |
| 산출물 | `:bin` — 기기/에뮬레이터에 설치하는 APK |
| 계약 | `contracts/openapi/scene-api-v1.yaml` — `//contracts/openapi:scene_api_kotlin_lib` 로 소비한다 |

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| `services/scene-api` | 직접 import 가 아니라 계약(`contracts/openapi/`)을 통해 |
| `@maven_android//:com_naver_maps_map_sdk` | 지도. 버전은 iOS 와 같은 3.23.3 |
| Jetpack Compose (`@maven_android//:androidx_compose_*`) | 화면. 버전 못은 `MODULE.bazel` 에 있다 |

**아직 계약 클라이언트를 쓰지 않는다.** 생성까지는 되지만 컴파일이 막혀 있다 —
생성기 7.2.0 의 코틀린 백엔드가 enum 기본값을 한정하지 않고 뱉는다
(`Lang? = ko`, `Lang.ko` 여야 함). 같은 명세로 spring·swift5 는 멀쩡하다. 근거와
선택지는 `contracts/openapi/BUILD.bazel` 의 `scene_api_kotlin_lib` 에 적어 두었고,
그 타깃의 `manual` 태그를 지우는 것이 완료 조건이다.

그때까지 화면은 `searchtab/Model.kt` 의 고정 데이터로 짓는다. 자료형 이름과 필드를
계약과 같게 맞춰 두었으므로 클라이언트가 들어오면 그 파일만 지우면 된다.
**앱이 API 클라이언트를 손으로 쓰지 않는다는 규칙**(CLAUDE.md §5)은 그대로다 —
고정 데이터는 클라이언트가 아니다.

## 빌드가 되는 조건

- **Android SDK 가 필요하다.** Bazel 이 받아오지 못하는 두 가지 중 하나다(다른 하나는
  Xcode). 설치 절차는
  [온보딩 문서](../../docs/engineering/onboarding.md)의 "Android SDK 설치" 절에 있고,
  빠졌는지는 `just doctor` 가 본다.
- 경로는 `.env` 의 `ANDROID_HOME` 에서 오고 **버전은 `MODULE.bazel` 이 고정한다**
  (api_level 36 · build-tools 36.1.0). 경로를 저장소에 박지 않는 이유는 brew 로 깐
  사람과 Android Studio 로 깐 사람이 다르기 때문이다.
- **iOS 와 달리 태그로 걸러 내지 않는다.** Android 는 리눅스에서 지어지므로 기존
  ubuntu `verify` 잡이 그대로 검사한다. `tags = ["ios"]` 같은 것을 붙이면 오히려 검사
  범위에서 빠진다 — 계획서 §5-2.
- `.kt` 파일이 있으므로 `just check` 가 `ktlint` 를 요구한다 — 없으면 게이트가
  빨간불이다. `brew install ktlint`.

## 명령

```bash
just build-module apps/scenetrip-android    # 빌드
just android-run                            # 에뮬레이터에 띄운다
```

`just android-run` 은 AVD 가 없으면 만들고, 에뮬레이터가 꺼져 있으면 부팅을 기다린
뒤 설치·실행한다. 끝나도 에뮬레이터는 살아 있어서, 다시 부르면 빌드·설치만 한다.

`just ios-run` 이 `bazel run` 한 줄인 것과 달리 스크립트를 거친다 — `android_binary`
는 APK 만 내놓을 뿐 설치·실행을 하지 않기 때문이다. 이유는
`tools/scripts/android-run.sh` 머리말에 적혀 있다.

**에뮬레이터는 별도 패키지다.** 빌드에 필요한 platform·build-tools 만으로는 뜨지
않는다. 없으면 스크립트가 받을 명령을 알려주고 멈춘다 (약 1.5GB, onboarding.md 참고).

## 설정

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |
| `ANDROID_HOME` | 예 | 없음 | Android SDK 경로. `.env` 에 적는다 |

네이버 지도 클라이언트 ID 는 지도 SDK 연동 때 빌드 시점 주입으로 붙는다 — 소스에
박지 않는다. iOS 는 `.env` → `--define` → 생성 파일 경로를 쓴다(`apps/scenetrip-ios`
참고). Android 도 같은 방식을 따른다.

## 운영

스토어 배포는 이 계획의 범위 밖이다 (MZ2AZ-148 완료 조건은 "로컬 실행"까지).
`deploy/` 는 서명·fastlane 설정이 생길 때 채운다 — k8s 매니페스트가 아니다
(AGENTS.md §3).
