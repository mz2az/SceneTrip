# iOS 1.0.1 — 웹에 먼저 들어간 수정 따라잡기 (MZ2AZ-525)

iOS 1.0.0 (4)는 `920166a`(`ios-1.0.0-4`, 2026-10-16)로 만들었고 2026-11-07 App Store 에 출시됐다.
그 뒤 웹앱(2026-10-29~)과 Android 에만 들어간 수정·기능을 1.0.1 에 모은다. 1.0.1 은 main 에서 만든다
(`apps/scenetrip-ios/deploy/README.md` 「릴리스 버전과 브랜치」).

## 범위

| 순서 | 티켓 | 내용 | 기준 구현 |
| --- | --- | --- | --- |
| 1 | MZ2AZ-526 | 첫 실행의 인앱 스플래시·언어 고르기·사용법을 없애고 바로 앱. 저장된 언어가 없으면 영어. 홈 오른쪽 위 단추는 톱니바퀴 | 웹 `9d12026f` (MZ2AZ-523) |
| 2 | MZ2AZ-521 | 로그인이 저절로 풀린 비회원은 설치 번호를 새로 만든다 — 조회는 한 번 다시, 쓰기는 다시 안 보냄 | 웹 `3f0762a2` (`state/install.ts` `renewInstallId`, `api/client.ts` `renewInstall`) |
| 3 | MZ2AZ-519 | 홈 「오늘의 성지」 → 그 장소로 확대(`focusToken`), 핀 목록에 없으면 번호 없는 핀으로 꽂고 강조 | 웹 `af6eee6b` (`search/map/mapRules.ts` `pinMarks`) |
| 4 | MZ2AZ-528 | 후기 끝 References·Photo credits 를 한 줄 뒤로 접고, 사진 출처 문장은 「더 보기」 뒤로 | Android `155b0297`·`948fba34`, 웹 `d2a5b06f` (`splitReferences`) |
| 5 | MZ2AZ-527 | Sentry 오류 추적·PostHog 사용 분석, 이용 통계 스위치를 따른다 | 웹 `115ecd58`·`52e90dbb` (`src/analytics/`) |
| 6 | MZ2AZ-529 | 영어 지도·언어 바꾼 뒤 남는 한글·열어 둔 장소 이름표 겹침 — iOS 에도 있는지 확인, 재현된 것만 고침 | 웹 `c5724c98`·`577bb385` |
| — | MZ2AZ-518 | 자동 닉네임 판정 — 서버 MZ2AZ-517 배포 뒤에만. 1.0.1 을 막지 않는다 | — |

iOS 수정이 필요 없는 것: 촬영지 사진 출처(MZ2AZ-514, iOS `PhotoPager` 가 이미 `credit`·`license` 를
그린다), 장면 사진 435 곳(MZ2AZ-524, 데이터), 코스 지도 내 위치(MZ2AZ-522, 웹이 iOS `RouteMapLocate` 를
옮긴 것), 브라우저 위치 캐시 대체(`ff2f19dc`), 촬영지 상세 조회 기록 반복(React effect 문제).

## 1. 첫 실행 (MZ2AZ-526)

- `Onboarding/AppRoot.swift` 는 `RootTabs` 만 그린다. 시스템 시작 화면(LaunchScreen)은 OS 몫이라 그대로다.
- `AppLanguage`: 저장값이 없으면 기기 언어와 무관하게 `.en`. 저장값이 있으면 그대로. 권한 창 문구
  (InfoPlist.strings)는 계속 시스템 언어를 따른다 — 기존 제약.
- 사용법은 마이페이지 「사용법 다시 보기」에만 남는다. 첫 실행에서 `select_language`·`tutorial_*` 가 나가지 않는다.
- **ATT 요청 시점이 바뀐다.** 지금은 `requestIfNeeded(onboardingComplete: OnboardingFlag.hasSeen, …)` 라
  사용법을 안 거치면 영영 묻지 않는다. 앱이 활성으로 처음 보인 뒤 0.6초에 묻고, 중복·미결정·백그라운드 규칙
  (`ios-att-statistics.md`)은 그대로 둔다.

## 2. 비회원 설치 번호 (MZ2AZ-521)

- `InstallIdentity.current` 가 `static let` 이라 바꿀 수 없다 → 키체인 값을 바꿀 수 있는 저장소로.
- `SESSION_REQUIRED` 를 토큰 없는 요청이 받았고 지금도 세션이 없으면 새 번호를 만든다. 같은 낡은 번호로 거절된
  요청이 여럿이어도 한 번만. 그 사이 로그인했으면 바꾸지 않는다. GET 만 새 번호로 한 번 다시 보낸다.

## 3. Sentry·PostHog (MZ2AZ-527)

- 의존성: `//:Package.swift` 에 `getsentry/sentry-cocoa` **9.30.1**·`PostHog/posthog-ios` **3.90.2**(둘 다 `exact`,
  2026-10-07 공개), `Package.resolved` 잠금, `MODULE.bazel` `use_repo` 에 `swiftpkg_sentry_cocoa`·`swiftpkg_posthog_ios`
  (사용자 승인, `just swift-deps-update`). sentry-cocoa 의 `Sentry` 제품은 binaryTarget(xcframework) + 작은 C++
  도움 타깃이다 — rules_swift_package_manager 1.23 이 네이버 지도 SDK 와 같은 방식으로 받아 `:bin` 이 지어진다(실측).
- 키: 네이버 키와 **같은 길**이다 — `.env` 의 `SENTRY_DSN_IOS`·`POSTHOG_KEY` → just 레시피의 `--define=sentry_dsn_ios`·
  `--define=posthog_key` → genrule 이 만드는 `Secrets.swift`(`Secrets.sentryDsnIos`·`Secrets.posthogKey`).
  계획 초안의 「Info.plist 치환」은 틀렸다 — 네이버 키는 Info.plist 가 아니라 이 genrule 로 들어간다.
  `.bazelrc` 기본은 빈 값이고, 비면 그 SDK 는 한 번도 불리지 않는다. 값은 사람이 `.env` 에 넣는다.
  PostHog 주소는 미국 클라우드(`https://us.i.posthog.com`) 고정이다(웹의 `VITE_POSTHOG_HOST` 같은 덮어쓰기는 두지 않았다).
- 이용 통계 스위치(`UsageStatistics`): `AppAnalytics.setCollectionEnabled` 가 앱 시작과 토글 때 둘을 맞춘다.
  OFF 로 시작하면 초기화하지 않고, ON 이 되면 그때 켜고, 켜진 뒤 OFF 면 PostHog opt-out·Sentry close.
  다시 ON 이면 PostHog 는 opt-in(재초기화 없음), Sentry 는 다시 start. ATT 와는 무관하다.
- PostHog(`PostHogAnalytics`): `AppAnalytics.log` 의 같은 이벤트·매개변수를 Firebase 와 PostHog 둘로. 앱 생명주기·
  화면 자동 기록·누름 자동 수집·화난 연타(`$rageclick`)·요소 글자·푸시 자동 기록·swizzling·화면 녹화·설문·기능 플래그
  미리 받기·PostHog 자체 충돌 수집 끔, IDFA 없음. SDK 가 스스로 만드는 이벤트는 없고 우리 `capture` 만 간다 — 다만
  SDK 기본 속성(기기·OS·앱 버전·세션 id 등)은 그 이벤트에 붙는다. 처음에 화난 연타(기본 ON)를 빠뜨려 독립 검증에서 잡혔다.
  SDK 가 지난 실행의 opt-out 을 디스크에서 되살리므로 시작 직후 opt-in 을 한 번 부른다(통계 ON 일 때만 시작한다).
  프로젝트는 웹과 같은 701967.
- Sentry(`ErrorReporting`): 충돌·오류만, `sendDefaultPii = false`, 성능 추적·프로파일링·세션 녹화·세션 집계·
  실패한 요청 보고·브레드크럼 끔(요청 주소에 검색어·좌표가 실린다), 보내기 전 `user` 를 지운다.
  환경은 API 주소로 — `https://api.scenetrip.io/v1` 이면 `production`, 그 밖(DEV·로컬)은 `dev`.
  프로젝트는 웹(`javascript-react`)과 따로 iOS 프로젝트를 둔다(충돌 기호화·알림이 섞이지 않게).
- 시험: `tests/ThirdPartyAnalyticsTests.swift`(키 없음·OFF 시작·OFF→ON·ON→OFF→ON·같은 이름·매개변수·SDK 설정).
- 남은 것: 실제 DSN·키로 시뮬레이터·실기기에서 Sentry 이벤트와 PostHog 이벤트가 들어오는지 확인(사람이 `.env` 를 채운 뒤).
  충돌 기호화용 dSYM 업로드는 이번 범위가 아니다.

## 4. 출시

1. 위 PR 이 모두 main 에 머지되고 시뮬레이터(DEV 서버)·실기기 확인.
2. App Store Connect 앱 개인정보 라벨: 진단(충돌·성능 데이터), 사용 데이터(제품 상호작용) 추가, 추적 아님.
   처리방침(MZ2AZ-515)에 앱의 Sentry·PostHog 문단. 둘 다 사람이 한다.
3. `just ios-package <프로파일> app-store 1.0.1 5` → 태그 `ios-1.0.1-5` → `deploy/README.md` 빌드 표.
4. 업로드·심사 제출은 사람이.

1.0.0 사용자가 계속 남으므로 서버·계약은 더하기만 한다(MZ2AZ-510 규칙).

## 작업 방식

티켓마다 브랜치·PR 하나. 구현·검증은 Codex(gpt-6.1-sol, high) 서브에이전트 — 구현 세션과 다른 세션이 검증한다.
`just check`·시뮬레이터 확인(DEV `https://api-dev.scenetrip.io/v1`, 쓰기·유료 호출 없음)·PR·CI 확인·머지는 내가 한다.
