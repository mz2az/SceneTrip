# 웹 — 새 홈(시안 A)에 빠진 분석 이벤트 채우기

- **상태**: 구현 중(2026-11-14). 화면은 바뀌지 않고 기록만 는다 → 시험 통과 뒤 main.
- **티켓**: 지라 연결이 끊겨 아직 없음 — 연결되면 만든다.
- **왜**:
  - 11/11 부터 운영 첫 화면이 시안 A(`features/proto/*`)다. 그런데 그 화면들에는 `track()` 호출이 하나도 없다.
  - 그래서 대시보드에서 「홈 다음엔 작품·장소 상세만 보이고 나머지는 거의 0」 으로 보였다. 무엇을 눌렀는지, 코스 만들기 어느 단계에서 그만뒀는지, 어디서 로그인 창에 막혔는지를 모른다.
  - 함께 PostHog 익명 방문자도 사람 프로필을 만든다(`person_profiles: 'always'`) — 「People」 에서 방문자 한 명의 이벤트 순서를 본다(사용자 결정 2026-11-14).

## 1. 새 웹 이벤트 (`webEvent`, 이름·매개변수는 GA4 규칙 — 소문자·밑줄, 값은 문자열·정수, 참거짓은 1·0)

시험은 이 절만 보고 쓴다.

| 이벤트 | 함수 | 매개변수 |
| --- | --- | --- |
| `select_home_city` (있음, 출처 값만 더함) | `webEvent.selectHomeCity(city, source)` | `source` 에 `proto_chip`(첫 화면 히어로의 도시 칩) · `proto_grid`(코스 만들기의 도시 고르기)를 더한다 |
| `home_scroll` | `webEvent.homeScroll(depth)` | `depth` = 25 · 50 · 75 · 100 (정수) |
| `home_tap` | `webEvent.homeTap(section)` | `section` = `trending_title` · `trending_all` · `today_place` · `today_save` · `community` · `my_record` · `plan_bar` |
| `plan_start` | `webEvent.planStart(entry)` | `entry` = `city_chip` · `fork_city` · `fork_works` · `fork_saved` · `plan_bar` |
| `plan_step` | `webEvent.planStep(flow, step)` | `flow` = `city` · `works`, `step` = `city` · `days` · `how` · `plan` · `picker` · `trip` · `planning` |
| `plan_failed` | `webEvent.planFailed(flow, reason)` | `flow` 같음, `reason` = `empty`(결과 0곳) · `error`(요청 실패) · `save`(저장 실패) |
| `view_post` | `webEvent.viewPost(postId)` | `post_id` |
| `like_post` | `webEvent.likePost(postId, liked)` | `post_id`, `liked`(1·0) |
| `comment_post` | `webEvent.commentPost(postId)` | `post_id` — 본문은 보내지 않는다 |
| `save_post_course` | `webEvent.savePostCourse(postId)` | `post_id` |
| `sign_in_prompt` | `webEvent.signInPrompt(reason)` | `reason` = `like` · `comment` · `save_course` · `compose` · `my_posts` · `review` · `block` · `directions` · `nudge` · `profile` · `session` · `server` · `other` |
| `coach_tour` | `webEvent.coachTour(tour, action)` | `tour` = 안내 id(예 `home-plan-trip`·`fork-options`·`editor-first`), `action` = `begin` · `complete` |

- `EventName` 에 새 이름을 더한다. 공통 26개(`appEvent`)의 개수는 그대로다.
- 이미 있는 공통 이벤트를 새로 부르는 곳:
  - `generate_plan` — 도시 AI 코스가 성공했을 때(`day_count` = 고른 날 수, `title_count` = 0).
  - (검증 뒤 바꿈) 코치 투어는 공통 `tutorial_*` 를 쓰지 않고 웹 전용 `coach_tour` 로 센다 — 안내가 4종이고 공통 정의(건너뛰기 포함)와 다르다.

## 2. 규칙 (순수 함수, 시험 대상)

### 2-1. 홈 스크롤 깊이 — `homeScrollDepths(scrollTop, clientHeight, scrollHeight, sent)` (`features/proto/homeScroll.ts`)

- 보인 비율 `seen = (scrollTop + clientHeight) / scrollHeight × 100` 이다.
  - `scrollHeight <= clientHeight` 면 100 으로 본다. 다만 그 경우에도 스크롤 이벤트가 있을 때만 부른다.
- 25 · 50 · 75 · 100 중 `seen` 이상이고 `sent` 에 없는 값을 작은 것부터 돌려준다.
- `sent` 는 세션 동안 쌓는다. 같은 깊이는 세션에 한 번만 보낸다.
- 홈 탭이 보일 때(`tab === 'home'` 이고 덮개 없음)만 센다. 홈은 숨겨져도 그대로 마운트돼 있기 때문이다.

### 2-2. 로그인 창 이유 — `openSignInFor(reason, key?)`

- 로그인 창을 여는 두 길(`promptSignIn(reason, key?)` → `openSignInFor`, `openSignInFor` 직접)이 모두 `openSignInFor` 를 지난다. 그래서 그 안에서 `sign_in_prompt` 를 보낸다.
- `key` 가 없으면 `other` 다.
- 세션이 풀려 다시 띄우는 경우(`account.ts` `sessionLost`)는 `session` 이다.
- 가입 전용 기능을 서버가 401 `SIGN_IN_REQUIRED` 로 막은 경우(`api/client.ts`)는 `server` 다(검증 뒤 바꿈).
- 이미 창이 열려 있으면 다시 보내지 않는다.

## 3. 붙이는 곳

| 이벤트 | 위치 |
| --- | --- |
| 히어로 도시 칩 | `features/proto/ProtoScreens.tsx` 칩 `onClick` → `selectHomeCity(key,'proto_chip')` + `planStart('city_chip')` |
| 도시 고르기 | `ProtoScreens.tsx` CityGrid `onPick` → `selectHomeCity(key,'proto_grid')` |
| 코스 만들기 단계 | QuestionFlow 의 단계가 바뀔 때 `planStep('city', step)`, CityPicker 진입 시 `planStep('city','picker')` |
| 도시 AI 결과 | PlanPreview — 성공 `generatePlan(days, 0)` · 0곳 `planFailed('city','empty')` · 실패 `planFailed('city','error')` · 저장 실패 `planFailed('city','save')` |
| 갈래 시트 | `ProtoFork.tsx` 세 갈래 → `planStart('fork_city'·'fork_works'·'fork_saved')` |
| 작품 AI | `ProtoPlanFlows.tsx` ProtoAIPlan — 단계 `planStep('works', 'trip'·'works'·'planning')`, 실패 `planFailed('works','error')` |
| 여행 계획 줄 | `ProtoScreens.tsx` 떠 있는 줄 → `homeTap('plan_bar')` 만. 시작은 이어서 열리는 갈래 시트의 `planStart('fork_*')` 가 센다(검증 뒤 바꿈 — 두 번 세지 않게). `entry` 의 `plan_bar` 값은 쓰지 않는다 |
| 홈 카드 | `features/home/HomeCards.tsx` 의 누르는 곳 → `homeTap(section)` |
| 홈 스크롤 | `features/home/HomeTab.tsx` 의 `.scroll-y` 에 ref, §2-1 |
| 커뮤니티 | 글 열기(`CommunityTab.tsx`) · 좋아요·댓글 성공(`protoPostSocial.tsx`) · 코스 담기 성공(`PostView.tsx`) |
| 로그인 창 | `features/profile/auth/account.ts` `openSignInFor`, `state/auth.ts` `promptSignIn`, 각 호출부에 열쇠 |
| 코치 투어 | `features/proto/CoachTour.tsx` 시작 · 마지막 단계 완료 → `coachTour(id, …)` |

## 3-1. 읽는 법

- `plan_step` 은 `plan_start` 의 `entry` 로 나눠 본다 — 도시 칩으로 들어오면 첫 단계가 `days` 라 `city` 단계가 없다.
- 뒤로 갔다 다시 들어온 단계는 다시 센다(사람 수로 보면 된다).
- PostHog People 은 「브라우저 수」다 — identify 를 하지 않아 같은 사람도 기기·브라우저마다 따로다.

## 4. 하지 않는 것

- 검색어, 본문, 이름, 좌표는 보내지 않는다(§4 개인정보 규칙).
- iOS·Android 에는 넣지 않는다(웹뷰로 간다).
- 화면 녹화, 자동 클릭 수집은 하지 않는다.
