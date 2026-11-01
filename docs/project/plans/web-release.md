# 웹앱 출시 준비 (Cloudflare Pages)

- **상태**: 코드·설정·문서 준비까지 끝. Cloudflare 계정·프로젝트·도메인·DNS 는 만들지 않았다(사용자 승인 필요).
- **결정(사용자, 2026-10-31)**: 호스팅 Cloudflare Pages(정적 SPA) · 폴더 `apps/scenetrip-web` · 지도 네이버 웹 SDK
- 계획·화면 체크리스트·기존 결정 목록: [`../web-app.md`](../web-app.md) §7·§9·§12 (이 문서는 그 출시 항목을 실행 순서로 푼 것이다)

## 1. 출시 체크리스트

담당: 사용자 / 권호 / 태환 / 에이전트. 상태는 2026-10-31 기준.

| 항목 | 담당 | 상태 | 비고 · **사용자가 해야 할 일** |
| --- | --- | --- | --- |
| `_redirects`·`_headers` | 에이전트 | 끝 | `public/` 에 있고 `npm run build` 후 `dist/` 에 들어간다(확인함). **할 일: 없음** |
| Cloudflare Pages 프로젝트 | 사용자 | 대기 | §2. **Cloudflare 에서 Pages 프로젝트를 만들고 GitHub 저장소를 연결한다** |
| 도메인·DNS | 사용자 | 대기 | §3. **웹 주소를 정하고 Pages 의 「사용자 지정 도메인」 에 붙인다** |
| API 주소(같은 출처 `/v1`) | 사용자 | **결정함(2026-10-31): 먼저 Pages Function 프록시** | §4. `functions/v1/[[path]].ts`·`src/proxy/forward.ts`. 사용자가 크게 늘면 서버 CORS(§5)로 바꾼다 |
| 서버 CORS | 권호 | 요청서 있음 | §5. **§5.5 메시지를 권호 님께 전달한다** |
| 사진 올리기 버킷 CORS | 권호 | 요청서 있음 | §5.5 의 두 번째 요청. **같이 전달한다** |
| 소셜 로그인 — Google | 사용자(+권호) | 대기 | §6. **Google 콘솔에서 웹 클라이언트에 JavaScript 원본·리디렉션 URI 를 등록하고 ID 를 Pages 변수에 넣는다** |
| 소셜 로그인 — Apple | 사용자 + 권호 | 대기(서버 수정 필요) | §6. **Apple 개발자 계정에서 Services ID 를 만들고, 권호 님께 §6.4 를 요청한다** |
| 지도(네이버) | 사용자 + 에이전트 | 코드는 다른 에이전트가 구현 중 | **NCP 콘솔에서 Maps 애플리케이션을 만들고 웹 서비스 URL 에 배포 도메인·`http://localhost:5173` 을 등록해 클라이언트 ID 를 받는다** |
| GA4 | 사용자 | 대기 | §7. **Firebase 콘솔에서 웹 데이터 스트림을 만들고 측정 ID 를 Pages 변수에 넣는다** |
| 개인정보 처리방침 | 사용자(+법무 확인) | 초안 있음 | §8. **초안을 검토해 `https://scenetrip.io/privacy` 에 반영하고, 쿠키 동의 배너 필요 여부를 확인한다** |
| Sentry | 사용자 | 대기 | §8.1. **sentry.io 가입 → 프로젝트(React) 생성 → DSN 을 Pages 변수 `VITE_SENTRY_DSN` 에.** 저장 지역(미국/EU)을 정해 §8 문안에 적는다 |
| PostHog | 사용자 | 대기 | §8.1. **posthog.com 가입 → 프로젝트 키를 `VITE_POSTHOG_KEY`, 지역이 EU 면 `VITE_POSTHOG_HOST=https://eu.i.posthog.com`** |
| 이용약관 | 사용자 | 대기 | `https://scenetrip.io/terms` 는 404 였다(iOS 기준 2026-10-15). **게시한다** |
| Bazel·just 연결 | 별도 에이전트 | 작업 중 | [`../web-app.md`](../web-app.md) §8. 이 문서에서는 건드리지 않았다 |
| 남은 기능 검증(쓰기·유료) | 사용자 | 대기 | §10. **미리보기 배포에서 쓰기·유료 흐름을 한 번씩 직접 해 본다** |
| CSP Report-Only → 강제 | 에이전트/사용자 | 배포 후 | §9. **미리보기에서 콘솔의 위반 기록을 모아 알려 준다** |

## 2. 호스팅: Cloudflare Pages

Pages 대시보드 → 프로젝트 만들기 → Git 연결.

| 설정 | 값 |
| --- | --- |
| 프로덕션 브랜치 | `main` (미리보기는 다른 브랜치·PR 마다 자동) |
| 루트 디렉터리 | `apps/scenetrip-web` |
| 빌드 명령 | `npm ci && npm run build` (`prebuild` 가 `npm run gen` 을 먼저 돈다 — 계약 `contracts/openapi/…yaml` 과 iOS 문구·이미지를 읽으므로 **저장소 전체가 체크아웃돼야** 한다. Pages 는 전체를 받는다) |
| 출력 디렉터리 | `dist` |
| Node 버전 | 변수 `NODE_VERSION`. 이 저장소는 로컬 Node 26.7.0 에서 확인했다. 도구의 요구는 `^20.19.0 \|\| >=22.12.0`(vite 8). **Pages 가 지원하는 버전은 확인하지 못했다 — 22 이상으로 지정해 첫 빌드를 본다** |

`npm run build` 는 이 모듈에서 `tsc -b && vite build` 다. 로컬에서 같은 명령이 성공함을 확인했다. 빌드가 `gen` 단계에서 실패하면(예: `ios` 소스 경로) 로그가 `scripts/gen-api.mjs`·`sync-ios-assets.mjs` 를 가리킨다.

### 환경 변수

`apps/scenetrip-web/.env.example` 과 코드의 `import.meta.env` 를 대조한 결과다. 값은 Pages 대시보드(설정 → 환경 변수)에만 넣는다. **저장소·문서에 값을 쓰지 않는다.** 이 변수들은 빌드할 때 번들에 박힌다 — 바꾸면 다시 빌드해야 한다(비밀이 아니다: 브라우저에서 보이는 공개 식별자만 넣는다).

| 이름 | 프로덕션 | 미리보기 | 코드 위치 | 비고 |
| --- | --- | --- | --- | --- |
| `VITE_API_BASE` | `https://api.scenetrip.io` (또는 §4 의 프록시면 비움) | `https://api-dev.scenetrip.io` | `src/api/client.ts` | 끝에 `/` 와 `/v1` 을 붙이지 않는다(코드가 `/v1` 을 붙인다) |
| `VITE_GOOGLE_CLIENT_ID` | 웹 OAuth 클라이언트 ID | 같음 | `src/features/profile/auth/oauth.ts` | 비면 로그인 시트가 「준비 중」 |
| `VITE_APPLE_CLIENT_ID` | Apple Services ID | 같음 | 〃 | 서버 수정 선행(§6.4) |
| `VITE_APPLE_REDIRECT_URI` | 선택. 비우면 `<출처>/auth/callback/apple` | 〃 | 〃 | 미리보기 주소는 매번 다르다 — §6.3 |
| `VITE_GA_MEASUREMENT_ID` | `G-…` (프로덕션 스트림) | 비움 또는 별도 스트림 | `src/analytics/index.ts` | 비면 완전한 no-op |
| `VITE_NAVER_MAP_CLIENT_ID` | NCP 클라이언트 ID | 같음 | `.env.example` 에만 있다(지도 코드는 다른 에이전트가 구현 중 — 구현이 끝나면 코드의 이름과 다시 대조한다) | |
| `SCENETRIP_API_TARGET` | **넣지 않는다** | 〃 | `vite.config.ts` | 개발 서버·`vite preview` 프록시 전용. 배포 번들에 영향 없다 |
| `VITE_BLOCK_WRITES` · `VITE_WRITE_ALLOW` · `VITE_BLOCK_PATHS` | **넣지 않는다** | 쓰기·유료를 막고 싶을 때만 | `src/api/client.ts` | 개발 안전장치. 프로덕션에 들어가면 쓰기가 막힌다 |

## 3. 도메인·DNS (제안)

- `scenetrip.io` 의 DNS 는 **Cloudflare 존**이다(2026-10 가비아에서 옮김 — [`dev-lifecycle.md`](dev-lifecycle.md), ADR 0017). 같은 계정에서 Pages 의 사용자 지정 도메인을 붙이면 CNAME 이 자동으로 만들어진다.
- 제안: 웹앱 `app.scenetrip.io`. 루트 `scenetrip.io` 는 방침·약관·랜딩이 이미 쓰는 자리라 건드리지 않는다. **현재 `scenetrip.io` 루트가 어디서 서빙되는지는 확인하지 못했다.**
- 이 주소는 §5(CORS), §6(OAuth 원본·리디렉션), 네이버 지도(웹 서비스 URL)에 모두 등록된다 — **먼저 정해야 나머지가 풀린다.**
- Pages 기본 주소(`<프로젝트>.pages.dev`)와 미리보기 주소(`<해시>.<프로젝트>.pages.dev`)도 서버·OAuth 에 등록하지 않으면 미리보기에서 API·로그인이 막힌다(§10).

## 4. API 주소 — 같은 출처 `/v1` 이 안 되는 이유

개발 서버는 `/v1` 을 DEV 로 대신 전달한다(`vite.config.ts`). **정적 Pages 에서는 이 방식이 없다.** `_redirects` 의 `200`(프록시) 규칙은 같은 사이트 안의 경로로만 보낼 수 있고 외부 서버(`api.scenetrip.io`)로는 보내지 못한다(Cloudflare 문서 기준, 직접 시험하지 않음). 그래서 선택은 둘이다.

| 방식 | 장점 | 단점 |
| --- | --- | --- |
| **A. 서버 CORS 허용** + `VITE_API_BASE=https://api.scenetrip.io` | 코드 변경 없음. 정적 호스팅 그대로 | 권호 님 작업이 선행. 요청 때마다 preflight(`Authorization`·JSON) — 캐시로 줄인다 |
| B. Pages Functions 로 `/v1/*` 프록시 | 같은 출처라 CORS·preflight 없음. 서버 변경 없음 | `functions/` 코드 추가(프록시 코드·요청 한도·IP 전달 `X-Forwarded-For` 처리 필요 — scene-api 가 IP 로 한도를 센다), Functions 요금/한도 |

**결정(2026-10-31, 사용자): 먼저 B(프록시)로 간다.** 권호 님 작업 없이 바로 배포할 수 있고, 시연·소규모에 충분하다. 장기적으로는 A 가 낫다.

프록시 한계를 서버 코드로 확인했다(2026-10-31).
- **앱별 한도는 어긋나지 않는다.** `RequestRateLimitFilter.subject()` 가 비회원을 `X-Install-Id` 로 먼저 세고(그다음에야 IP), 회원은 `Authorization` 으로 센다. 프록시는 두 헤더를 그대로 넘긴다.
- **그러나 서버 앞 nginx 는 접속 IP 당 초당 30건·burst 60·동시 60연결 울타리를 친다**(`platform/helm/scenetrip/templates/config.yaml`, 평소값). nginx 는 `real_ip_recursive off` 로 `X-Forwarded-For` 의 **마지막 항목(ALB 가 본 접속 IP)** 을 쓰므로, 프록시가 넣는 사용자 IP 는 무시되고 웹 사용자 전원이 Cloudflare 출구 IP 몇 개를 함께 쓴다. 시연은 충분하고 사용자가 크게 늘면 429 가 날 수 있다.
- **그때의 해결**: 권호 님이 서버 CORS 를 열고(§5) 웹앱의 `VITE_API_BASE=https://api.scenetrip.io` 로 직접 부르게 바꾼다(웹 코드 변경 없음, 프록시는 쓰지 않게 된다). 또는 nginx 가 Cloudflare 의 `CF-Connecting-IP` 를 믿게 한다.
- 사진 올리기(S3 presigned PUT)는 프록시를 거치지 않으므로 **버킷 CORS(§5 두 번째 요청)는 어느 쪽이든 필요하다.**

배포 설정: `functions/v1/[[path]].ts` 가 `/v1/*` 를 `https://api.scenetrip.io` 로 전달하고, `public/_routes.json` 이 그 길에만 Function 을 부르게 한다. `VITE_API_BASE` 는 비워 둔다(같은 출처 `/v1`). 시험: `src/proxy/forward.test.ts`.

## 5. CORS 요청서 (권호 님)

### 5.1 서버의 현재 설정 (읽기만 함)

- `services/` 전체와 `platform/`·`tools/aws` 에서 `cors`·`Access-Control`·`allowedOrigins` 를 검색했으나 **CORS 설정이 없다.** 응답에 `Access-Control-*` 헤더를 내지 않는다.
- DEV 실측(2026-10-29, [`../web-app.md`](../web-app.md) §7 「CORS 증거」): `GET`·`OPTIONS` 모두 `access-control-allow-origin` 없음. `OPTIONS` 는 200 이지만 CORS 헤더가 없어 브라우저가 막는다. 응답 서버는 nginx.
- 따라서 **부족한 것은 전부**다: 허용 Origin·허용 메서드·허용 헤더·노출 헤더·preflight 캐시.
- 이 점검 이후 서버·ingress 가 바뀌었을 수 있다 — 요청 전에 `curl -i -X OPTIONS` 로 한 번 더 본다.

### 5.2 웹이 서버로 보내는 요청의 실제 모양 (코드에서 추출)

| 항목 | 내용 | 근거 |
| --- | --- | --- |
| 메서드 | `GET`, `POST`, `PUT`, `DELETE` (+ preflight 의 `OPTIONS`). `PATCH`·`HEAD` 는 쓰지 않는다 | `api.GET/POST/PUT/DELETE` 호출 집계 |
| 요청 헤더(안전 목록 밖) | `Authorization` (로그인했을 때 `Bearer`), `Content-Type: application/json`(본문 있는 요청), `Accept-Language`(`ko`/`en`), `X-Install-Id`(설치 UUID), `Idempotency-Key`(가이드 질문), `X-SceneTrip-Coordinate-Source`(가이드 질문) | `src/api/client.ts`, `src/state/install.ts`, `features/route/guide/guideSender.ts` |
| 보내지 않는 헤더 | `X-SceneTrip-No-Retry` 는 전송 직전에 떼므로 나가지 않는다 | `client.ts` |
| 자격증명 | **쿠키를 쓰지 않는다.** `credentials` 옵션 없음(기본 `same-origin`). 토큰은 `Authorization` 헤더 → `Access-Control-Allow-Credentials` **불필요**, `Allow-Origin` 에 `*` 도 가능하지만 아래처럼 명시 목록을 권한다 | `grep credentials` 결과 없음 |
| 읽는 응답 헤더 | `Retry-After`(429·503 재시도 대기), `RateLimit-*`(가이드 한도 표시, `features/route/guide/rateLimit.ts`). 둘 다 CORS 안전 목록 **밖**이라 `Access-Control-Expose-Headers` 가 없으면 웹에서 `null` 이다 | `client.ts`, `rateLimit.ts`, 서버 `RequestRateLimitFilter` 가 `RateLimit-Limit` 등을 싣는다 |
| 사진 올리기 | API 가 아니라 **저장소(S3 서명 주소)로 브라우저가 직접 `PUT`**. 헤더는 서버가 준 `requiredHeaders` 그대로(`Content-Type: image/jpeg` 등), `Authorization` 은 붙이지 않는다 | `src/shared/photoUpload.ts` |

### 5.3 요구 사항 정리

- **허용 Origin**: 배포 도메인(제안 `https://app.scenetrip.io`, 확정되면 교체) + 개발 `http://localhost:5173`. Pages 미리보기를 서버에 붙여 시험하려면 `https://*.<프로젝트>.pages.dev` 패턴이 필요한데 와일드카드는 **DEV 에만** 허용하기를 권한다(PRD 는 고정 목록).
- **허용 메서드**: `GET, POST, PUT, DELETE, OPTIONS`
- **허용 요청 헤더**: `Authorization, Content-Type, Accept-Language, X-Install-Id, Idempotency-Key, X-SceneTrip-Coordinate-Source`
- **자격증명**: 쓰지 않음 (`Allow-Credentials` 생략)
- **노출 헤더**: `Retry-After, RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset` (웹이 읽는 이름 `rateLimit.ts` = 서버 `RequestRateLimitFilter` 가 싣는 이름, 둘이 일치함)
- **preflight 캐시**: `Access-Control-Max-Age: 86400` (브라우저가 상한을 더 낮게 자를 수 있다)
- 에러 응답(401·429·5xx)에도 CORS 헤더가 실려야 한다 — 필터/ingress 가 앞단에서 거절하는 응답(분당 한도 429 포함)이 CORS 헤더 없이 나가면 웹은 오류 코드를 읽지 못하고 「네트워크 오류」 로 본다.

### 5.4 신뢰 경계 메모

`Origin` 을 그대로 되돌려 주지 말고 허용 목록과 비교한다. 쿠키를 쓰지 않으므로 CSRF 위험은 낮지만, 허용 목록 밖 Origin 은 헤더를 내지 않는다.

### 5.5 권호 님께 보낼 메시지 (붙여넣기용)

> 권호 님, 웹앱(`apps/scenetrip-web`)을 Cloudflare Pages 로 출시하려는데, 브라우저가 `api.scenetrip.io` 를 직접 부르려면 서버 CORS 가 필요합니다. 지금 DEV 는 `Access-Control-*` 를 아예 내지 않습니다(코드에도 설정 없음, 10/29 실측).
>
> **1. API CORS (api-dev 먼저, 그다음 api)**
> - 허용 Origin: `https://app.scenetrip.io`(도메인 확정되면 알려 드릴게요), `http://localhost:5173`. DEV 만 Pages 미리보기용 `https://*.<프로젝트>.pages.dev` 도 부탁드립니다.
> - 메서드: `GET, POST, PUT, DELETE, OPTIONS`
> - 요청 헤더: `Authorization, Content-Type, Accept-Language, X-Install-Id, Idempotency-Key, X-SceneTrip-Coordinate-Source`
> - 자격증명: 안 씁니다(쿠키 없음, 토큰은 Authorization 헤더) — `Allow-Credentials` 불필요
> - 노출 헤더: `Retry-After, RateLimit-Limit, RateLimit-Remaining, RateLimit-Reset` (웹이 429 재시도 대기와 가이드 한도 표시에 읽습니다)
> - preflight 캐시: `Access-Control-Max-Age: 86400`
> - 401·429·5xx 같은 에러 응답과 분당 한도 필터가 거절하는 응답에도 CORS 헤더가 실려야 합니다(안 실리면 웹은 상태 코드를 못 읽고 네트워크 오류로 처리).
>
> **2. 사진 버킷 CORS (후기·커뮤니티 사진)**
> 브라우저가 `POST /uploads` 로 받은 서명 주소에 직접 `PUT` 합니다. 버킷 CORS 에 위 Origin 들, 메서드 `PUT`, 허용 헤더는 서명에 들어가는 `requiredHeaders`(최소 `Content-Type`) 를 열어 주세요. 응답 `ETag` 는 쓰지 않습니다.
>
> **3. Apple 웹 로그인** (CORS 와 별개): `/auth/apple` 이 받는 `aud` 가 번들 ID(`com.mz2az.scenetrip`) 하나라서, 웹 Services ID 로 온 토큰은 거절됩니다. 자세한 건 `docs/project/plans/web-release.md` §6.4 에 적었습니다.
>
> 도메인과 Services ID 가 확정되면 값을 바로 드릴게요.

## 6. 소셜 로그인 (웹)

### 6.1 구현 (읽은 결과)

`src/features/profile/auth/oauth.ts`. 코드에 하드코딩된 ID 는 **없다**(환경 변수만 읽는다) — 코드 변경 없음.

- **Google**: 팝업으로 `https://accounts.google.com/o/oauth2/v2/auth` 를 열어 `response_type=id_token`(암시적 흐름), `nonce`·`state` 를 쓴다. 돌아오는 곳은 우리 앱의 `<출처>/auth/callback/google` 이고 그 페이지가 opener 에 fragment 를 postMessage 한다. ID 토큰 + nonce 원문을 `POST /auth/google` 로 보낸다.
- **Apple**: Apple JS(`appleid.cdn-apple.com/.../appleid.auth.js`)를 동적으로 불러 팝업(`usePopup: true`). 요청에는 난수의 SHA-256, 서버에는 원문을 보낸다. `POST /auth/apple` 에 `identityToken`·`authorizationCode`·`nonce`.
- 두 ID 가 없으면 `NotConfigured` → 시트가 「준비 중」.
- 실제 로그인은 시험한 적이 없다(키·계정 없음).

### 6.2 Google — 콘솔 작업

1. Google Cloud 콘솔 → API 및 서비스 → 사용자 인증 정보 → **OAuth 2.0 클라이언트 ID(웹 애플리케이션)** 를 쓴다.
2. **승인된 JavaScript 원본**: 배포 도메인(`https://app.scenetrip.io`), `http://localhost:5173`.
3. **승인된 리디렉션 URI**: `https://app.scenetrip.io/auth/callback/google`, `http://localhost:5173/auth/callback/google` (암시적 흐름이 이 주소로 돌아온다 — 코드가 `redirect_uri` 로 보낸다).
4. 클라이언트 ID 를 Pages 의 `VITE_GOOGLE_CLIENT_ID` 에 넣는다(비밀번호 격인 client secret 은 쓰지 않는다 — 넣지 않는다).
5. OAuth 동의 화면이 「테스트」 상태면 테스트 사용자만 로그인된다. 출시 전 게시 상태 확인.

### 6.3 Apple — 콘솔 작업

1. Apple Developer → Identifiers → **Services ID** 생성(예: `com.mz2az.scenetrip.web`), 「Sign in with Apple」 활성화, 기본 앱 ID `com.mz2az.scenetrip` 연결.
2. Configure: **Domains** 에 배포 도메인(`app.scenetrip.io`), **Return URLs** 에 `https://app.scenetrip.io/auth/callback/apple`. 도메인 소유 확인용 파일(`apple-developer-domain-association.txt`)을 Apple 이 주면 `public/.well-known/` 에 두어야 한다(이 파일은 Apple 이 발급하므로 아직 없다. 정적 파일은 `_redirects` 의 SPA 대체보다 우선한다).
3. Apple 은 **localhost 와 등록 안 된 주소를 받지 않는다.** 로컬·Pages 미리보기에서 Apple 로그인은 시험할 수 없고, 프로덕션 도메인에서만 된다.
4. Services ID 를 `VITE_APPLE_CLIENT_ID` 에 넣는다. `VITE_APPLE_REDIRECT_URI` 를 비우면 코드가 `<출처>/auth/callback/apple` 을 쓰므로 Return URL 과 일치해야 한다.

### 6.4 서버가 웹 클라이언트 ID 를 받아 주는가 (services/ 읽기 결과)

- **Google: 받아 줄 가능성이 높다.** `application.yaml` 의 `scenetrip.auth.google.client-ids` 는 두 개이고, 주석상 하나는 **「웹 — Android 가 serverClientId 로 넘겨 aud 가 이것이 된다」**, 다른 하나는 iOS 다. 즉 **웹 유형 클라이언트가 이미 허용 목록에 있다.** 그 클라이언트에 §6.2 의 JavaScript 원본·리디렉션 URI 만 추가하고 그 ID 를 `VITE_GOOGLE_CLIENT_ID` 로 쓰면 **서버 변경 없이** 된다. 새 웹 클라이언트를 따로 만들면 `client-ids` 에 추가해야 한다(권호 님 요청). 어느 쪽이 그 웹 클라이언트인지는 yaml 주석 순서(첫 번째)와 Google 콘솔로 확인해야 한다 — **콘솔은 확인하지 못했다.** nonce 는 원문 비교(`GoogleIdTokenVerifier`)라 웹 흐름과 맞다.
- **Apple: 받아 주지 않는다 (서버 수정 필요).** `AppleIdTokenVerifier` 는 `aud` 가 `scenetrip.auth.apple.client-id`(= 번들 ID `com.mz2az.scenetrip`) **한 개와 같아야** 통과시킨다. 웹 Services ID 로 받은 토큰의 `aud` 는 Services ID 라 거절된다. 게다가 같은 `client-id` 를 `AppleClient` 가 코드 교환(`/auth/token`)·연결 끊기(`/auth/revoke`)의 `client_id` 와 클라이언트 시크릿 JWT 의 `sub` 로 쓴다 — Services ID 로 온 인증 코드는 Services ID 로 교환해야 하므로 허용 `aud` 를 늘리는 것만으로는 부족하다. nonce 해시 비교는 웹 흐름과 맞다.
  - 권호 님께 요청할 내용: (1) `aud` 허용을 번들 ID + Services ID 목록으로, (2) 웹 토큰의 코드 교환·revoke 에 Services ID 를 `client_id`/`sub` 로 쓰는 분기(Apple 이 Services ID 용 키를 같은 팀의 같은 Sign in with Apple 키로 서명하게 허용하는지는 Apple 설정 확인 필요 — 확인하지 못했다), (3) Services ID 값은 사용자가 만들어 알려 준다.
  - Apple 웹 로그인을 출시 첫 범위에서 빼고 「준비 중」 으로 두는 것도 가능하다(Google 만 켜는 경우 App Store 4.8 규정은 iOS 앱 이야기라 웹에는 해당하지 않으나, 정책 판단은 사용자 확인 필요).

## 7. GA4

코드: `src/analytics/` (이벤트 26개, [`../web-app.md`](../web-app.md) §12). 정본 이벤트표: [`analytics-events.md`](analytics-events.md).

### 7.1 콘솔 작업

1. Firebase 콘솔 → 프로젝트 `scenetrip-5bf07` → 프로젝트 설정 → 일반 → **앱 추가 → 웹(`</>`)** (또는 Analytics → 데이터 스트림 → 웹 스트림 추가). 스트림 URL 은 배포 도메인.
2. 앱 닉네임 예: `SceneTrip Web`. 「Firebase 호스팅 설정」 은 건너뛴다(Cloudflare 를 쓴다).
3. 발급된 **측정 ID(`G-…`)** 만 쓴다. Firebase SDK 설정 코드는 필요 없다(우리는 `gtag.js` 를 직접 부른다).
4. 측정 ID → Pages 환경 변수 `VITE_GA_MEASUREMENT_ID`(프로덕션). 미리보기에는 넣지 않거나 별도 스트림을 만들어 운영 데이터와 섞이지 않게 한다. 다시 빌드한다.
5. GA4 관리 → 데이터 스트림 → 웹 스트림 → **향상된 측정**: 페이지뷰·스크롤 등 자동 이벤트가 켜져 있으면 우리가 보내는 이벤트와 별개로 쌓인다. 이름·매개변수를 앱과 맞추려면 끄는 것을 권하지만 판단은 사용자 몫. (코드는 자동 `page_view` 를 끈다 — `send_page_view: false`.)

### 7.2 DebugView 확인

- 코드에는 URL 로 켜는 debug 스위치가 **없다**(`?debug_mode` 는 구현하지 않았다). 방법은 둘이다.
  1. Chrome 확장 「Google Analytics Debugger」 를 켜고 배포 주소를 연다 → 콘솔에 gtag 로그, GA4 DebugView 에 기기가 뜬다.
  2. 개발자 도구 콘솔에서 `gtag('set', { debug_mode: true })` 를 실행한 뒤 화면을 조작한다(이후 이벤트에 `debug_mode` 가 붙는다). 새로고침하면 사라진다.
- GA4 → 관리 → DebugView 에서 이벤트가 몇 초 안에 찍히는지 본다.
- 이용 통계가 꺼져 있으면 아무것도 안 나간다(마이페이지 → 이용 통계 토글). **기본은 켜짐**이다(`usageStatistics.ts`: 값이 없으면 `true`).
- 눌러 볼 수 있는 이벤트 순서는 [`analytics-events.md`](analytics-events.md) §6 과 같다: 검색 → 작품·장소 열기 → 하트·담기 → 코스 만들기. 길찾기·가이드·로그인 계열은 유료/쓰기 호출이라 §10 의 허락 하에 한 번.

## 8. 개인정보 처리방침 — 추가 문안 초안

> **초안이다. 법적으로 확정된 문구가 아니며, 게시 전에 법무/사용자 검토가 필요하다.** 앱(iOS·Android) 처리방침의 분석 항목과 같은 수준으로 맞췄다([`analytics-events.md`](analytics-events.md) §4 와 어긋나지 않게).

**웹 서비스 이용 통계 (Google Analytics 4)**

회사는 웹 서비스(예: `app.scenetrip.io`)의 이용 현황을 파악하고 서비스를 개선하기 위해 Google LLC 의 Google Analytics 4(Firebase 포함)를 사용합니다.

- **수집하는 항목**: 서비스 이용 기록(열어 본 화면, 눌러 본 기능, 작품·장소·코스 등의 서비스 내부 식별 번호), 기기·브라우저 정보(브라우저 종류와 버전, 운영체제, 화면 크기, 언어 설정), 대략적인 지역(접속 IP 주소로 추정한 국가·도시 수준. Google 이 IP 주소를 저장하지 않고 지역 추정에만 사용합니다), 방문 횟수·이용 시간. 이를 위해 브라우저에 쿠키(`_ga`, `_ga_<ID>`)를 저장합니다.
- **수집·이용 목적**: 어떤 기능이 많이 쓰이는지 통계로 파악하고 서비스를 개선하기 위해서입니다.
- **수집하지 않는 항목**: 이름, 이메일 주소, 닉네임, 위치 좌표, 후기·게시글 등 작성한 글의 본문, 검색어 원문(검색은 글자 수와 종류만), 사진 주소는 분석 도구로 보내지 않습니다.
- **보유 기간**: Google Analytics 의 데이터 보관 설정에 따릅니다(현재 설정 값은 GA4 콘솔에서 확인해 기입 — **미확인**).
- **거부 방법**: 마이페이지 → 설정의 「이용 통계」 를 끄면 이후 이용 정보가 전송되지 않습니다. 브라우저 설정에서 쿠키를 삭제하거나 차단할 수도 있습니다. 끄셔도 서비스 이용에는 제한이 없습니다.
- **처리 위탁·국외 이전**: 수집된 정보는 Google 의 서버(국외)에서 처리될 수 있습니다. (이전 국가·시기·방법·문의처 등 법정 고지 항목은 법무 확인 후 기재 — **미확정**)

## 8.1 Sentry·PostHog 를 켜는 순서

코드는 `src/analytics/` 에 이미 있고 키가 없어 꺼져 있다([`../web-app.md`](../web-app.md) §12.1). **방침을 먼저 고치고, 그다음에 키를 넣는다.**

1. **방침 개정 초안을 검토해 `https://scenetrip.io/privacy` 에 게시한다**(아래 문안). 시행일은 게시일이다.
   - 방침 10항은 「수집 항목 추가·제3자 제공은 30일 전부터 알린다」고 적고 있다. 웹은 아직 공개 전이라 예고를 받을 기존 이용자가 없다는 판단으로 즉시 시행한다(사용자 확인, 2026-11-01). **법적 판단은 아니다 — 법무 확인이 필요하다.**
   - iOS 심사 빌드에는 Sentry·PostHog SDK 가 없다. App Privacy 라벨은 바뀌지 않는다.
2. sentry.io·posthog.com 에 가입하고 저장 지역을 정한다. 문안의 「국외 이전」에 그 지역을 적는다.
3. Cloudflare Pages 변수(Production)에 `VITE_SENTRY_DSN`, `VITE_POSTHOG_KEY`(EU 면 `VITE_POSTHOG_HOST` 도)를 넣고 **재배포**한다(`VITE_` 값은 빌드 때 박힌다).
4. 확인: 브라우저에서 앱을 열어 네트워크에 Sentry·PostHog 요청이 나가는지, 마이페이지 「이용 통계」 를 끄면 멈추는지 본다. Sentry 는 일부러 오류를 한 번 내 보고가 오는지, 보고에 주소의 `?` 뒤·IP·쿠키가 없는지 본다.

**오류 추적 (Sentry) — 문안 초안**

회사는 웹 서비스의 오류를 찾아 고치기 위해 Functional Software, Inc.(Sentry)의 오류 추적 서비스를 사용합니다. *(법인명은 가입 화면에서 확인 — 미확인)*

- **수집하는 항목**: 처리하지 못한 오류의 내용과 코드 위치, 브라우저 종류와 버전, 운영체제, 오류가 난 화면의 주소(검색어·좌표 등 `?` 뒤는 지웁니다).
- **수집하지 않는 항목**: 이름·이메일·닉네임, 접속 IP 주소, 쿠키, 화면 녹화, 입력한 글, 클릭·요청 기록.
- **목적**: 오류를 빨리 찾아 고치기 위해서입니다.
- **거부 방법**: 마이페이지 → 설정의 「이용 통계」 를 끄면 오류 정보도 전송되지 않습니다.
- **보유 기간·처리 위탁·국외 이전**: Sentry 의 서버(선택한 지역 — **미확정**)에서 처리됩니다. 보유 기간은 프로젝트 설정 값을 기입합니다 — **미확인**.

**웹 서비스 이용 통계 (PostHog) — 문안 초안**

GA4 와 같은 이용 기록(열어 본 화면, 눌러 본 기능, 서비스 내부 식별 번호)을 PostHog, Inc. 의 분석 서비스로도 보냅니다. *(법인명은 가입 화면에서 확인 — 미확인)* 수집하지 않는 항목, 거부 방법은 위 GA4 문안과 같습니다. 자동 수집·화면 녹화·사용자 식별은 하지 않고, 접속 IP 주소는 저장하지 않게 설정합니다. 브라우저에 PostHog 저장 값(`ph_*`)을 둘 수 있습니다. 보유 기간·국외 이전은 위와 같이 **미확정**입니다.

**법무/사용자 확인 필요**

- 쿠키(`_ga*`) 사용에 사전 동의 배너가 필요한지는 이 문서에서 판단하지 않는다. 현재 구현은 **기본 켜짐, 마이페이지에서 끄는 방식**이며 첫 방문 때 동의를 묻지 않는다(`usageStatistics.ts`).
- 국외 이전 고지, 보관 기간, 처리방침 시행일.
- 웹 처리방침 URL `https://scenetrip.io/privacy` 가 영어판이 없다(한국어 한 장). 웹은 영어 사용자도 있으므로 영어판 필요 여부.

## 9. CSP (Report-Only) 와 헤더

`public/_headers` 가 정한다. 근거가 있는 출처만 넣었다.

| 지시어 | 허용한 출처 | 근거 |
| --- | --- | --- |
| `script-src` | `'self'`, `https://www.googletagmanager.com`, `https://appleid.cdn-apple.com`, `https://oapi.map.naver.com` | `ga4.ts`(gtag.js), `oauth.ts`(Apple JS), 네이버 공식 튜토리얼의 스크립트 `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=…` |
| `connect-src` | `'self'`, `https://api.scenetrip.io`, `https://api-dev.scenetrip.io`, `https://www.googletagmanager.com`, `https://*.google-analytics.com`, `https://*.analytics.google.com` | API(`client.ts`), GA4 전송(Google 태그 문서의 일반 목록) |
| `img-src` | `'self'`, `data:`, `blob:`, `https:` | 사진 주소가 API 응답에서 오는 여러 호스트(작품 포스터·장소 사진·커뮤니티 사진)라 호스트 목록을 모른다. 지금 지도(Leaflet)의 `tile.openstreetmap.org` 도 포함된다 |
| `style-src` | `'self'`, `'unsafe-inline'` | 화면 코드의 `style` 속성 |
| 그 밖 | `default-src 'self'`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `font-src 'self' data:`, `manifest-src 'self'` | |

**확인하지 못해 넣지 않은 것 — 배포 후 Report-Only 로그로 확정**:

- 네이버 지도 타일·이미지·API 호출 호스트(`*.pstatic.net` 등 — 네이버 공식 문서에서 CSP 호스트 목록을 찾지 못했다). 스크립트 호스트만 확인했다.
- 사진 업로드 PUT 의 S3 호스트(`connect-src`) — 버킷 이름·리전을 모른다.
- 사진 표시 호스트(`img-src https:` 로 넓게 열어 둠) — 로그로 좁힌다.
- Google 로그인·Apple 로그인 팝업이 우리 문서의 CSP 에 걸리는지(팝업은 별도 문서라 영향이 없을 것으로 보이나 시험 전).
- `worker-src`·`frame-src` 등 네이버 SDK 가 쓰는 지시어.

**강제로 바꾸는 순서**: 미리보기에서 모든 화면을 훑고 → 브라우저 콘솔(「[Report Only] Refused to …」) 위반을 모아 허용 목록에 추가 → 위반이 없으면 `_headers` 에서 `Content-Security-Policy-Report-Only` 를 `Content-Security-Policy` 로 바꾼다. 보고 수집 엔드포인트(`report-uri`/`report-to`)는 만들지 않았다 — 콘솔 기록으로 충분한 단계다.

`X-Frame-Options: DENY`: `frame-ancestors` 는 Report-Only 에서 무시되므로 이 헤더로 막는다. `Permissions-Policy`: `geolocation=(self)`(위치 권한은 우리 문서만), 마이크·결제는 끔. 카메라는 사진 선택(`<input type=file>`)이 쓸 수 있어 정책을 걸지 않았다.

캐시: `/assets/*` 는 해시 이름이라 1년 `immutable`, `/` 와 `/index.html` 은 `no-cache`. `public/generated/*`(해태 그림, 해시 없음)와 `manifest.webmanifest` 는 Pages 기본값을 쓴다.

## 10. 미리보기 배포로 시험하는 순서

1. **Pages 프로젝트 만들기 → 미리보기 배포.** 빌드 성공과 `/`, `/courses`, `/profile` 새로고침(SPA 대체 동작)을 본다.
2. `VITE_API_BASE=https://api-dev.scenetrip.io`(미리보기 변수) 로 읽기 전용 화면(홈·검색·작품 상세·장소 상세) — **서버 CORS 가 들어간 뒤에만** 된다. 그 전에는 콘솔이 CORS 오류다.
3. 지도(네이버 클라이언트 ID 등록 뒤), 위치 권한 창.
4. CSP 위반 기록 수집 → §9 허용 목록 확정.
5. **사람이 직접, 한 번씩, DEV 가 아니라 승인된 환경에서**: 로그인(Google) → 닉네임 → 후기/사진 올리기(버킷 CORS) → 코스 만들기·시작·방문 기록 → 가이드 질문(유료) → 로그아웃·탈퇴. 에이전트는 쓰기·유료 호출을 하지 않는다(메모리의 DEV 쓰기 금지 규칙). DEV 에 시험 데이터를 남기지 않는다.
6. 프로덕션 도메인 연결 → `VITE_*` 프로덕션 값 → 다시 빌드 → Apple 로그인(프로덕션 도메인에서만 가능)·GA4 DebugView.
7. CSP 강제 전환, 처리방침 게시, 공개.

## 11. 확인하지 못한 것

- Cloudflare Pages 가 지원하는 Node 버전, `_redirects` 의 외부 프록시 불가(문서 기준, 직접 시험 안 함), `_headers` 규칙이 실제 응답에 적용되는지(배포 필요).
- 네이버 지도의 CSP 호스트 목록, 사진 S3 호스트.
- Google 콘솔의 웹 클라이언트 실체와 현재 승인 원본/URI, Apple Services ID 설정, GA4 보관 기간 설정.
- `scenetrip.io` 루트가 지금 어디서 서빙되는지.
- 서버 CORS 는 코드·플랫폼 파일 기준으로 없음을 확인했고, 현재 DEV/PRD 실제 응답은 10/29 이후 다시 측정하지 않았다.
