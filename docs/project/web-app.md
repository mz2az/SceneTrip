# 웹앱(브라우저) 이식 계획

> 상태: **모든 화면 구현, 비교 가능한 30화면 평균 유사도 95.3** (2026-10-30, §4·§6; 같은 날 웹 쪽 준비 단계는 §11) — 모듈 `apps/web/` (폴더 이름은 사용자 확정 대기, 아래 §9).
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
    state/        storage.ts(localStorage·작은 스토어), install.ts(X-Install-Id), auth.ts, cart·likes·footprints·consent·shared(계정 초기화·페이징)
    shared/       photoUpload.ts — 후기·커뮤니티가 같이 쓰는 사진 줄이기·올리기(서명 주소 PUT)
    features/     onboarding · home · search · community · profile · route · reviews · moderation
                  route/editor/tripExtensions.tsx 가 편집 화면에 여행 안내·가이드 챗봇·주변 편의시설을 잇는다(editorExtensions 등록)
```

폴더 이름을 바꿀 때: `scripts/paths.mjs` 는 `apps/<이름>/` 두 단계 위를 저장소 루트로 계산하므로 `git mv apps/web apps/scenetrip-web` 만으로 동작한다. 문서의 경로 표기와 아래 §8 의 Bazel 라벨만 고친다.

## 4. 화면 체크리스트

상태: ⬜ 안 함 · 🟨 구현됨, 비교·실행을 다 하지 못함(이유는 비고) · ✅ 구현·비교 완료. **유사도**는 §6 의 방법으로 화면마다 매긴다(비교하지 못한 화면은 `비교 안 함`, 「동일」이라 쓰지 않는다).
iOS 출처는 `apps/scenetrip-ios/src/` 아래 경로다. 비교한 기준은 `/private/tmp/claude-501/e2e/audit/*-ios.png`·`…/data-filled/ios-*.png`(402×874pt) 와 웹 402×874 화면이다(상태바·홈 인디케이터는 제외, §6).

모든 화면은 라우트에 실제로 달려 있고(`npm run dev` 로 열어 확인), 오류 없이 열린다. 기능 사이를 잇던 자리표시자(`state/*`, `features/*/index.ts`, `editorExtensions`)는 전부 실제 구현으로 채워졌다.

### 온보딩/루트

| # | 화면 | iOS 출처 | 상태 | 유사도 | 비고 |
| --- | --- | --- | --- | --- | --- |
| R1 | 스플래시 | Onboarding/SplashView | 🟨 | 비교 안 함 | iOS 기준 스크린샷 없음 |
| R2 | 언어 고르기 | Onboarding/LanguagePickView | ✅ | 97 | S3: 아이콘·글꼴 미세 |
| R3 | 사용법 4장 | Onboarding/OnboardingView | ✅ | 97 | 그림·문구·점 표시 동일, 상태바 높이만 다름 |
| R4 | 탭바(검색·홈·커뮤니티) | RootTabs | ✅ | 97 | S3: SF Symbols 대신 lucide |
| R5 | 로그인 시트 | Auth/SignInView | 🟨 | 비교 안 함 | 웹용 Apple/Google 클라이언트 ID 가 없어 누르면 「준비 중」. 로그인은 실행하지 않았다(금지) |
| R6 | 닉네임 시트 | Reviews/NicknameView | 🟨 | 비교 안 함 | 계정이 있어야 열린다 — 실행 못 함 |
| R7 | AI 동의 시트 | Models/AIConsentSheet | 🟨 | 비교 안 함 | iOS 기준 없음(같은 부품인 위치 동의 시트는 R8 에서 비교) |
| R8 | 위치 동의 시트 | Models/LocationConsentSheet | ✅ | 97 | |

### 홈

| # | 화면 | iOS 출처 | 상태 | 유사도 | 비고 |
| --- | --- | --- | --- | --- | --- |
| H1 | 인사 머리줄 + 프로필 버튼 | HomeTab/HomeCards (HomeHeader) | ✅ | 97 | |
| H2 | 내 여행 이어가기(카드·페이저·빈/로딩/오류) | HomeTab/HomeCards (HomeTripCard) | ✅ | 97 | 「이어서 길찾기」는 코스를 열자마자 안내를 켠다(`pendingStore.tripStart`) |
| H3 | 지금 뜨는 작품 | HomeTab/HomeCards (HomeWorkShelf) | ✅ | 97 | |
| H4 | 오늘의 성지 | HomeTab/HomeCards (HomeTodayCard) | ✅ | 97 | |
| H5 | 커뮤니티 지금 | HomeTab/HomeCards | ✅ | 97 | |
| H6 | 내 기록 | HomeTab/HomeCards | ✅ | 97 | |

### 작품검색

| # | 화면 | iOS 출처 | 상태 | 유사도 | 비고 |
| --- | --- | --- | --- | --- | --- |
| S1 | 검색 탭(지도 + 바텀시트 3단) | SearchTab/SearchTabView, BottomSheet, NaverMapView | ✅ | 87 | S2: 지도가 OSM 타일(iOS 는 네이버 지도) |
| S2 | 작품/촬영지 목록(탭·무한 스크롤) | SearchTab/SearchTabList, Rows | ✅ | 97 | |
| S3 | 자동완성 패널 | SearchTab/SuggestionPanel | ✅ | 97 | |
| S4 | 작품 상세 | SearchTab/ContentDetailView | ✅ | 87 | S2: 위쪽에 보이는 지도 |
| S5 | 촬영지 상세 + 장면 카드 | SearchTab/PlaceDetailView, SceneCard | ✅ | 87 | S2: 지도 |
| S6 | 사진 전체 화면(확대) | SearchTab/ScenePhotoViewer, SceneZoomView | ✅ | 97 | |
| S7 | 장바구니 시트 | SearchTab/CartSheet | ✅ | 97 | |
| S8 | 주변 편의시설 점·칩·정보 카드 | RouteTab/RoutePoi*, PoiInfoSection | 🟨 | 비교 안 함 | 칩·점은 iOS 편집 화면 스크린샷과 같게 나온다. 정보 카드(`PlaceCard`)는 iOS 기준 스크린샷이 없어 비교 못 함 |
| S9 | 리뷰 시트·작성·내 리뷰·사진첩 | Reviews/* | 🟨 | 97 (시트) | 리뷰 시트만 비교. 작성·고치기·지우기는 계정·쓰기가 필요해 실행 못 함 |

### 커뮤니티

| # | 화면 | iOS 출처 | 상태 | 유사도 | 비고 |
| --- | --- | --- | --- | --- | --- |
| C1 | 커뮤니티 탭(목록) | CommunityTab/CommunityTabView | ✅ | 97 | |
| C2 | 글 상세 | CommunityTab/CommunityPostView, CommunityArticleView | ✅ | 97 | |
| C3 | 글쓰기·사진 배치 | CommunityTab/CommunityComposeView, CommunityPlacementEditor | 🟨 | 비교 안 함 | iOS 기준 없음. 사진 올리기는 버킷 CORS 가 열려야 한다(§7) — 실행 못 함. 시험은 가짜 업로더 |
| C4 | 사진 보기 | CommunityTab/CommunityPhotoViewer | 🟨 | 비교 안 함 | iOS 기준 없음 |
| C5 | 신고·차단 | Reviews/ModerationViews, Models/Moderation | 🟨 | 비교 안 함 | 쓰기라 실행 못 함, 단위·컴포넌트 시험만 |

### 마이페이지

| # | 화면 | iOS 출처 | 상태 | 유사도 | 비고 |
| --- | --- | --- | --- | --- | --- |
| P1 | 마이페이지 본문(프로필·설정·언어·약관) | ProfileTab/ProfileTabView | ✅ | 97 | 「광고 추적 허용」 줄은 웹에 없다(ATT 해당 없음, §5) — 차이에서 제외 |
| P2 | 내 코스 시트 | ProfileTab/ProfileSheets | ✅ | 97 | |
| P3 | 찜한 작품 | ProfileTab/ProfileSheets | ✅ | 97 | |
| P4 | 장바구니 | ProfileTab/ProfileSheets | ✅ | 97 | |
| P5 | 스탬프 | ProfileTab/ProfileStamps | 🟨 | 비교 안 함 | 빈 상태(본문의 스탬프 줄)만 P1 에서 비교 |
| P6 | 내 글 | ProfileTab/ProfileSheets | ✅ | 97 | |
| P7 | 내 후기 | Reviews/MyReviewsView | 🟨 | 비교 안 함 | 계정이 있어야 한다 |
| P8 | 차단한 사용자 | ProfileTab/BlockedUsersView | 🟨 | 비교 안 함 | 계정이 있어야 한다 |
| P9 | AI 설정·기기 데이터 삭제·릴스 소개 | ProfileTab/ProfileAISettings, ProfileDeviceDataSection, ReelsTeaserView | ✅ | 97 | 설정·동의 시트·삭제 확인창만 비교. 릴스 소개는 비교 안 함 |

### 경로여정(코스)

| # | 화면 | iOS 출처 | 상태 | 유사도 | 비고 |
| --- | --- | --- | --- | --- | --- |
| T1 | 코스 목록(펼침·삭제 밀기) | RouteTab/RouteTabView | ✅ | 97 | |
| T2 | 일정짜기 마법사 | RouteTab/RouteWizardView | ✅ | 97 | 직접 짜기 2단계·AI 5단계 첫 화면 비교. AI 로 짜기 실행(`/guide/plan`)은 유료라 안 함 |
| T3 | 코스 편집기(일차·장소·저장) | RouteTab/RouteEditorView, RouteEditor* | ✅ | 87 | S2: 지도(OSM) |
| T4 | 장소 추가 검색·주변 시트 | RouteTab/RouteSearchSheet, RouteNearbySheet | ✅ | 87 | S2: 뒤의 지도 |
| T5 | 여행 모드·길찾기 | RouteTab/RouteNavView, TripSession | 🟨 | 비교 안 함 | 안내 띠·도착 알림·스탬프·아래 단추 줄·카카오 위치 띠·경로선·방문 기록까지 편집 화면에 이었고(`tripExtensions.tsx`) 가짜 서버로 시험했다. **실제 길찾기(`/navigation/next-leg`, 유료)는 실행하지 않았다.** 코스 시작·방문 기록은 서버 쓰기라 실행 못 함 |
| T6 | 가이드 챗봇 | RouteTab/RouteGuideSheet, RouteGuide | 🟨 | 비교 안 함 | 해태 동그라미(끌어 옮기기)·오른쪽 서랍·시작 문구·입력창이 열리는 것까지 확인. **질문을 보내지는 않았다(`/guide/chat`, 유료).** 답의 명령(코스 갈아 끼우기·일차 열기·장소 카드 등)은 시험으로 확인 |

### 점수 요약

**독립 감사(webaudit1, 2026-10-30)**: iOS 심사 제출본과 24화면을 다시 짝지어 잰 결과 **엄격 79.6 / 조정 82.5** 이다. 아래 이전 자체 측정 95.3 은 재현되지 않았다. 원인은 전역 6개(줄 높이 1.25, squircle 모서리, 시스템 색, 헤어라인 두께, 시트 딤·위치, 한국어 줄바꿈)였다.

**수정 후 웹 쪽 자체 재측정(독립 재감사 전, 2026-10-30)**: 전역 6개와 S1 3건(로그인 시트 「Apple로 계속하기」, 내 리뷰 편의시설 줄, 갈 수 없는 곳 안내) 및 보고서의 S2 항목을 고쳤다. iOS 캡처와 같은 배율로 띠(잉크 행·열) 위치를 재면 대표 화면 대부분이 ±1~2pt 이내다(홈 상단 −0.7~−2.3, 커뮤니티 목록 행 간격 118.0 = iOS, 글 상세 ±1, 작품·촬영지 상세 ±1.3, 로그인 시트 제목·단추 ±1, 경고창 상자 171.3 = iOS). 남은 알려진 차이는 지도 타일·SDK 컨트롤, SF Symbols 대 lucide 아이콘 모양, 시트 바탕 색(유리 비침) 2~3, 줄 높이 잔차 ±2pt 이다. 이 숫자는 웹 쪽 추정이며 **독립 재감사로 확인한 점수가 아니다.** 못 고친 것: 촬영지 리뷰 줄이 목록 위 풀스크린으로 열리는 동작(P7-2, 웹은 검색 탭으로 이동), 긴 닉네임 0.65배 축소(R6-1) 등 §7 코드 근거표의 S2 일부, 탭바 해태 광채(N-06).

(이전) 비교한 30화면(R2·R3·R4·R8, H1~H6, S1~S7·S9(시트), C1·C2, P1~P4·P6·P9, T1~T4) 자체 측정 평균 95.3. 점수를 깎은 것은 두 가지뿐이다 — 아이콘 모양 S3 −3(전 화면), 지도 타일 S2 −10(지도가 같이 보이는 S1·S4·S5·T3·T4 다섯 화면). 비교 안 함 13개(R1·R5~R7, S8, C3~C5, P5·P7·P8, T5·T6)는 평균에서 뺐다.

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
상태바·홈 인디케이터는 브라우저에 없으므로 차이에서 제외한다(그 때문에 웹 화면의 위쪽 내용이 iOS 보다 약 54px 위에서 시작한다 — `--safe-top` 은 0).

2026-10-30 비교 방법: iOS 스크린샷과 같은 상태(같은 시트·같은 언어)를 웹에서 만들어 402×874 로 찍어 나란히 놓았다(`scripts/shot.mjs` 또는 puppeteer 러너, 스크린샷은 `/private/tmp/claude-501/web-port/final/`). 데이터가 다른 것(내 코스 유무, 서버 글)은 차이로 세지 않았다. 문구는 한국어·영어 둘 다 iOS `Localizable.strings` 에서 만든 표를 쓰고, 코드의 `tr("…")` 열쇠가 영어 표에 다 있는지는 시험이 지킨다(`src/i18n/index.test.ts`).

## 7. 출시에 필요한 결정 (사용자)

1. **호스팅** — 정적 호스팅(S3+CloudFront 등)과 `/v1` 역프록시(같은 출처, CORS 회피)를 한 곳에서.
2. **도메인** — `scenetrip.io` DNS(예: `app.scenetrip.io`). 개인정보 `https://scenetrip.io/privacy` 는 한국어 초안만, `terms` 는 404(iOS README 기준) — 게시가 선행.
3. **지도 SDK** — 네이버/카카오/구글 웹 키. 네이버는 웹 서비스 URL 등록이 필요. OSM 타일은 상용 서비스에 쓰지 않는다.
4. **개인정보·약관 페이지** — 웹 전용 쿠키/저장소 고지.
5. **CORS** — 아래. **사진 올리기도 같다**: 후기·커뮤니티 사진은 서명된 주소로 저장소(S3)에 브라우저가 직접 `PUT` 한다(`shared/photoUpload.ts`) — 버킷 CORS 가 이 웹 출처의 `PUT` 과 `Content-Type`·서명 헤더를 허용해야 한다. 서버(`POST /uploads`)가 서명을 줘도 버킷이 막으면 사진은 올라가지 않는다. 이번 작업에서는 쓰기를 DEV 로 보내지 않아 실제로 확인하지 못했다.
6. **소셜 로그인 웹 클라이언트 ID** (Apple/Google).
7. **토큰 보관 방식** — localStorage vs HttpOnly 쿠키.
8. **지도 키** — 지도는 개발용 OSM 타일(Leaflet)이다. 지도가 보이는 화면의 유사도를 깎는 유일한 큰 차이(−10)이고, 출시 전에 네이버/카카오/구글 웹 키로 바꿔야 한다. 길찾기 경로는 서버가 카카오로 계산해 주므로 지도 SDK 와는 별개다.
9. **DEV 서버 호출 한도** — DEV 는 IP 당 분당 약 60 회(`ratelimit-limit: 60`)다. 화면 확인을 연달아 돌리면 429 가 나고 홈이 「불러오는 중…」 에 머문다(iOS 와 같은 재시도 규칙) — 시간을 두고 다시 열면 된다.

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

## 9. 사용자에게 남긴 결정 (2026-10-30 기준)

- 폴더 이름 `apps/web` vs `apps/scenetrip-web` (저장소 관례는 `apps/<이름>` 이고 `scenetrip-ios`/`scenetrip-android` 와 대칭이면 후자).
- 위 §7 의 항목들, §8 의 Bazel 연결 승인.
- 지도 SDK/키(§7-3·8) — 정하면 `RouteMap`·`LeafletMap`·`NavMap` 세 곳의 타일 부분만 바꾸면 된다.
- 사진 올리기 버킷 CORS(§7-5), 서버 CORS 허용 또는 같은 출처 역프록시(§7).
- 호스팅·도메인(§7-1·2), 소셜 로그인 웹 클라이언트 ID(§7-6) — 없으면 로그인 시트의 두 단추는 「준비 중」.
- **이번에 하지 않은 것(승인이 필요하거나 금지라서)**: 로그인·계정·쓰기(코스 만들기·시작·방문 기록·글·후기·사진)와 유료 호출(`/guide/chat`·`/guide/plan`·`/navigation/*`)의 **실제 실행**. 로직은 가짜 서버 시험으로 확인했고, 실제 실행 확인은 허락을 받은 뒤 한 번 돌려야 한다 — 특히 코스 시작→길찾기→도착→방문 기록, 가이드 질문→코스 갈아 끼우기, 사진 올리기.

## 10. 실행과 확인

```
cd apps/web
npm ci && npm run gen && npm run dev      # http://localhost:5173 , /v1 은 개발 서버가 DEV 로 전달
npm run check                              # gen + typecheck + lint + test + vite build
```

`.env.local` 의 안전장치(`VITE_BLOCK_WRITES`·`VITE_BLOCK_PATHS=/guide,/navigation`)는 그대로 둔다. 화면 확인용 쿼리: `?dev-lang=ko|en`, `?dev-seen=1`(사용법 건너뜀), `?dev-nosplash=1`, `?dev-trip-dwell=10`(머무름 초).

2026-10-30 확인 결과: typecheck 통과, lint 통과(오류 0·경고 0), vitest 34 파일 464 시험 통과, `vite build` 통과. 모든 탭·덮개·시트 라우트(`/`, `/search`, `/search/content/:id`, `/search/place/:id`, `/community`, `/community/new`, `/courses`, `/courses/new`, `/courses/:id`, `/profile`, `/profile/*`)를 열어 페이지 오류 0 을 확인했다.

### 이번 마무리에서 고친 것

- 중단 시점에 비어 있던 이음새: `editorExtensions` 에 아무도 등록하지 않아 **가이드 챗봇·여행 안내·주변 편의시설이 편집 화면에 안 붙어 있었다** → `route/editor/tripExtensions.tsx` 로 이었다(해태 동그라미, 가이드 서랍, 안내 띠·도착 알림·스탬프·아래 단추 줄, 갈래 칩, 지도 위 점·AI 장소·경로선, 정보 카드의 리뷰 시트·사진첩, 홈 「이어서 길찾기」). 챗봇이 코스를 읽고 고치는 데 필요한 코스 ↔ 초안 변환(`guide/guidePlan.ts`)도 새로 썼다.
- 중복: 후기·커뮤니티의 사진 올리기를 `shared/photoUpload.ts` 하나로, 글쓰기의 「내가 쓴 글」 시트를 마이페이지와 같은 시트로(iOS 도 같은 `MyPostsSheet`), 공용 경고창을 iOS 26 유리 모양 하나로. 남은 중복: 밀어서 삭제 줄이 둘(`community/SwipeRow`·`route/ui/SwipeRow` — 모양이 다르다), 장바구니 시트가 셋(iOS 도 `CartSheet`·`ProfileCartSheet`·`RouteCartSheet` 세 벌이라 그대로 둠), 지도 래퍼 셋(`LeafletMap`·`RouteMap`·`NavMap`).
- 문구: `tr('… %d')` 로 적은 열쇠가 iOS 의 `%lld` 영어 문구를 못 찾던 것(코스 장소 검색의 「추가 0」), 영어 달력 요일 줄임말(SUN…SAT).
- 린트 오류 1건·경고 1건.
- 알려진 남은 차이: 지도 타일(OSM), SF Symbols 대신 lucide 아이콘, 상태바 높이, 웹에서 숨긴 「광고 추적 허용」, 코드 분할 없이 번들 하나(약 1.2 MB, gzip 386 KB — 출시 전에 라우트별로 나눌 수 있다).


## 11. 2026-10-30 웹 쪽 준비 단계 (기기 없이)

점수는 **다시 매기지 않았다** — 비교 못 한 13화면의 iOS 스크린샷이 아직 없어 평균은 그대로 95.3(30화면)이다. 이번에 한 일은 사실로만 적는다.

### 고친 것

| 화면 | 변경 | iOS 근거 |
| --- | --- | --- |
| 공용 경고창·GlassDialog(신고 차단 확인, 삭제 확인 등) | 바깥 상자 fade-in 을 `both` → `backwards`. `both` 가 opacity 애니메이션을 남겨 backdrop root 를 만들어 유리 흐림(`backdrop-filter`)이 안 먹고 뒤 글자가 비쳤다 | iOS 26 유리 경고창(`49-delete-confirm-ios.png`) |
| 가이드 서랍 장소 줄 등 한 줄 글 전반(`Txt lines=1`) | `maxWidth: 100%` — `VStack align=start` 안에서 안 줄바꿈 글이 칸 밖으로 넘쳐 「8 m」·펼침·⊕ 와 겹쳤다 | `RouteGuideSheet.swift:257~270` `.lineLimit(1)` |
| 코스 편집기 주변 편의시설 점 | 조회 범위·중심을 지도 전체가 아니라 시트가 덮지 않은 영역으로(`viewportOf(map, bottomInset)`). 전에는 가까운 30개가 시트 밑에 깔려 점이 안 보이는 경우가 있었다 | `RouteMapView.swift:259` `contentInset.bottom = panelHeight`, `RouteEditorAmbient.swift:84` |
| 내 리뷰 시트 | 「내 리뷰 고치기·지우기」 글자를 강조색으로 | `MyReviewsView.swift:128` 기본 `Button` |

### 개발 도구

- `?dev-account=1`·`?dev-stamps=1|active`(`src/devMock.ts`): 계정·방문 기록이 필요한 화면을 읽기 전용 가짜 응답으로 연다. 켜면 GET 이 아닌 요청은 서버로 나가지 않는다(204). 가짜 로그인은 저장소에 쓰지 않는다.
- `?dev-fake-guide=…` 는 전에 만들어져 있었으나 어디에도 연결되지 않았다 → `installDevFakeGuide()` 로 `sharedGuide` 에 연결.
- 개발 서버가 DEV GET 200 을 10분 메모리에 둔다(분당 60회 한도 완화).
- `scripts/e2e-smoke.mjs`: 13개 흐름(홈·탭·검색·작품/촬영지 상세·코스 목록·마법사·편집기·여행 중·가이드 서랍·커뮤니티·신고·글쓰기·마이페이지 시트·로그인/동의 시트·영어)을 훑는다. 마지막 실행(개발 서버 5273): 13/13 통과.
- `scripts/shot.mjs --until domcontentloaded`(스플래시 캡처용).

### 웹 캡처 준비(`/private/tmp/claude-501/web-port/p99/<ID>-web.png`, @2x 와 `-web-1x.png`)

R1(스플래시), R5(로그인 시트), R6(닉네임 바꾸기), R7(AI 동의), S8(+`S8-chips`), C3, C4, C5(`-menu`·`-report`·`-block`), P5, P7, P8, T5, T6(`-open`·`-answer`). 13화면 모두 준비됨. 여는 방법은 `p99` 폴더와 같은 URL 플래그 + 클릭이며 `scripts/e2e-smoke.mjs` 흐름에 같은 단계가 있다.

### 아직 비교하지 못한 것

위 13화면은 iOS 쪽 스크린샷이 없어 유사도를 매기지 못했다(§4 의 「비교 안 함」 유지).
