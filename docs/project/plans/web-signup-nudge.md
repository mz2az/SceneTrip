# 웹 — 비회원에게 가입을 권하는 떠 있는 버튼 (MZ2AZ-564)

- **상태**: 계획(2026-11-13). 새 기능이라 **dev 에 먼저** 올려 dev.scenetrip.io 에서 확인한 뒤 main 으로 간다.
- **왜**: 11/11 정문창 멘토 — 「둘러보고 나가게 하지 말고 가입까지. 플로팅으로 로그인하면 뭐가 좋은지 알려라」. 지금 웹은 비회원도 찜·장바구니·코스를 다 쓸 수 있고, 로그인 시트는 길찾기·글쓰기 같은 가입 전용 기능을 누를 때만 뜬다 — 그 전에 가입의 이점을 말하는 곳이 없다.

## 1. 모양과 자리

- **홈 탭에만** 보인다. 검색 탭은 지도 위 목록 시트가 화면 아래를 차지해 알약이 목록을 가리므로 띄우지 않는다. 커뮤니티 탭·덮개(코스·마이페이지·지금 뜨는 작품 전체)가 떠 있을 때도 안 보인다.
- 탭바 바로 위에 가운데 정렬로 뜨는 알약(둥근 모서리, 강조색 배경, 흰 글자). 좌우 16px 여백, 최대 폭 420px. 글은 두 줄까지.
- 오른쪽에 닫기(✕) 단추 — 누르는 자리 최소 44×44. 접근성 라벨 「닫기」/「Dismiss」.
- 알약(✕ 제외)을 누르면 기존 로그인 시트가 열린다(`accountActions.openSignIn` — 기본 설명 「담아 둔 장바구니·코스·찜이 계정에 저장돼요…」).
- 「어디로 가세요?」 시안(로컬 개발 서버 전용, `protoOn`)이 켜져 있으면 안 보인다.

## 2. 언제 보이나 — 규칙 (테스트는 이 절과 §3·§4 만 보고 쓴다)

순수 함수 `nudgeVisible(state, ctx)` 가 참일 때만 그린다.

`state`(기기에 저장, 키 `scenetrip.signupNudge`, JSON):
- `detailViews: number` — 상세(작품·촬영지)를 연 횟수, 기본 0
- `dismissedAt: number | null` — 마지막으로 ✕ 를 누른 시각(ms), 기본 null
- `sessionsShown: number` — 알약이 보인 세션(페이지를 새로 연 한 번) 수, 기본 0

`ctx`: `signedIn`, `onHome`(홈 탭이고 덮개가 없다), `protoOn`, `savedPlaces`(장바구니 장소 수), `likedWorks`(찜한 작품 수), `now`(ms), `shownThisSession`(이번 세션에 이미 보였나).

보이는 조건 — **모두** 참:
1. `!signedIn`
2. `onHome && !protoOn`
3. `savedPlaces + likedWorks > 0` **또는** `detailViews >= 3`
4. `dismissedAt === null` 또는 `now - dismissedAt >= 3 일(259_200_000 ms)`
5. `sessionsShown < 3` **또는** `shownThisSession`(이번 세션에 이미 보이기 시작했으면 세 번째 세션 안에서는 계속 보인다)

저장 값이 깨졌거나 없으면 기본값으로 읽는다(던지지 않는다). 숫자가 아니거나 음수면 0.

## 3. 문구 — `nudgeMessage(savedPlaces, likedWorks, lang)`

| 조건 | ko | en |
| --- | --- | --- |
| `savedPlaces >= 1` | 담아 둔 {N}곳, 로그인하면 어느 기기서든 이어져요 | Sign in to keep your {N} saved spot(s) on any device — N=1 이면 `spot`, 아니면 `spots` |
| `savedPlaces == 0`, `likedWorks >= 1` | 찜한 작품 {N}개, 로그인하면 어느 기기서든 이어져요 | Sign in to keep your {N} liked title(s) on any device — N=1 이면 `title`, 아니면 `titles` |
| 둘 다 0 | 로그인하면 길찾기·후기까지 다 돼요 | Sign in for directions and reviews |

## 4. 상태 바꾸기·측정

- **상세 열기**: 작품 상세(`ContentDetail`)·촬영지 상세(`PlaceDetail`)가 열려 `view_title`·`view_place` 가 나가는 그 자리에서 `detailViews` 를 1 올린다. 로그인한 사람이면 올리지 않는다.
- **처음 보임**: 한 세션에서 알약이 처음 보이는 순간 `sessionsShown` 을 1 올리고 `signup_nudge_view`(매개변수 `trigger` = `saved`(담은 게 있음) 또는 `views`(상세 3 번)) 를 한 번 보낸다. 같은 세션에서 다시 보였다 숨었다 해도 다시 세지 않는다.
- **누름**: `signup_nudge_click`(`trigger` 같음) 보내고 로그인 시트를 연다. 알약 상태는 바꾸지 않는다(로그인하면 조건 1 로 사라진다).
- **닫기**: `dismissedAt = now`, `signup_nudge_dismiss`(`trigger` 같음) 보낸다. 알약은 바로 사라진다.
- 이벤트는 웹 전용 `webEvent` 에 더한다(공통 26개 표에 넣지 않는다). 이름·검색어·좌표·개수는 보내지 않는다 — `trigger` 하나뿐. 이용 통계를 끈 사람에게는 기존 `track()` 규칙대로 안 나간다.

## 5. 파일

- `apps/scenetrip-web/src/features/profile/auth/signUpNudgeRules.ts` — 저장 읽기/쓰기, `nudgeVisible`, `nudgeMessage`, 동작(`countDetailView`, `markShown`, `dismiss`).
- `apps/scenetrip-web/src/features/profile/auth/SignUpNudge.tsx` — 알약. `RootTabs` 가 탭바 위에 얹는다.
- `analytics/events.ts` 의 `webEvent` 에 세 이벤트, `docs/project/plans/analytics-events.md` §9 표에 세 줄.

## 6. 하지 않는 것

- 검색·커뮤니티 탭, iOS·Android(웹만).
- 가입 이벤트(`sign_up`)에 출처 매개변수 더하기 — 공통 이벤트라 그대로 둔다. 효과는 PostHog 에서 `signup_nudge_click` → `sign_up` 퍼널로 본다.
