# apps/ — 네이티브 프론트엔드 애플리케이션

독립적으로 배포되는 iOS(Swift) · Android(Kotlin) 네이티브 앱 하나당 디렉터리 하나.
웹 프론트엔드는 없다 — 이 저장소의 사용자 대면 UI 는 전부 네이티브 모바일 앱이다.

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
- 공통 UI 요소와 유틸리티는 앱끼리 복사하지 않고 iOS 는 `libs/swift/`, Android 는
  `libs/kotlin/` 에 둔다.
- 디자인 토큰은 한 곳에서 정의하고 가져다 쓴다 — 앱마다 팔레트가 갈리지 않게.
- 시각적 회귀 테스트와 접근성 검사는 선택이 아니라 테스트 레인의 일부다.
- iOS 빌드/테스트는 macOS 실행기에서만 돈다 (AGENTS.md §4.3 참고) — CI 잡을 잘못된
  플랫폼에 배정하지 않는다.

## 명령

```bash
just new-app <이름> swift    # iOS
just new-app <이름> kotlin   # Android
just build-module apps/<이름>
just test-module  apps/<이름>
just run //apps/<이름>:dev
```
