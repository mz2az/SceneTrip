# apps/ — 프론트엔드 애플리케이션

독립적으로 배포되는 사용자 대면 애플리케이션(웹·어드민·모바일) 하나당 디렉터리 하나.

```
apps/<이름>/
├── BUILD.bazel   필수
├── README.md     필수 — 목적, 대상 사용자, 사용하는 백엔드
├── src/
└── tests/
```

## 규칙

- 앱은 **API 클라이언트를 직접 작성하지 않는다.** `contracts/` 로부터 생성한다.
- 공통 UI 요소와 유틸리티는 앱끼리 복사하지 않고 `libs/ts/` 에 둔다.
- 디자인 토큰은 한 곳에서 정의하고 가져다 쓴다 — 앱마다 팔레트가 갈리지 않게.
- 시각적 회귀 테스트와 접근성 검사는 선택이 아니라 테스트 레인의 일부다.

## 명령

```bash
just new-app <이름> [언어]
just build-module apps/<이름>
just test-module  apps/<이름>
just run //apps/<이름>:dev
```
