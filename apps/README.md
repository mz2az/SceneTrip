# apps/ — 네이티브 프론트엔드 애플리케이션

독립적으로 배포되는 iOS(Swift) · Android(Kotlin) 네이티브 앱 하나당 디렉터리 하나.
웹 프론트엔드는 없다 — 이 저장소의 사용자 대면 UI 는 전부 네이티브 모바일 앱이다.
크로스 플랫폼 프레임워크를 쓰지 않으므로 두 앱은 코드를 공유하지 않는 별개의 모듈이다.

| 앱 | 언어 | 공유 라이브러리 |
| --- | --- | --- |
| [`scenetrip-ios/`](./scenetrip-ios/) | Swift | `libs/swift/` |
| Android (예정 — `scenetrip-android/`) | Kotlin | `libs/kotlin/` |

```
apps/<이름>/
├── BUILD.bazel   필수
├── README.md     필수 — 목적, 대상 사용자, 사용하는 백엔드
├── src/
├── tests/
└── deploy/       스토어 제출 설정 (fastlane, 서명/프로비저닝 — k8s 아님)
```

## 규칙

- 앱은 **API 클라이언트를 직접 작성하지 않는다.** `contracts/` 로부터 생성한다.
- **iOS 와 Android 는 서로의 코드를 공유하지 않는다.** 두 앱이 같은 규칙을 지켜야
  한다면 공유하는 것은 코드가 아니라 `contracts/` 의 정의다. 화면 흐름이나 검증
  규칙이 두 앱에서 어긋나는 것을 막는 방법은 계약 테스트(`tests/contract/`)다.
- 플랫폼 안에서의 중복은 승격한다 — iOS 는 `libs/swift/`, Android 는 `libs/kotlin/`.
- 디자인 토큰은 한 곳에서 정의하고 가져다 쓴다 — 앱마다 팔레트가 갈리지 않게.
- 시각적 회귀 테스트와 접근성 검사는 선택이 아니라 테스트 레인의 일부다.
- iOS 빌드/테스트는 macOS 실행기에서만 돈다 (AGENTS.md §4.3 참고) — CI 잡을 잘못된
  플랫폼에 배정하지 않는다.

## 빌드 환경 주의

- **iOS 빌드는 정식 Xcode 앱이 설치된 macOS 에서만 된다.** Command Line Tools 만으로는
  안 된다. Apple 관련 Bazel 규칙(`rules_apple`·`rules_swift`·`apple_support`)은
  scenetrip-ios 와 함께 `MODULE.bazel` 에 활성화됐다 — 이 시점부터 **저장소를 빌드하는
  모든 macOS 장비에 정식 Xcode 가 필요하다.** 없으면 iOS 와 무관한 타깃의 빌드까지 깨진다.
- **Android 빌드는 Android SDK 가 필요하다.** 마찬가지로 첫 Android 모듈과 함께
  `MODULE.bazel` 에 SDK 위치를 알려주는 확장을 추가한다.

## 명령

```bash
just new-app-ios     <이름>      # Swift  iOS 앱 생성
just new-app-android <이름>      # Kotlin Android 앱 생성
just build-module apps/<이름>
just test-module  apps/<이름>
```
