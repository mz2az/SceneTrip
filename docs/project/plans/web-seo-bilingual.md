# 웹 검색 노출 — 영어 기본 + 한국어판 (`?lang=ko`)

웹앱 첫 화면(`https://app.scenetrip.io/`)이 검색 결과와 링크 미리보기에서 **영어 사용자에게는 영어로, 한국어 사용자에게는 한국어로** 보이게 한다.

## 1. 지금 (2026-11-21 확인)

- `scenetrip.io/`·`/index.html`(www 포함)은 Cloudflare 리디렉션 규칙으로 `https://app.scenetrip.io/` 에 301 로 넘어온다.
  `/index.html` 은 2026-11-21 에 추가했다 — 구글 결과가 GitHub Pages 랜딩의 `/index.html` 을 가리키고 있었다.
  `privacy.html`·`terms.html` 은 랜딩 그대로다.
- 웹앱 `index.html` 은 제목이 `SceneTrip` 한 단어이고 설명·`canonical`·`og:*` 가 없다.
  2026-11-14 커밋 `0996d3cb` 가 넣었고 2026-11-15 커밋 `2f10c4ab`(MZ2AZ-571·572)이 `index.html` 을 고치며 함께 지워졌다.
  `public/robots.txt`·`public/sitemap.xml`(첫 화면 1개)은 남아 있다.
- 웹앱은 저장된 언어가 없으면 **영어로 시작**한다(MZ2AZ-523, `src/i18n/index.ts`). 구글 봇도 저장된 언어가 없으니 영어 화면을 본다.
  그런데 정적 `index.html` 은 `<html lang="ko">` 다.
- Pages Function 은 `/v1/*`(API 중계)에만 붙어 있다(`public/_routes.json`).

## 2. 정한 것

| 주소 | 언어 | 역할 |
| --- | --- | --- |
| `https://app.scenetrip.io/` | 영어 | 기본판, `hreflang="en"` + `x-default` |
| `https://app.scenetrip.io/?lang=ko` | 한국어 | 한국어판, `hreflang="ko"` |

- **영어가 기본이다**(사용자 결정, 2026-11-21). 대상이 외국인 여행자이고 웹앱도 영어로 시작한다.
- 한국어판 주소는 경로(`/ko`)가 아니라 쿼리(`?lang=ko`)다. `/ko` 는 SPA 경로 규칙(react-router)과 겹친다. 구글은 쿼리 주소의 hreflang 도 받는다.
- 두 주소는 각자 자기 자신을 `canonical` 로 가리키고, 둘 다 서로를 `hreflang` 으로 가리킨다(구글 규칙: 양쪽에 같은 묶음).

## 3. 바꾸는 것

### 3-1. `index.html` — 영어 꼬리표 (기본)

- `<html lang="en">`.
- 영어 제목·설명·`canonical`(`https://app.scenetrip.io/`)·`og:*`·`twitter:card`.
- `hreflang` 세 줄(en, ko, x-default).
- 별도 표시 없이 꼬리표 자체(`name="description"`, `rel="canonical"`, `property="og:…"`)를 찾아 그 값만 바꾼다(`localizeHead`). 문구의 정본은 `src/seo/localizeHead.ts` 의 `SEO_COPY` 이고, `index.html` 의 영어 문구와 같아야 한다(`staticFiles.test.ts` 가 본다).

문구(초안):

| | 영어 | 한국어 |
| --- | --- | --- |
| 제목 | SceneTrip — K-Drama & Movie Filming Locations Trip Planner | SceneTrip — K-드라마·영화 촬영지 여행 코스 |
| 설명 | Stand where the scene happened. Pick a K-drama or movie and SceneTrip maps its real filming locations in Korea, then builds a trip with nearby food and cafés. | 드라마·영화 속 그 장면이 찍힌 곳으로. 작품을 고르면 촬영지와 주변 맛집·카페를 엮어 한국 여행 코스를 짜 드려요. |

### 3-2. Pages Function — 첫 화면만 한국어 꼬리표로 바꿔 내보낸다

- `functions/index.ts` 를 새로 둔다. `/` 요청에서 정적 `index.html` 을 받아(`context.next()`), `lang=ko` 이면 표시된 줄을 한국어로 바꾼다.
  바꾸는 것은 `html lang`·제목·설명·`canonical`·`og:url`·`og:title`·`og:description`·`og:locale` 이다. 그 밖이면 그대로 내보낸다.
- 바꾸기 로직은 순수 함수 `src/seo/localizeHead.ts`(문자열 → 문자열)로 두고 vitest 로 시험한다. Function 은 입구일 뿐이다(`/v1` 중계와 같은 구조).
- 자바스크립트로 바꾸지 않는 이유: 카카오톡·인스타그램 같은 링크 미리보기 봇은 자바스크립트를 돌리지 않는다.
- `public/_routes.json` 의 `include` 에 `"/"` 를 더한다. **첫 화면 요청만** Function 을 거치고 다른 정적 파일·SPA 경로는 지금처럼 Function 없이 나간다.
  Function 이 실패해도 정적 `index.html`(영어)이 나가게 한다(오류 시 `context.next()` 응답 그대로).

### 3-3. 웹앱 — `?lang=` 으로 시작 언어

- 주소에 `lang=ko|en` 이 있으면 그 언어로 시작하고 저장한다(언어를 고른 것과 같다, `chooseLanguage`). 다른 값은 무시한다.
- 주소의 `lang` 은 지우지 않는다(검색 결과 주소와 화면이 일치).

### 3-4. `public/sitemap.xml`

- 두 주소를 다 적고 각 항목에 `xhtml:link` 로 서로를 적는다.

## 4. 범위 밖

- 작품·촬영지·광고 랜딩(`/go/{작품}`) 같은 다른 경로의 언어별 꼬리표·사이트맵. 경로가 많아 서버에서 그리는 방식이 필요하다 — 따로 계획.
- 1200×630 공유 이미지. 지금은 앱 아이콘(512×512, `summary` 카드)을 쓴다.
- 구조화 데이터(JSON-LD). 첫 화면에 보이는 내용과 맞는 형식을 고른 뒤 따로 넣는다.
- Google Search Console 등록·사이트맵 제출 — 사람이 한다(§6).

## 5. 시험

- 단위(vitest): `localizeHead` — `ko` 이면 여덟 줄이 한국어로 바뀌고 다른 줄은 그대로, `en`·없음·이상한 값이면 원문 그대로, 표시가 빠진 HTML 에서도 깨지지 않음.
- 단위(vitest): 시작 언어 — 주소 `?lang=ko` 가 저장값보다 앞서고 저장된다, `?lang=xx` 는 무시.
- 빌드 결과 시험: `dist/index.html` 에 영어 꼬리표·`hreflang` 세 줄·`lang="en"` 이 있다(다른 작업이 또 지우면 시험이 깨지게).

## 6. 배포 뒤 확인

- `curl https://app.scenetrip.io/` 과 `curl 'https://app.scenetrip.io/?lang=ko'` 의 `<head>` 를 비교한다.
- Google Search Console URL 검사(실제 구글이 읽는 화면), 카카오톡 링크 미리보기.
- Search Console 에 `scenetrip.io` 도메인 등록(Cloudflare DNS TXT), `app.scenetrip.io/sitemap.xml` 제출, 두 주소 색인 요청.
- 검색 결과 반영은 며칠~몇 주. 순위 변화는 약속하지 않는다.
