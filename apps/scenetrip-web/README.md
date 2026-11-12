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
| `node scripts/shot.mjs <URL> <out.png>` | 설치된 Chrome 을 puppeteer-core 로 돌려 402×874@3x 스크린샷 (iOS 스크린샷과 같은 크기). `--scale 2`, `--click 글자`, `--eval 코드`, `--until domcontentloaded`(DOMContentLoaded 뒤 `--wait`(기본 1.5초)만큼 기다렸다가 캡처) |
| `node scripts/e2e-smoke.mjs [--base URL] [--only 이름,…] [--shots 폴더]` | 핵심 흐름(홈→탭→검색→작품·촬영지 상세→코스 목록·마법사·편집기→커뮤니티→마이페이지·시트)을 헤드리스 Chrome 으로 훑어 콘솔 오류·실패한 요청·4xx/5xx·깨진 이미지·쓰기/유료 호출을 센다. 하나라도 있으면 종료 코드 1. 개발 서버가 떠 있어야 한다 |

개발 서버에서만 URL 로 상태를 정할 수 있다(`src/devFlags.ts`): `?dev-lang=ko|en`.
계정·기록이 있어야 열리는 화면은 서버 쓰기 없이 **읽기 전용 가짜 응답**(`src/devMock.ts`)으로 연다: `?dev-account=1`(가짜 로그인 — 저장소에는 쓰지 않는다 — 과 `/me`·`/me/reviews`·`/me/blocks`·`/me/posts`), `?dev-stamps=1`(방문 기록이 찍힌 코스 `9001`), `?dev-stamps=active`(여행 중인 코스 `9001`). 둘 중 하나라도 켜면 GET 이 아닌 요청은 서버로 나가지 않고 204 로 돌아온다. 가이드 챗봇은 `?dev-fake-guide=answer|answer-text|slow|…`(`features/route/guide/devFake.ts`, 유료 `/guide/chat` 을 부르지 않는다).

가이드 답의 장소 카드 「여기로 길찾기」 는 담겨 있어도 늘 뜬다 — 로그인 전이면 길찾기 로그인 안내만, 코스에 없으면 먼저 담고, 여행 전이면 그곳으로 여행을 시작, 여행 중이면 목적지만 바꾼 뒤 가이드 창을 접는다(`guideRules.ts` 의 `guideDirectionsSteps`, iOS `RouteGuideDirections` 와 같은 값). 계획 `docs/project/plans/guide-title-info-directions.md` (MZ2AZ-554).
개발 서버는 DEV API 의 GET 200 응답을 10분 메모리에 둔다(`vite.config.ts`, DEV 분당 60회 한도 때문. `SCENETRIP_NO_API_CACHE=1` 로 끈다).
새 화면(시안, `features/proto/`)은 개발 서버에서 `?proto=a|b|off` 로 켜고, 시험용 웹 `dev.scenetrip.io` 에서는 기본으로 켜진다(`features/proto/protoGate.ts`, MZ2AZ-539). 운영 주소는 늘 시안 A 다(주소 값을 보지 않는다, 2026-11-11). 「시안」 표시·스위치는 개발 서버에서만 보인다. 커뮤니티 게시판·글 검색·좋아요·댓글, 편의시설 「골고루」·이름 검색, 정한 여행 기억은 서버(계약 1.23~1.25)를 부른다 — 계획 `docs/project/plans/web-proto-apply.md`(MZ2AZ-544).
시안 「작품으로」 의 「누구의 장면을 따라갈까요?」 는 `GET /contents` 를 30개씩 `total` 까지 이어 받고(격자 마지막 줄이 보이면 다음 쪽), 위 검색칸 「작품 이름으로 찾기」 는 0.3초 멈추면 서버 `q` 로 찾는다. 고른 작품은 검색·쪽 넘김과 상관없이 남고 AI 요청은 고른 작품 제목(없으면 인기 앞 3개)을 보낸다(`features/proto/useWorksBrowse.ts`·`worksBrowse.ts`). 코스 편집 지도 검색의 결과 줄은 왼쪽 점 없이 글만, 엔터 뒤 결과 서랍은 손잡이를 끌어 지도의 42%·78% 에 붙인다(빠르게 튕기면 그 방향, `searchDrawer.ts`). 계획 `docs/project/plans/motion-works-drawer-parity.md`(MZ2AZ-547).
지도 검색은 범위(bbox) 없이 보이는 지도(서랍 위) 가운데에서 가까운 순(`GET /pois?q&lat&lng&sort=distance`)으로 찾고, 목록 제목은 「가게·장소 · 가까운 순」, 없으면 「찾는 곳이 없어요」, 지도 핀은 결과 앞 100곳까지다(`searchPaging.ts`). 지도를 옮기면 「↻ 이 지역에서 다시 찾기」 가 새 화면 가운데로 다시 찾는다. 갈래 칩에는 개수를 붙이지 않는다. 시안 지도에는 해태를 그리지 않는다 — 가이드가 찾아 준 곳은 갈래 아이콘, 고른 곳은 같은 아이콘을 크게(26→40), 고른 코스 장소·안내 중인 목적지는 같은 번호 핀 1.4배, 담을까 보는 곳은 번호 없는 보라 핀, 핀 찍기 자리는 그 핀을 크게. 고른 곳·코스 장소로 옮길 때 화면 너비 3배 안이면 0.6초 감속, 밖이면 한 번에(`cameraMotion.ts`). 같은 계획 §8~§10.
시안 「도시로 시작 → 알아서 짜 주세요」 는 서버 AI 일정(`POST /guide/plan {city, days, pace}`, 계약 1.28.0)만 쓴다 — 도시 촬영지 목록을 미리 받지 않고, 못 받으면 「코스를 못 짰어요」 와 「다시 시도」. 「다른 곳으로 바꾸기」 는 그 카드 자리에서 가까운 그 도시 촬영지 10곳만 묻는다(`cityPlan.ts`). 꾹 눌러 순서 바꾸기는 없다. 카드 사진을 누르면 뒤집혀 장면 그림(모든 카드, 없는 쪽은 보라 바탕 「…아직 없어요」 면, `SceneFlipPhoto.tsx`·`sceneFlip.ts`). 계획 `docs/project/plans/city-ai-course.md` §5.
시안 「도시로 시작」(도시 → 며칠 → 어떻게 → 추천 코스)과 「작품으로」(여행 정보 → 작품 → 짜는 중)는 단계가 바뀔 때 새 단계가 오른쪽 24px 에서 밀려 들어오고 이전 단계는 왼쪽으로 밀리며 사라진다(0.28초, 뒤로는 반대). 위 단계 점도 같은 시간에 늘거나 준다. 동작 줄이기면 0.2초 서서히만(`features/proto/protoParts.tsx` 의 `StepTransition`, `proto.css`). 같은 계획 §5.

## 환경 변수

이름만 `.env.example` 에 있다. 값은 로컬 `.env.local` 에 둔다(커밋 금지).

- `SCENETRIP_API_TARGET` — 개발 서버 프록시 대상(기본 AWS DEV)
- `VITE_API_BASE` — 브라우저가 부르는 기준 주소(기본: 같은 출처)
- `VITE_BLOCK_WRITES=1`, `VITE_WRITE_ALLOW=<정규식>`, `VITE_BLOCK_PATHS=/guide,/navigation` — **검증 중 DEV 보호용 안전장치**. 켜면 GET 이 아닌 요청과 유료 경로를 클라이언트에서 막는다(`src/api/client.ts`).
- `VITE_NAVER_MAP_CLIENT_ID` — 지도 SDK 를 정한 뒤에 쓴다(미정).

Bazel 빌드(`just web-build`)는 호스트 환경 변수를 받지 않아 `VITE_*` 가 비어 있는 번들이 나온다(키가 들어간 배포 번들은 Cloudflare 가 `npm run build` 로 만든다).

## 구조

웹은 2026-11-03 첫 실행의 스플래시·언어 고르기·사용법을 없애고 바로 앱을 보여 준다(MZ2AZ-523). 저장된 언어가 없으면 영어로 시작하고 마이페이지 설정에서 바꾼다. 「사용법 다시 보기」는 마이페이지에 남기되 웹에서 `tutorial_begin`·`tutorial_complete` 는 보내지 않는다. iOS 도 2026-11-07 같게 했다(MZ2AZ-526).

홈 인사 아래 「도시의 성지」(MZ2AZ-531, 웹 DEV 실험)는 웹에만 있다 — 도시 칩(고정 서울·부산·경주·인천·제주, 그다음 촬영지 수 순)과 그 도시 촬영지 카드, 「지도에서 N곳 보기」(작품검색 탭의 「현 지도 내 성지 검색」). 도시 열쇠는 한국어 주소에서 얻으므로 `GET /places` 를 한국어로 끝까지 받고, 영어면 영어로도 받는다(API 창구는 호출부가 준 `Accept-Language` 를 덮어쓰지 않는다). 위치는 권한이 이미 허용됐을 때만 쓴다. 계획: [`docs/project/plans/web-home-city-spots.md`](../../docs/project/plans/web-home-city-spots.md).

계획 문서 §3. iOS 와 같은 구조: 탭 셋(작품검색·홈·커뮤니티) + 덮개 셋(경로여정 `/courses`·마이페이지 `/profile`·지금 뜨는 작품 전체 `/trending`). 경로는 `src/app/routes.ts` 에서만 만든다.

홈 「지금 뜨는 작품」의 「전체 보기」는 작품검색 탭이 아니라 `/trending` 덮개를 연다(MZ2AZ-534, 웹 DEV 실험). 선반과 같은 `GET /contents`(인기순)를
`limit=100` 으로 `total` 까지 `offset` 으로 이어 받아 포스터 격자로 그린다 — 앱 틀 폭 400px 이하 2열, 넘으면 3열. 포스터를 누르면 작품검색 탭의 작품 상세로 가고,
상세의 「뒤로」는 이동 state 의 `returnTo`(실어 연 작품 id 와 맞을 때만)를 보고 격자로 한 칸 물린다. 작품 상세 위 촬영지를 닫아도 표시는 남는다(`src/features/search/detailBack.ts`). 분석은 상세가 보내는 `view_title` 뿐이고 화면 이름은 `home` 으로 센다.
선반과 격자 모두 고정 작품(`src/features/home/pinnedWorks.ts` 의 `PINNED_CONTENT_IDS`, 지금 [32] 케이팝 데몬 헌터스)을 맨 앞에 두고 나머지는 인기순에서 중복을 뺀다. 목록에 없으면 `GET /contents/{id}` 로 받아 넣고, 실패하면 기존 순서다.
디자인 토큰은 `src/design/tokens.css` 에 iOS 소스의 파일:줄 출처와 함께 있다. 문구는 iOS 와 같은 `tr("한국어 원문")` 규칙이다(`src/i18n`).

촬영지 상세의 주 버튼은 「길찾기」다(MZ2AZ-532, DEV 실험 — [계획](../../docs/project/plans/web-place-directions.md)). 누르면 그 장소 하나로 된 코스(「{장소} 가는 길」)를 만들고
여행 중으로 바꾼 뒤 홈 「이어서 길찾기」와 같은 길(`pendingStore.tripStart` + `openCourse`)로 안내를 켠다. 비회원이면 로그인 시트를 이유 한 줄과 함께 띄우고
로그인되면 이어 간다. 「장바구니에 담기」는 보조 버튼이다. 웹에만 있는 문구의 영어는 `src/i18n/en.web.ts` 에 둔다(정본은 iOS 문구표).

코스 지도의 「내 위치」는 켤 때 받은 첫 자리가 시트 위 화면 밖이면 코스·미리보기(장소 선택 중이면 그곳)와 함께 담는다. 이후 위치 갱신은 점만 옮기며,
시트 높이만 바뀌면 350ms 동안 조용해진 뒤 마지막 높이로 한 번 맞추며, 켜진 내 위치도 함께 담아 첫 위치 맞추기를 유지한다.
권한 거부는 즉시, 첫 위치가 없으면 8초 뒤 지도 위에 안내한다(핀 찍기 안내 우선). 위치를 받은 뒤 권한이 거부돼도 안내하고 낡은 위치 점을 지운다.

원격 포스터·사진은 `<img loading="lazy">` 대신 `src/design/DeferredImg.tsx` 의 `DeferredImg` 로 그린다(MZ2AZ-558). 검색·홈 탭은 항상 마운트돼
`visibility:hidden` 으로만 숨고, 가로 줄(`.scroll-x`)의 화면 밖 포스터도 브라우저가 `loading="lazy"` 만으로는 받아 버리기 때문이다.
`DeferredImg` 는 `<img>` 를 처음부터 그리되 `src` 속성을 달지 않고, 감싼 탭이 보이고(`RootTabs` 의 `Layer` 가 내려 주는 `LayerVisibleContext`,
`Layer` 밖은 늘 보임) IntersectionObserver 가 화면 근처(rootMargin·scrollMargin `200px 120px` — scrollMargin 은 가로 줄·세로 목록 같은 스크롤 영역 안의 여유, 모르는 브라우저는 보이는 칸에 들어올 때)라고 알릴 때 단다. 한 번 단 `src` 는 떼지 않는다.
IntersectionObserver 가 없는 환경(jsdom 포함)에서는 탭이 보이면 바로 단다. 크기를 재야 하는 이미지(사진 뷰어)·로컬 그림(`/generated/…`)·
업로드 미리보기·이미 `active` 로 거르는 리뷰 사진(`src/features/reviews/PhotoParts.tsx`)은 일반 `<img>` 로 둔다.

## 폴더 이름

폴더 이름은 2026-10-31 `apps/web` 에서 `apps/scenetrip-web` 으로 바꿨다(`scripts/paths.mjs` 는 깊이만 쓴다).
