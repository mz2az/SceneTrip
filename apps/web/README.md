# web

> 모듈 종류: `app` · 언어: `typescript` (Vite + React) · 경로: `apps/web`

SceneTrip 의 **웹앱**. iOS 앱(`apps/scenetrip-ios`)의 화면과 기능을 브라우저에서 iOS 와 거의 똑같이 보이고 동작하게 옮긴다.
계획·화면 체크리스트·출시 결정: [`docs/project/web-app.md`](../../docs/project/web-app.md).

> **아직 Bazel/just 에 연결되지 않았다 — 연결에는 사용자 승인이 필요하다.**
> 새 언어·툴체인 도입이라 `MODULE.bazel`·루트 `BUILD.bazel`·`justfile`·`tools/`·`.github/` 를 건드리지 않았다. 그래서 이 모듈은
> 저장소 규칙의 예외로 **자체 완결형 npm 프로젝트**다. 연결 제안은 계획 문서 §8.

## 사용하는 백엔드

`contracts/openapi/scene-api-v1.yaml` 의 scene-api (AWS DEV: `https://api-dev.scenetrip.io/v1`). 브라우저는 서버가 CORS 를 허용하지 않아 직접 부를 수 없으므로
개발 서버가 `/v1` 을 대신 전달한다(`vite.config.ts`). 서버·계약·iOS·Android 소스는 이 모듈이 **읽기만** 한다.

## 실행

```bash
cd apps/web
npm ci
npm run dev          # http://localhost:5173  (predev 가 npm run gen 을 먼저 돌린다)
```

| 명령 | 하는 일 |
| --- | --- |
| `npm run gen` | 계약 → `src/api/generated/schema.d.ts`, iOS 문구표 → `src/i18n/en.generated.json`, 해태 그림 → `public/generated/` (전부 커밋하지 않는다) |
| `npm run typecheck` / `npm run lint` / `npm test` | 타입·린트·단위 시험(vitest) |
| `npm run build` | 타입 검사 + 프로덕션 번들(`dist/`) |
| `npm run check` | 위를 모두 (사실상 이 모듈의 `just check`) |
| `node scripts/shot.mjs <URL> <out.png>` | 설치된 Chrome 을 puppeteer-core 로 돌려 402×874@3x 스크린샷 (iOS 스크린샷과 같은 크기) |

개발 서버에서만 URL 로 상태를 정할 수 있다(`src/devFlags.ts`): `?dev-lang=ko|en`, `?dev-seen=1`(사용법 본 것으로), `?dev-nosplash=1`.

## 환경 변수

이름만 `.env.example` 에 있다. 값은 로컬 `.env.local` 에 둔다(커밋 금지).

- `SCENETRIP_API_TARGET` — 개발 서버 프록시 대상(기본 AWS DEV)
- `VITE_API_BASE` — 브라우저가 부르는 기준 주소(기본: 같은 출처)
- `VITE_BLOCK_WRITES=1`, `VITE_WRITE_ALLOW=<정규식>`, `VITE_BLOCK_PATHS=/guide,/navigation` — **검증 중 DEV 보호용 안전장치**. 켜면 GET 이 아닌 요청과 유료 경로를 클라이언트에서 막는다(`src/api/client.ts`).
- `VITE_NAVER_MAP_CLIENT_ID` — 지도 SDK 를 정한 뒤에 쓴다(미정).

## 구조

계획 문서 §3. iOS 와 같은 구조: 탭 셋(작품검색·홈·커뮤니티) + 덮개 둘(경로여정 `/courses`·마이페이지 `/profile`). 경로는 `src/app/routes.ts` 에서만 만든다.
디자인 토큰은 `src/design/tokens.css` 에 iOS 소스의 파일:줄 출처와 함께 있다. 문구는 iOS 와 같은 `tr("한국어 원문")` 규칙이다(`src/i18n`).

## 폴더 이름

`apps/web` 은 임시 이름일 수 있다. 바꿀 때는 `git mv` 후 문서 경로만 고치면 된다(`scripts/paths.mjs` 가 깊이만 쓴다).
