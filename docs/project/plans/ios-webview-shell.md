# iOS 웹뷰 껍데기 실험 (MZ2AZ-557)

> 상태: **실험**(2일 시간 제한). 결정은 [ADR 0029](../../architecture/adr/0029-ios-app-as-webview-shell-experiment.md)(제안).
> 브랜치 `MZ2AZ-557-ios-webview-shell`(origin/dev 기준). 머지·스토어 제출은 이 작업 범위 밖이다.

## 1. 목표

iOS 앱을 웹앱(`app.scenetrip.io`, 시험판 `dev.scenetrip.io`)을 감싸는 얇은 `WKWebView` 껍데기로 바꿔도 되는지
2일 안에 판단한다. 바뀌면 팀은 화면 코드를 웹 하나만 유지한다.

조건:

- 스위치를 **끄면**(기본값) 지금 App Store 의 네이티브 앱(1.0.0)과 똑같이 동작한다.
- 웹 변경은 최소이고, 일반 브라우저에서는 아무것도 바뀌지 않는다.
- 토큰은 주소(URL)에 싣지 않는다.

## 2. 방법

### 2-1. 스위치 — 빌드 시점

- `.bazelrc` 에 `build --define=scenetrip_web_shell_url=`(빈 값 = 끔).
- `//apps/scenetrip-ios:web_shell_swift` genrule 이 `WebShellBuild.swift`(`enum WebShellBuild { static let url = "…" }`)를 만든다.
  `Secrets.swift` 와 같은 길이다.
- 앱은 `WebShellRules.baseURL(WebShellBuild.url)` 이 주소를 돌려줄 때만 껍데기를 띄운다. 빈 값·`https` 가 아닌 원격 주소는 끔
  (예외: 시뮬레이터 개발용 `http://localhost`·`127.0.0.1`).
- 레시피 `just ios-run-webshell <웹 주소> [앱 인자…]` — 시험판은 `https://dev.scenetrip.io`, 이 브랜치의 웹 변경을 보려면
  `just web-dev` 를 띄우고 `http://localhost:5173`.

### 2-2. 껍데기 화면 (`src/WebShell/`)

| 항목 | 처리 |
| --- | --- |
| 안전 영역 | `WKWebView` 를 화면 끝까지 깔고 `contentInsetAdjustmentBehavior = .never` — 웹이 `viewport-fit=cover` + `env(safe-area-inset-*)` 로 직접 맞춘다 |
| 뒤로 밀기 | `allowsBackForwardNavigationGestures = true` (SPA `pushState` 기록을 따른다) |
| 위치 | 앱 Info.plist 의 `NSLocationWhenInUseUsageDescription` 이 이미 있다. 웹의 `navigator.geolocation` 은 WebKit 이 그 권한 위에서 처리한다 |
| `target=_blank`·`window.open` | `WKUIDelegate.createWebViewWith` — 같은 출처면 본 창에서 열고, 아니면 바깥(Safari·해당 앱)으로 |
| 바깥 주소 | `decidePolicyFor` — `http(s)` 가 아닌 스킴(`mailto:`·`tel:`·`nmap:`·`kakaomap:`·`itms-apps:`)과 다른 호스트의 **최상위 프레임** 이동은 `UIApplication.open` 으로 넘기고 웹뷰에서는 취소. 하위 프레임·리소스는 그대로 |
| 사용자 에이전트 | `applicationNameForUserAgent` 에 `SceneTripShell/1` 을 덧붙인다(서버 로그·분석 구분용) |
| 당겨서 새로고침 | 넣지 않는다 — 지도 화면의 끌기와 부딪힌다 |

판단 규칙(주소 → 허용·바깥·취소)은 `WebShellRules` 순수 함수로 두고 단위 시험으로 고정한다.

### 2-3. 로그인 다리 (`scenetripAuth`)

웹은 브라우저에서 구글은 `accounts.google.com` 팝업, Apple 은 `AppleID.auth` JS 팝업을 쓴다. 웹뷰 안에서는 둘 다 막힌다
(구글 `disallowed_useragent`, 팝업 창 없음). 그래서 **공급자 단계만 네이티브가 하고, 서버 교환은 웹이 그대로 한다**:

```
웹 SignInSheet ─▶ accountActions.signInWithGoogle()
                  deps.google()  ── 껍데기면 ──▶ window.webkit.messageHandlers.scenetripAuth.postMessage({provider:'google'})
                                                  └ 네이티브: GoogleOAuth.signIn() (기존 코드, GIDSignIn) → {idToken, nonce}
                  ◀────────────── 응답(Promise) ──┘
                  settle() → POST /auth/google (웹의 설치 번호 헤더) → completeSignIn → 웹 토큰 저장소·갱신 로직 그대로
```

- Apple 도 같다: 네이티브 `AppleOAuth.signIn()`(ASAuthorization) → `{identityToken, authorizationCode, nonce, givenName, familyName}` → 웹이 `POST /auth/apple`.
- `WKScriptMessageHandlerWithReply` 를 쓰므로 `postMessage` 가 Promise 를 돌려준다. 토큰은 메시지 본문으로만 오가고 URL 에 실리지 않는다.
- 웹은 `window.webkit?.messageHandlers?.scenetripAuth` 가 있을 때만 이 길을 쓴다(`nativeAuth.ts`). 일반 브라우저에는 그 객체가 없다.
- 취소는 `'cancelled'` 오류 문자열 → 웹의 `Cancelled`, 그 밖은 `OAuthFailed`.

**과제 지시와 다른 점(의도적)**: 지시는 "네이티브가 서버 세션(토큰+me)까지 받아 웹에 넘긴다" 였다. 그렇게 하면 서버 교환에
**네이티브 설치 번호**(`X-Install-Id`, 키체인)가 실리는데, 비회원으로 담아 둔 데이터는 **웹의 설치 번호**(localStorage)에 붙어 있어
로그인 때 합치기(merge)가 엉뚱한 비회원 계정에서 일어난다. 공급자 자격만 넘기면 웹의 `settle`·합치기·토큰 저장·갱신·분석 이벤트가
한 줄도 바뀌지 않는다. 서버의 `aud` 허용 목록에는 iOS 구글 클라이언트 ID 와 앱 번들 ID(Apple)가 이미 들어 있다(1.0.0 이 쓰는 값).

### 2-4. 시험

- 웹: `nativeAuth.test.ts` — 껍데기 감지, 구글·Apple 응답 변환, 취소·실패 매핑, 일반 브라우저에서 기존 팝업 경로 유지.
- iOS: `WebShellRulesTests.swift` — 주소 검증(빈 값·http·localhost), 이동 판단(같은 호스트·다른 호스트·스킴·하위 프레임·`about:blank`),
  새 창 판단, 로그인 메시지 파싱·응답 직렬화.

## 3. 위험

| 위험 | 내용 | 대응·판단 |
| --- | --- | --- |
| App Store 4.2 최소 기능 | 웹사이트를 감싼 것만으로는 거절 사유. 1.0.0 은 네이티브라 "기능 후퇴" 로도 보일 수 있다 | 네이티브 로그인(Apple·구글), 위치 기반 길 안내, (후속) 푸시·오프라인 화면 등 앱다운 부분을 남겨야 한다. **가장 큰 위험** |
| 구글 OAuth 웹뷰 차단 | `accounts.google.com` 은 내장 웹뷰를 `disallowed_useragent` 로 막는다 | 네이티브 GIDSignIn(시스템 인증 창)으로 우회 — §2-3 |
| Apple JS 팝업 | 웹뷰에는 팝업 창이 없어 `AppleID.auth` 팝업이 못 뜬다. 4.8 에 따라 구글을 주면 Apple 도 줘야 한다 | 네이티브 ASAuthorization — §2-3 |
| 위치 권한 | 앱 권한 + WebKit 의 사이트별 위치 질문이 **두 번** 뜰 수 있다 | iOS 15+ 는 앱 권한이 있으면 WebKit 질문을 한 번 더 띄운다. 후속: 네이티브 위치를 JS 로 주입하는 폴리필 |
| 네이버 지도 도메인 키 | 지도 JS 는 등록된 도메인(Referer)에서만 동작 | 껍데기는 `dev.scenetrip.io`·`app.scenetrip.io` 를 그대로 열므로 Referer 가 같다. `localhost` 는 등록 여부에 따라 다르다 |
| 여행 중 안내(백그라운드) | 웹은 화면이 꺼지거나 앱이 뒤로 가면 위치·타이머가 멈춘다. 네이티브 앱은 여행 모드에서 계속 안내한다 | 웹뷰로는 같은 수준 불가. 네이티브 위치 + 알림 다리가 필요(후속) |
| 딥 링크 | 유니버설 링크(`app.scenetrip.io/...`)로 앱을 열려면 `apple-app-site-association` 과 associated domains 권한이 필요 | 실험 범위 밖. 껍데기는 받은 경로를 그대로 열 수 있게만 둔다 |
| 분석 중복 | 웹 GA4·PostHog 와 네이티브 Firebase·PostHog 가 같은 사용자를 두 번 셀 수 있다 | 껍데기에서는 네이티브 분석을 켜지 않는다(AppRoot 를 띄우지 않음) |
| 오프라인·첫 로딩 | 네트워크가 없으면 흰 화면 | 오류 화면 + 다시 시도(이 실험에 포함) |

## 4. 하지 않는 것

Android, 스토어 제출, dev/main 머지, 실제 계정으로 로그인(사용자가 나중에 한다), 푸시, 딥 링크, 네이티브 위치 폴리필.

## 5. 검증 기준

- `just web-test`·`just web-lint`·`just check` 초록.
- 스위치 끈 빌드(`just ios-run`)는 기존 앱 그대로.
- 스위치 켠 빌드가 시뮬레이터에서 `dev.scenetrip.io` 를 열고 지도가 그려진다.
- 로컬 웹(`just web-dev`)을 연 껍데기에서 로그인 시트의 구글·Apple 단추가 네이티브 창(계정 선택 직전)을 띄운다.
