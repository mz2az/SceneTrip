# 웹 — 구글 원탭 로그인 (MZ2AZ-565)

- **상태**: 계획(2026-11-13). dev 먼저, 확인 뒤 main.
- **왜**: 11/13 팀 논의 — 「처음 들어오자마자 가입부터」 의견. 강제 로그인 벽은 광고로 온 사람을 첫 화면에서 잃고, 발표용 iOS 웹뷰 껍데기가 App Store 심사 지침 5.1.1(계정이 필요 없는 기능에 로그인 강요 금지)에 걸린다. 대신 **막지 않고** 브라우저에 구글이 로그인돼 있으면 한 번 눌러 가입되는 구글 원탭(Google Identity Services, GIS)을 붙인다. 가입 권유 알약([web-signup-nudge.md](web-signup-nudge.md))과 함께 쓴다.

## 1. 언제 띄우나 — 규칙 (테스트는 §1~§3 만 보고 쓴다)

순수 함수 `oneTapAllowed(ctx)` 가 참일 때만 GIS 를 불러 `prompt()` 한다. `ctx`:

| 칸 | 뜻 |
| --- | --- |
| `signedIn` | 로그인 상태 |
| `clientId` | 웹 구글 클라이언트 ID(`VITE_GOOGLE_CLIENT_ID`), 없으면 `null` |
| `webShell` | iOS 웹뷰 껍데기 안인가(`isWebShell()` — 구글이 웹뷰 OAuth 를 막는다) |
| `onHome` | 홈 탭이고 덮개가 없다 |
| `promptedThisSession` | 이번 세션(페이지를 새로 연 한 번)에 이미 띄웠나 |

참 조건 — 모두: `!signedIn`, `clientId !== null`, `!webShell`, `onHome`, `!promptedThisSession`.

- 홈이 처음 보이고 **1.5 초 뒤** 띄운다(첫 화면을 먼저 보게). 그 사이 홈을 떠나거나 로그인하면 띄우지 않는다.
- 사람이 닫으면 다시 띄울 때까지 쉬는 기간은 **구글이 정한다**(GIS 의 기본 쿨다운). 우리는 세션당 한 번만 부른다.
- 로그인 시트가 열려 있거나 로그인이 진행 중(`busy`)이면 띄우지 않는다 — 이것도 `oneTapAllowed` 의 칸 `busy`(참이면 거짓)로 넣는다.

## 2. 흐름

1. `randomToken()`(oauth.ts)으로 nonce 원문을 만든다.
2. `https://accounts.google.com/gsi/client` 를 한 번만 불러(이미 있으면 재사용) `google.accounts.id.initialize({ client_id, nonce, callback, auto_select: false, cancel_on_tap_outside: true, use_fedcm_for_prompt: true, context: 'signin' })` 후 `prompt()`.
3. `callback({ credential })` 이 오면 `credential`(ID 토큰)과 **nonce 원문**으로 기존 계정 동작을 부른다 — 새 동작 `accountActions.signInWithGoogleCredential(idToken, nonce)`. 내부는 팝업 로그인과 같다: 나가 있는 쓰기를 기다린 뒤(`settle`) `POST /auth/google`, 성공하면 `completeSignIn`(가입이면 `sign_up`, 아니면 `login` — method `google`), 실패는 기존 안내 문구.
4. 성공하면 웹 전용 이벤트 `one_tap_sign_in`(매개변수 `new_user` = 1·0)을 하나 더 보낸다 — 원탭으로 온 가입을 따로 센다.
5. 스크립트 불러오기 실패·`credential` 이 빈 값이면 조용히 아무것도 하지 않는다(화면에 오류 없음).
6. (검증 뒤 추가) 원탭 창이 떠 있는 동안 홈을 떠나거나 로그인했거나 로그인 시트가 열리면 `google.accounts.id.cancel()` 로 닫는다(`cancelOneTap`). 이미 로그인한 뒤 들어온 원탭 응답은 무시한다. 서버가 거절하는 등 안내 문구가 생기면 로그인 시트를 열어 그 문구를 보인다(원탭에는 안내 자리가 없다).
7. `itp_support: true` — Safari 처럼 서드파티 쿠키를 막는 브라우저용 화면. `cancel_on_tap_outside` 는 FedCM(Chrome)에서는 무시된다. Safari·인스타그램 인앱 브라우저에서 실제로 뜨는지는 실기기로 확인해야 한다.

서버는 바꾸지 않는다 — `POST /auth/google` 이 이미 웹 클라이언트 ID 를 `aud` 로 받고 nonce 를 원문으로 대조한다.

## 3. 파일

- `apps/scenetrip-web/src/features/profile/auth/oneTap.ts` — `oneTapAllowed`, GIS 불러오기·`prompt` (`startOneTap(deps)` — 스크립트 로더·`google.accounts.id`·계정 동작을 주입 가능하게).
- `apps/scenetrip-web/src/features/profile/auth/account.ts` — `signInWithGoogleCredential(idToken, nonce)`.
- `apps/scenetrip-web/src/features/profile/auth/GoogleOneTap.tsx` — 홈에서 조건을 보고 1.5 초 뒤 `startOneTap`. 그리는 것은 없다(GIS 가 자기 창을 띄운다). `RootTabs` 에 얹는다.
- `analytics/events.ts` 의 `webEvent.oneTapSignIn(newUser)`, `docs/project/plans/analytics-events.md` §9 한 줄.
- `public/_headers` CSP(보고 전용)에 `https://accounts.google.com` — script-src(`/gsi/client`), connect-src, frame-src.

## 4. 사람 손

- Google Cloud 콘솔: 웹 OAuth 클라이언트의 **승인된 JavaScript 원본**에 `https://app.scenetrip.io`, `https://dev.scenetrip.io`(그리고 로컬 `http://localhost:5173`)가 있어야 원탭이 뜬다. 없으면 GIS 가 `origin_mismatch` 로 조용히 안 띄운다.
- 개인정보 처리방침: 원탭은 비회원이 홈을 열면 구글 스크립트를 불러오므로, 누르기 전에도 구글이 접속 정보(IP·구글 쿠키)를 받는다. 기존 문구는 「구글 로그인 시」 만 말한다 — 「홈에서 구글 로그인 안내를 불러온다」 한 줄을 더할지 사용자가 정한다(코드는 바꾸지 않았다).

## 5. 하지 않는 것

- 강제 로그인 화면, Apple 원탭(없다), 자동 로그인(`auto_select`), iOS 웹뷰 껍데기 안의 원탭.
