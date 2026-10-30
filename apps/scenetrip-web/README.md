# web

> 모듈 종류: `app` · 언어: `typescript` (Vite + React) · 경로: `apps/scenetrip-web`

SceneTrip 의 **웹앱**. iOS 앱(`apps/scenetrip-ios`)의 화면과 기능을 브라우저에서 iOS 와 거의 똑같이 보이고 동작하게 옮긴다.
계획·화면 체크리스트·출시 결정: [`docs/project/web-app.md`](../../docs/project/web-app.md).

> **Bazel 로 짓고 `just` 로 돌린다**(2026-10-31, [ADR 0028](../../docs/architecture/adr/0028-web-app-built-by-bazel-with-rules-js.md)).
> 규칙은 `aspect_rules_js`, Node 는 `MODULE.bazel` 이 고정한다. npm 스크립트와 `package-lock.json` 은 그대로라
> Cloudflare Pages 의 `npm ci && npm run build` 도 계속 된다.

## 사용하는 백엔드

`contracts/openapi/scene-api-v1.yaml` 의 scene-api (AWS DEV: `https://api-dev.scenetrip.io/v1`). 브라우저는 서버가 CORS 를 허용하지 않아 직접 부를 수 없으므로
개발 서버가 `/v1` 을 대신 전달한다(`vite.config.ts`). 서버·계약·iOS·Android 소스는 이 모듈이 **읽기만** 한다.

## 실행

| 명령 | 하는 일 |
| --- | --- |
| `just web-dev` | 개발 서버 http://localhost:5173 (`node_modules` 가 없으면 `npm ci`). **Bazel 밖의 npm 개발 서버** — HMR 이 소스 트리를 직접 감시해야 해서. 호스트 Node 22.12+ 필요 |
| `just web-build` | 프로덕션 번들 → `bazel-bin/apps/scenetrip-web/dist` (`index.html`·`assets/`·`_headers`·`_redirects`·`manifest.webmanifest`) |
| `just web-test` | vitest 단위 시험 (`//apps/scenetrip-web:unit_test`, tag `unit`) |
| `just web-lint` | eslint(`:lint`) + tsc 타입 검사(`:typecheck`) |
| `just web-gen` | 생성물(`:gen`)만 만든다 — 번들·시험이 알아서 먼저 만든다 |
| `just check` | 저장소 전체 게이트. 위 타깃이 `//...` 로 들어 있다 |

타깃 이름은 `:gen`(API 타입·영어 문구표·해태 그림) · `:web` · `:unit_test` · `:lint` · `:typecheck`. 소스 파일은 `BUILD.bazel` 의 glob 이 줍지만
**iOS 그림 목록(`IOS_IMAGES`)은 다른 패키지라 손으로 적는다** — 웹이 새 iOS 그림을 쓰면 거기에 한 줄을 더한다.
`package.json`/`package-lock.json` 을 바꾸면 다음 Bazel 호출이 파생 파일 `pnpm-lock.yaml`(직접 고치지 않는다)과 `.aspect/` 의 해시 파일을
갱신한다 — 그 diff 를 같은 커밋에 넣는다.

npm 으로 직접도 된다(Cloudflare 와 같은 길, Bazel 이 필요 없다):

```bash
cd apps/scenetrip-web
npm ci
npm run dev          # predev 가 npm run gen 을 먼저 돌린다
```

| npm 명령 | 하는 일 |
| --- | --- |
| `npm run gen` | 계약 → `src/api/generated/schema.d.ts`, iOS 문구표 → `src/i18n/en.generated.json`, 해태 그림 → `public/generated/` (전부 커밋하지 않는다) |
| `npm run typecheck` / `npm run lint` / `npm test` | 타입·린트·단위 시험(vitest) |
| `npm run build` | 타입 검사 + 프로덕션 번들(`dist/`) — `just web-build` 와 같은 결과 |
| `npm run check` | 위를 모두 |
| `node scripts/shot.mjs <URL> <out.png>` | 설치된 Chrome 을 puppeteer-core 로 돌려 402×874@3x 스크린샷 (iOS 스크린샷과 같은 크기). `--scale 2`, `--click 글자`, `--eval 코드`, `--until domcontentloaded`(스플래시처럼 금방 사라지는 화면) |
| `node scripts/e2e-smoke.mjs [--base URL] [--only 이름,…] [--shots 폴더]` | 핵심 흐름(홈→탭→검색→작품·촬영지 상세→코스 목록·마법사·편집기→커뮤니티→마이페이지·시트)을 헤드리스 Chrome 으로 훑어 콘솔 오류·실패한 요청·4xx/5xx·깨진 이미지·쓰기/유료 호출을 센다. 하나라도 있으면 종료 코드 1. 개발 서버가 떠 있어야 한다 |

개발 서버에서만 URL 로 상태를 정할 수 있다(`src/devFlags.ts`): `?dev-lang=ko|en`, `?dev-seen=1`(사용법 본 것으로), `?dev-nosplash=1`.
계정·기록이 있어야 열리는 화면은 서버 쓰기 없이 **읽기 전용 가짜 응답**(`src/devMock.ts`)으로 연다: `?dev-account=1`(가짜 로그인 — 저장소에는 쓰지 않는다 — 과 `/me`·`/me/reviews`·`/me/blocks`·`/me/posts`), `?dev-stamps=1`(방문 기록이 찍힌 코스 `9001`), `?dev-stamps=active`(여행 중인 코스 `9001`). 둘 중 하나라도 켜면 GET 이 아닌 요청은 서버로 나가지 않고 204 로 돌아온다. 가이드 챗봇은 `?dev-fake-guide=answer|answer-text|slow|…`(`features/route/guide/devFake.ts`, 유료 `/guide/chat` 을 부르지 않는다).
개발 서버는 DEV API 의 GET 200 응답을 10분 메모리에 둔다(`vite.config.ts`, DEV 분당 60회 한도 때문. `SCENETRIP_NO_API_CACHE=1` 로 끈다).

## 환경 변수

이름만 `.env.example` 에 있다. 값은 로컬 `.env.local` 에 둔다(커밋 금지).

- `SCENETRIP_API_TARGET` — 개발 서버 프록시 대상(기본 AWS DEV)
- `VITE_API_BASE` — 브라우저가 부르는 기준 주소(기본: 같은 출처)
- `VITE_BLOCK_WRITES=1`, `VITE_WRITE_ALLOW=<정규식>`, `VITE_BLOCK_PATHS=/guide,/navigation` — **검증 중 DEV 보호용 안전장치**. 켜면 GET 이 아닌 요청과 유료 경로를 클라이언트에서 막는다(`src/api/client.ts`).
- `VITE_NAVER_MAP_CLIENT_ID` — 지도 SDK 를 정한 뒤에 쓴다(미정).

Bazel 빌드(`just web-build`)는 호스트 환경 변수를 받지 않아 `VITE_*` 가 비어 있는 번들이 나온다(키가 들어간 배포 번들은 Cloudflare 가 `npm run build` 로 만든다).

## 구조

계획 문서 §3. iOS 와 같은 구조: 탭 셋(작품검색·홈·커뮤니티) + 덮개 둘(경로여정 `/courses`·마이페이지 `/profile`). 경로는 `src/app/routes.ts` 에서만 만든다.
디자인 토큰은 `src/design/tokens.css` 에 iOS 소스의 파일:줄 출처와 함께 있다. 문구는 iOS 와 같은 `tr("한국어 원문")` 규칙이다(`src/i18n`).

## 폴더 이름

폴더 이름은 2026-10-31 `apps/web` 에서 `apps/scenetrip-web` 으로 바꿨다(`scripts/paths.mjs` 는 깊이만 쓴다).
