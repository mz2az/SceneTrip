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
| 장바구니 담기(＋ → ✓) | 서버 계약 호출, 설치 UUID로 비회원 계정 식별 |
| 작품 찜(♡ → ♥) | 기기 저장소 사용 |
| 핀 번호 — 첫 화면 작품 탭에서만 민 핀 | 됨 |
| 자동완성 · 작품 드릴다운 · 지도 범위 검색 · 현위치 | 구현됨 |

`SceneData`가 생성된 계약 클라이언트로 서버 검색·상세·자동완성을 호출한다.

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

계약 클라이언트는 Bazel이 생성·컴파일한다. 과거 enum 기본값 생성 오류는 생성기
7.24.0으로 해결됐으며, 현재 `data/SceneData.kt`가 그 클라이언트를 사용한다.

## 빌드가 되는 조건

- **Android SDK 가 필요하다.** Bazel 이 받아오지 못하는 두 가지 중 하나다(다른 하나는
  Xcode). 설치 절차는
  [온보딩 문서](../../docs/engineering/onboarding.md)의 "Android SDK 설치" 절에 있고,
  빠졌는지는 `just doctor` 가 본다.
- 경로는 `.env` 의 `ANDROID_HOME` 에서 오고 **버전은 `MODULE.bazel` 이 고정한다**
  (api_level 36 · build-tools 36.1.0). 경로를 저장소에 박지 않는 이유는 brew 로 깐
  사람과 Android Studio 로 깐 사람이 다르기 때문이다.
- 기존 SDK의 command-line tools가 있으면 `just android-sdk-install <SDK 경로>`로
  고정된 플랫폼·빌드 도구만 설치한다. 라이선스 동의가 없으면 자동 승인하지 않는다.
- **iOS 와 달리 태그로 걸러 내지 않는다.** Android 는 리눅스에서 지어지므로 기존
  ubuntu `verify` 잡이 그대로 검사한다. `tags = ["ios"]` 같은 것을 붙이면 오히려 검사
  범위에서 빠진다 — 계획서 §5-2.
- Kotlin 포맷은 Bazel에 고정된 `//:ktlint`로 검사한다. 별도 호스트 설치는 필요 없다.

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

API 주소의 기본값은 에뮬레이터에서 호스트를 가리키는 `http://10.0.2.2:8081/v1`이다.
DEV·PRD는 환경의 실제 HTTPS 주소를 명시한다. 아래 도메인은 예시다.

```bash
just mobile-build-cloud android https://api.example.com/v1
```

Bazel이 `scenetrip_api_base_url` 값을 검증해 `ApiConfiguration.kt`를 생성한다.
HTTP 원격 주소·인증정보·query·fragment·잘못된 포트·`/v1` 이외 경로는 빌드 단계에서
거절하므로 URL 오류로 앱이 시작 중 종료되지 않는다. 빈 값은 일반 로컬 빌드에서만
기존 기본값을 사용한다. API 주소는 공개 설정이며 모델 키·DB 자격 증명을 넣지 않는다.
명령은 APK를 빌드하며 기기 설치·서명·Play Store 배포는 수행하지 않는다.

## 운영

스토어 배포는 이 계획의 범위 밖이다 (MZ2AZ-148 완료 조건은 "로컬 실행"까지).
`deploy/` 는 서명·fastlane 설정이 생길 때 채운다 — k8s 매니페스트가 아니다
(AGENTS.md §3).
