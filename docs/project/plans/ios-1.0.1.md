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

- 의존성: `//:Package.swift` 에 `getsentry/sentry-cocoa`·`PostHog/posthog-ios`, `Package.resolved` 잠금,
  `MODULE.bazel` `use_repo` 에 두 저장소. **MODULE.bazel 변경은 사용자 승인 뒤.** sentry-cocoa 는 SPM 에서
  binaryTarget(xcframework)이다 — rules_swift_package_manager 1.23 이 Firebase 와 같은 방식으로 받는지 먼저 본다.
- 키: 네이버 키처럼 `.env` → `--define` → Info.plist 치환. 비면 꺼진다. 값은 사람이 `.env` 에 넣는다.
- 이용 통계 스위치(`UsageStatistics`): OFF 로 시작하면 초기화하지 않고, ON 이면 늦게 켜고, 켜진 뒤 OFF 면
  PostHog opt-out·Sentry close. ATT 와는 무관하다(둘 다 다른 앱·사이트를 넘나드는 추적이 아님).
- PostHog: `AppAnalytics.log` 의 같은 이벤트·매개변수를 Firebase 와 PostHog 둘로. 자동 수집·화면 자동 기록 끔.
  프로젝트는 웹과 같은 701967 — 광고·웹·앱 흐름을 한 곳에서 비교한다.
- Sentry: 충돌·오류만, `sendDefaultPii = false`, 성능 추적·세션 녹화 끔. 프로젝트는 웹(`javascript-react`)과
  따로 iOS 프로젝트를 둔다(충돌 기호화·알림이 섞이지 않게).

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
