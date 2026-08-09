# scenetrip-android

> 모듈 종류: `app` · 언어: `kotlin` · 경로: `apps/scenetrip-android`

## 목적

SceneTrip 의 Android 네이티브 앱. 첫 화면인 **작품검색 탭**(지도 + 바텀시트 + 검색)을
만든다 — 화면 구조·검색 규칙의 기준은
[검색 탭 네이티브 구현 계획](../../docs/project/plans/mobile-native-search-tab.md) §3 이다.
**iOS 와 같은 문서를 기준으로 삼는다.** 거기 없는 동작은 어느 쪽에서도 임의로 만들지
않는다 — 두 앱이 갈리는 것은 화면을 만들 때가 아니라 규칙이 한쪽에만 적혀 있을 때다.

현재는 **모듈이 서는지 확인하는 자리표시자**다. `MainActivity` 가 앱 이름만 띄운다.
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
| 계약 | `contracts/openapi/scene-api-v1.yaml` — kotlin 생성 클라이언트로 소비한다 (생성 타깃은 아직 없음) |

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| `services/scene-api` | 직접 import 가 아니라 계약(`contracts/openapi/`)을 통해 |

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
```

에뮬레이터 실행 레시피는 아직 없다. 첫 화면 작업에서 `just android-run` 을 함께
만든다 — iOS 의 `just ios-run` 과 같은 자리다.

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
