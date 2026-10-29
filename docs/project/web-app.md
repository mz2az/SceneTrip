# 웹앱(브라우저) 이식 계획

> 상태: 진행 중 — 모듈 `apps/web/` (폴더 이름은 사용자 확정 대기, 아래 §9).
> **아직 Bazel/just 에 연결되지 않았다. 연결에는 사용자 승인이 필요하다(§8).**

## 1. 목표

iOS 앱(`apps/scenetrip-ios`)의 **모든 화면과 기능을 브라우저에서 iOS 와 거의 똑같이** 동작하게 한다. 색·간격·글꼴·모서리·그림자·문구·아이콘·전환·빈/로딩/오류 상태까지, 한국어·영어 둘 다.
Android 를 iOS 에 맞추는 작업(`docs/project/plans/android-ios-parity.md`)과 같은 기준이다 — 기준 화면은 iOS 소스와 iOS 스크린샷이다.

웹은 **독립 모듈**이다. iOS·Android 와 코드를 공유하지 않고, 공유하는 것은 `contracts/openapi/scene-api-v1.yaml` 뿐이다. iOS/Android/services/contracts/agents 는 읽기만 한다.

## 2. 기술 선택과 이유

| 항목 | 선택 | 이유 |
| --- | --- | --- |
| 빌드/개발 | Vite + React + TypeScript(strict) | 정적 산출물만 나와 호스팅이 단순하고, PWA 로 설치할 수 있다. SSR 은 불필요(로그인 뒤 개인 데이터 앱, 검색 노출 대상은 별도 랜딩 `SceneTrip_landing`). |
| 레이아웃 | 모바일 우선. 모바일 브라우저는 화면 가득, 데스크톱은 가운데 **402×874 폰 컨테이너**(iPhone 17 Pro = 비교용 iOS 스크린샷의 기준 기기) | 같은 레이아웃 코드를 iOS 값(pt = CSS px)으로 쓴다. |
| 스타일 | SwiftUI 어휘를 그대로 옮긴 작은 부품(`VStack/HStack/Txt/Icon`) + CSS 변수 토큰 | iOS 코드의 `spacing`, `padding`, `.font(.system(size:))` 를 줄 단위로 대조하기 위해서다. 디자인 토큰은 `src/design/tokens.css` 에 출처(파일:줄) 주석과 함께 둔다. |
| 라우팅 | `react-router`, 경로는 `src/app/routes.ts` 한 곳의 빌더/패턴으로만 만든다 | 탭 셋 + 덮개 둘(경로여정·마이페이지)이라는 iOS 구조(`RootTabs`·`TabRouter`)를 URL 로 옮겼다. 문자열 경로를 손으로 쓰지 않는다. |
| 서버 상태 | `@tanstack/react-query` + 작은 외부 스토어(`state/storage.ts`) | iOS 의 `ObservableObject` 스토어(CartStore·LikeStore 등)에 대응. |
| API 클라이언트 | `openapi-typescript` 로 계약에서 타입 생성 + `openapi-fetch` 로 호출. 생성물은 커밋하지 않는다(`npm run gen`) | 「손으로 쓴 API 클라이언트 금지」 규칙. 계약 파일은 읽기만 한다. |
| 아이콘 | SF Symbols 이름을 `lucide-react` 와 손 그린 SVG 로 매핑(`design/Icon.tsx`) | SF Symbols 는 Apple 플랫폼 밖에서 쓸 수 없다(라이선스). 모양 차이는 S3. |
| i18n | iOS `Localizable.strings`(en) 를 `npm run gen` 이 JSON 으로 변환, 열쇠는 한국어 원문 | iOS 와 같은 `tr("한국어 원문", …인자)` 규칙. 영어 문구를 두 벌로 관리하지 않는다. |
| 지도 | 개발 중 Leaflet + OpenStreetMap 타일(키 불필요), 출시는 **미정**(§7) | iOS 는 네이버 지도 SDK. 웹 키가 없다. |
| PWA | `manifest.webmanifest` 만. 서비스 워커는 이번 범위 밖 | 오프라인 캐시는 가이드/코스 데이터 정책이 정해진 뒤. |
| 테스트 | `vitest` — 순수 로직(재시도 규칙, 문구 형식, 온보딩 진행, 각 화면의 규칙) | iOS 의 규칙 파일(`*Rules.swift`)과 같은 이름의 테스트로 짝을 맞춘다. |

## 3. 디렉터리 구조

```
apps/web/
  package.json, package-lock.json, vite.config.ts, eslint.config.js, tsconfig.json
  scripts/        gen-api.mjs(계약→타입), sync-ios-assets.mjs(문구·그림), shot.mjs(스크린샷), paths.mjs(저장소 안 위치 한 곳)
  src/
    api/          client.ts(+재시도·안전장치), failure.ts, retry.ts, generated/(커밋 안 함)
    app/          App, AppRoot(스플래시→언어→사용법→앱), RootTabs, TabBar, routes.ts, tabRouter.ts
    design/       tokens.css, primitives.tsx, Icon.tsx, overlay.tsx(Sheet/Cover/Alert/ActionSheet), PinoMascot
    i18n/         tr() · trAt() · 언어 선택
    state/        storage.ts(localStorage·작은 스토어), install.ts(X-Install-Id), auth.ts
    features/     onboarding · home · search · community · profile · route · reviews · moderation
```

폴더 이름을 바꿀 때: `scripts/paths.mjs` 는 `apps/<이름>/` 두 단계 위를 저장소 루트로 계산하므로 `git mv apps/web apps/scenetrip-web` 만으로 동작한다. 문서의 경로 표기와 아래 §8 의 Bazel 라벨만 고친다.

## 4. 화면 체크리스트

상태: ⬜ 안 함 · 🟨 진행 중 · ✅ 구현·비교 완료. **유사도**는 §6 의 방법으로 화면마다 매긴다(비교하지 못한 화면은 `—`, 「동일」이라 쓰지 않는다).
iOS 출처는 `apps/scenetrip-ios/src/` 아래 경로다.

### 온보딩/루트

| # | 화면 | iOS 출처 | 상태 | 유사도 |
| --- | --- | --- | --- | --- |
| R1 | 스플래시 | Onboarding/SplashView | 🟨 | — |
| R2 | 언어 고르기 | Onboarding/LanguagePickView | 🟨 | — |
| R3 | 사용법 4장 | Onboarding/OnboardingView | 🟨 | — |
| R4 | 탭바(검색·홈·커뮤니티) | RootTabs | 🟨 | — |
| R5 | 로그인 시트 | Auth/SignInView | ⬜ | — |
| R6 | 닉네임 시트 | Reviews/NicknameView | ⬜ | — |
| R7 | AI 동의 시트 | Models/AIConsentSheet | ⬜ | — |
| R8 | 위치 동의 시트 | Models/LocationConsentSheet | ⬜ | — |

### 홈

| # | 화면 | iOS 출처 | 상태 | 유사도 |
| --- | --- | --- | --- | --- |
| H1 | 인사 머리줄 + 프로필 버튼 | HomeTab/HomeCards (HomeHeader) | ⬜ | — |
| H2 | 내 여행 이어가기(카드·페이저·빈/로딩/오류) | HomeTab/HomeCards (HomeTripCard) | ⬜ | — |
| H3 | 지금 뜨는 작품 | HomeTab/HomeCards (HomeWorkShelf) | ⬜ | — |
| H4 | 오늘의 성지 | HomeTab/HomeCards (HomeTodayCard) | ⬜ | — |
| H5 | 커뮤니티 지금 | HomeTab/HomeCards | ⬜ | — |
| H6 | 내 기록 | HomeTab/HomeCards | ⬜ | — |

### 작품검색

| # | 화면 | iOS 출처 | 상태 | 유사도 |
| --- | --- | --- | --- | --- |
| S1 | 검색 탭(지도 + 바텀시트 3단) | SearchTab/SearchTabView, BottomSheet, NaverMapView | ⬜ | — |
| S2 | 작품/촬영지 목록(탭·무한 스크롤) | SearchTab/SearchTabList, Rows | ⬜ | — |
| S3 | 자동완성 패널 | SearchTab/SuggestionPanel | ⬜ | — |
| S4 | 작품 상세 | SearchTab/ContentDetailView | ⬜ | — |
| S5 | 촬영지 상세 + 장면 카드 | SearchTab/PlaceDetailView, SceneCard | ⬜ | — |
| S6 | 사진 전체 화면(확대) | SearchTab/ScenePhotoViewer, SceneZoomView | ⬜ | — |
| S7 | 장바구니 시트 | SearchTab/CartSheet | ⬜ | — |
| S8 | 주변 편의시설 시트·POI 카드 | RouteTab/RoutePoi*, PoiInfoSection | ⬜ | — |
| S9 | 리뷰 시트·작성·내 리뷰·사진첩 | Reviews/* | ⬜ | — |

### 커뮤니티

| # | 화면 | iOS 출처 | 상태 | 유사도 |
| --- | --- | --- | --- | --- |
| C1 | 커뮤니티 탭(목록) | CommunityTab/CommunityTabView | ⬜ | — |
| C2 | 글 상세 | CommunityTab/CommunityPostView, CommunityArticleView | ⬜ | — |
| C3 | 글쓰기·사진 배치 | CommunityTab/CommunityComposeView, CommunityPlacementEditor | ⬜ | — |
| C4 | 사진 보기 | CommunityTab/CommunityPhotoViewer | ⬜ | — |
| C5 | 신고·차단 | Reviews/ModerationViews, Models/Moderation | ⬜ | — |

### 마이페이지

| # | 화면 | iOS 출처 | 상태 | 유사도 |
| --- | --- | --- | --- | --- |
| P1 | 마이페이지 본문(프로필·설정·언어·약관) | ProfileTab/ProfileTabView | ⬜ | — |
| P2 | 내 코스 시트 | ProfileTab/ProfileSheets | ⬜ | — |
| P3 | 찜한 작품 | ProfileTab/ProfileSheets | ⬜ | — |
| P4 | 장바구니 | ProfileTab/ProfileSheets | ⬜ | — |
| P5 | 스탬프 | ProfileTab/ProfileStamps | ⬜ | — |
| P6 | 내 글 | ProfileTab/ProfileSheets | ⬜ | — |
| P7 | 내 후기 | Reviews/MyReviewsView | ⬜ | — |
| P8 | 차단한 사용자 | ProfileTab/BlockedUsersView | ⬜ | — |
| P9 | AI 설정·기기 데이터 삭제·릴스 소개 | ProfileTab/ProfileAISettings, ProfileDeviceDataSection, ReelsTeaserView | ⬜ | — |

### 경로여정(코스)

| # | 화면 | iOS 출처 | 상태 | 유사도 |
| --- | --- | --- | --- | --- |
| T1 | 코스 목록(펼침·삭제 밀기) | RouteTab/RouteTabView | ⬜ | — |
| T2 | 일정짜기 마법사 | RouteTab/RouteWizardView | ⬜ | — |
| T3 | 코스 편집기(일차·장소·저장) | RouteTab/RouteEditorView, RouteEditor* | ⬜ | — |
| T4 | 장소 추가 검색·주변 시트 | RouteTab/RouteSearchSheet, RouteNearbySheet | ⬜ | — |
| T5 | 여행 모드·길찾기 | RouteTab/RouteNavView, TripSession | ⬜ | — |
| T6 | 가이드 챗봇 첫 화면 | RouteTab/RouteGuideSheet, RouteGuide | ⬜ | — |

## 5. 웹에서 안 되는 것과 대체안

| iOS 기능 | 웹 | 대체 |
| --- | --- | --- |
| 푸시 알림 | Web Push 는 PWA 설치 뒤에만, 서버 발송 경로 없음 | 이번 범위 밖(iOS 도 푸시 없음 확인 필요) |
| 위치 동의 시트(CoreLocation) | 브라우저 Geolocation 권한 창이 대신 | 앱 쪽 안내 시트는 그대로 두고, 승인 뒤 `navigator.geolocation` 호출 |
| 네이버 지도 네이티브 SDK | 웹 SDK 는 별도 키·도메인 등록 | §7 |
| Apple/Google 로그인 | 웹용 OAuth 클라이언트 ID·리다이렉트 등록이 필요 | 화면과 흐름은 구현, 키가 없으면 「준비 중」 |
| 앱 내 결제·스토어 정책(ATT 등) | 해당 없음 | ATT/광고 추적 항목은 웹에서 숨김 |
| 토큰 보관(Keychain) | localStorage — XSS 위험이 더 크다 | HttpOnly 쿠키 세션 지원 여부는 서버 결정 사항 |
| 사진 선택·카메라 | `<input type=file accept=image/*>` | 동작은 같고 모양만 다름 |
| 길안내(여행 중 `/navigation/next-leg`) | 구현은 하되 검증에서 실행하지 않는다(유료) | 외부 지도 앱 링크 |

## 6. 유사도 측정 방법

iOS 스크린샷(`/private/tmp/claude-501/e2e/data-filled/ios-*.png`, `…/audit/*-ios.png`, 1206×2622 = 402×874pt @3x)과 웹 390/402px 화면을 나란히 놓고 차이를 목록화한다. 화면마다 100점에서
**S1 기능·문구 어긋남 −30, S2 눈에 띄는 모양 차이 −10, S3 미세 −3** 을 뺀다. 평균을 보고에 같이 적는다. 비교하지 못한 화면은 평균에서 빼고 따로 적는다.
상태바·홈 인디케이터는 브라우저에 없으므로 차이에서 제외한다.

## 7. 출시에 필요한 결정 (사용자)

1. **호스팅** — 정적 호스팅(S3+CloudFront 등)과 `/v1` 역프록시(같은 출처, CORS 회피)를 한 곳에서.
2. **도메인** — `scenetrip.io` DNS(예: `app.scenetrip.io`). 개인정보 `https://scenetrip.io/privacy` 는 한국어 초안만, `terms` 는 404(iOS README 기준) — 게시가 선행.
3. **지도 SDK** — 네이버/카카오/구글 웹 키. 네이버는 웹 서비스 URL 등록이 필요. OSM 타일은 상용 서비스에 쓰지 않는다.
4. **개인정보·약관 페이지** — 웹 전용 쿠키/저장소 고지.
5. **CORS** — 아래.
6. **소셜 로그인 웹 클라이언트 ID** (Apple/Google).
7. **토큰 보관 방식** — localStorage vs HttpOnly 쿠키.

### CORS 증거 (2026-10-29, DEV)

서버는 어떤 응답에도 `Access-Control-Allow-Origin` 을 내지 않는다. 브라우저의 직접 호출은 막힌다.

```
GET  https://api-dev.scenetrip.io/v1/contents?size=2   (Origin: http://localhost:5173)
  → 200, content-type: application/json, server: nginx, ratelimit-*, content-language, strict-transport-security
  → access-control-* 헤더 없음
OPTIONS 같은 경로 (Access-Control-Request-Method: GET, …-Headers: accept-language,authorization)
  → 200, content-length: 0, allow: GET, HEAD, POST, PUT, DELETE, TRACE, OPTIONS, PATCH
  → access-control-allow-origin 없음
```

서버는 고치지 않았다(권호 영역). 개발 서버는 `vite.config.ts` 의 프록시로 `/v1` 을 대신 전달한다. 배포에서도 같은 출처의 `/v1` 역프록시(CloudFront 비헤이비어/ALB 규칙)나 서버의 CORS 허용 중 하나가 필요하다.

## 8. Bazel/just 연결 제안 (승인 필요 — 이번 작업에서는 하지 않았다)

규칙(`AGENTS.md` §1)은 Bazel 이 유일한 빌드이고 `just` 가 유일한 입구다. 웹 툴체인은 **새 언어·툴체인 도입**(`CLAUDE.md` §9)이라 `MODULE.bazel`, 루트 `BUILD.bazel`, `justfile`, `tools/`, `.github/` 를 수정하지 않았고, `apps/web/` 는 자체 완결형(`npm ci && npm run dev`)이다. 승인되면 해야 할 일:

1. `MODULE.bazel` 에 `aspect_rules_js`(+`rules_nodejs`) 와 `aspect_rules_ts`, `aspect_rules_esbuild`(또는 Vite 를 `js_run_binary` 로) 추가, Node 버전을 `tools/bazel/toolchains/` 에 고정. `pnpm`/`npm` 락 → `npm_translate_lock` 으로 `package-lock.json` 을 입력으로 사용(AGENTS §4.4: 매니페스트는 Bazel 입력).
2. `apps/web/BUILD.bazel`: `:web`(번들), `:unit_test`(vitest, tag `unit`), `:lint`(eslint), `:gen`(openapi-typescript — `contracts/openapi` 를 입력으로).
3. `tools/just/dev.just` 에 `web-dev`·`web-build` 레시피, `just check` 에 `//apps/web:lint`·`:unit_test` 포함. `just gen` 에 `contracts/openapi → apps/web` 타입 생성 연결.
4. `.github/workflows` 는 `just ci` 를 부르므로 위가 되면 자동 포함.
5. 동시에 `AGENTS.md` §2 의 placement 표·`apps/README.md`(「웹 프론트엔드는 없다」 문구)를 갱신 — 이번에는 `apps/README.md` 만 고쳤다.
6. 하네스 제약: 웹 빌드는 hermetic 해야 한다(네트워크 없이). `npm_translate_lock` 이 락파일로 해결한다.

## 9. 사용자에게 남긴 결정

- 폴더 이름 `apps/web` vs `apps/scenetrip-web` (저장소 관례는 `apps/<이름>` 이고 `scenetrip-ios`/`scenetrip-android` 와 대칭이면 후자).
- 위 §7 의 항목들, §8 의 Bazel 연결 승인.
