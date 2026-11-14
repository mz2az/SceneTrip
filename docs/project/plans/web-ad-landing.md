# 웹 — 광고로 처음 온 사람의 랜딩, 「더 담기」 타일, 다시 온 사람의 홈

- **상태**: 구현 중(2026-11-14 밤). dev 먼저.
- **티켓**: [MZ2AZ-572](https://mz2az.atlassian.net/browse/MZ2AZ-572).
- **근거**: 2026-11-14 사용자 — 광고를 작품 하나(케이팝 데몬 헌터스)로 좁힌다. 광고 영상은 그 작품을 보던 인물이 「저기 어디야?」 하며 우리 앱으로 서울을 찾아가는 이야기다. 광고를 누르면 웹앱이 열리고, **랜딩에서 그 장면 → 성지들이 펼쳐짐 → 지도에 꽂혀 장바구니에 담김** 이 움직임으로 보여야 한다. 거기에 식당·다른 작품을 네모 사진 칸으로 더 담고, 담은 것으로 여행 일정을 정한다. 다시 온 사람이 보는 홈도 그 이야기에 맞게 바꾼다.
- **앞선 것**: [담기 중심 흐름](web-keep-flow.md)(모두 담기·간직하기·내 지도), [편의시설 담기](keep-poi.md)(계약 1.31.0).

## 0. 다듬은 원칙

1. **움직임의 끝이 진짜 화면이다.** 보여 주고 끝나는 소개가 아니라, 멈춘 자리에서 단추 한 번으로 실제로 담긴다.
2. **자동으로 담지 않는다.** 움직임은 보여 주기이고 저장은 사용자가 누를 때 한다(들어온 사람 전원에게 담기 기록이 생기면 지표가 뜻을 잃는다).
3. **가볍다.** 영상 없이 사진과 CSS 움직임만. 전체 8초 안. 화면 아무 곳이나 누르면 바로 끝 장면으로 간다. `prefers-reduced-motion` 이면 처음부터 끝 장면이다.
4. **작품에 묶이지 않는다.** 주소가 작품을 정한다 — `/go/{contentId}`.

## 1. 랜딩 — `/go/:contentId` (전체 화면 덮개 `features/landing/LandingCover.tsx`)

광고의 도착 주소는 `https://app.scenetrip.io/go/32?utm_…` 다. 탭바·홈 줄은 보이지 않는다.

### 1-1. 받는 것

- `GET /contents/{id}`(제목·포스터), `GET /contents/{id}/places`(촬영지 전부 — 이름·좌표·실제 사진·장면 설명), 그리고 앞의 세 곳만 `GET /places/{id}` 로 **장면 사진**(`scenes[]` 중 이 작품의 `sceneImageUrl`)을 받는다. 셋은 함께 보낸다.
- 받는 동안: 보라 바탕에 해태와 `SceneTrip`(1초 안에 사라져야 한다 — 첫 그림이 오면 곧바로 ① 로).
- 작품이 없거나(404) 촬영지가 0곳이거나 받기에 실패하면 랜딩을 접고 홈(`/`)으로 보낸다(주소도 바꾼다). 목록에서 빠진 작품도 같다.
- 이 작품의 촬영지를 이미 전부 담은 사람은 랜딩을 건너뛰고 내 지도(`/my-map`)로 간다.

### 1-2. 세 박자 (`features/landing/landingScript.ts` — 순수 함수, 시험 대상)

```ts
export type Beat = 'scene' | 'spread' | 'rest'
export interface LandingTimeline { sceneMs: number; spreadMs: number; perCardMs: number; total: number }
export function landingTimeline(placeCount: number): LandingTimeline
export function beatAt(elapsedMs: number, t: LandingTimeline): Beat
export function landedCount(elapsedMs: number, t: LandingTimeline, placeCount: number): number
export function pinLayout(places: readonly { latitude: number; longitude: number }[], box: { width: number; height: number; pad: number }): { x: number; y: number }[]
```

- `landingTimeline(n)`: `sceneMs` = 1600. 카드 한 장이 날아가 꽂히는 간격 `perCardMs` = `clamp(3200 / n, 180, 420)`(정수로 내림). `spreadMs` = 700(펼쳐지는 시간) + `perCardMs × n`. `total` = `sceneMs + spreadMs`. `n` 이 0 이면 `spreadMs` = 0.
- `beatAt`: `elapsed < sceneMs` → `scene`, `< total` → `spread`, 그 뒤 → `rest`.
- `landedCount`: 지도에 꽂힌 수. `scene` 과 펼쳐지는 700ms 동안은 0, 그 뒤 `perCardMs` 마다 1씩, 최대 `placeCount`. `rest` 는 언제나 `placeCount`.
- `pinLayout`: 지도가 아직 없을 때 쓰는 자리. 좌표의 최소·최대를 `box` 안(가장자리 `pad`)에 비율로 놓는다. 북쪽이 위(위도가 클수록 y 가 작다), 동쪽이 오른쪽. 점이 하나거나 모두 같은 좌표면 가운데. 가로·세로 비율은 따로 늘린다(상자를 채운다).

| 박자 | 보이는 것 |
| --- | --- |
| ① `scene` | 장면 사진이 화면 가득(없으면 포스터를 흐리게 깐 바탕). 아래에 작품 제목과 `say('이 장면, 진짜 있는 곳이에요', 'This scene is a real place')`. 위쪽 작게 해태 + `SceneTrip` |
| ② `spread` | 장면 사진이 작은 카드로 줄며 실제 장소 사진으로 뒤집히고(`features/proto/SceneFlipPhoto` 와 같은 뒤집기), 촬영지 카드들이 부채처럼 펼쳐진 뒤 한 장씩 지도의 제자리로 날아가 핀이 된다. 아래 「내 지도」 접시의 수가 `landedCount` 로 오른다 |
| ③ `rest` | 핀이 다 꽂힌 지도. 위에 제목 `say('{작품}의 실제 장소 {N}곳', '{N} real places from {작품}')`. 아래 주 단추 하나 `say('이 {N}곳 내 지도에 담기', 'Keep these {N} on my map')`(영어 1곳이면 `Keep this 1 on my map`), 그 아래 작은 글자 단추 `say('먼저 둘러볼게요', 'Just looking')` |

- 화면 아무 곳이나 누르면(단추 제외) `rest` 로 건너뛴다. 오른쪽 위 `say('건너뛰기', 'Skip')` 도 같다.
- 지도: 진짜 지도(검색 탭과 같은 `MapView`)를 ①·② 동안 밑에서 미리 띄운다. ③ 에 지도가 준비돼 있으면 진짜 핀, 아니면 `pinLayout` 자리의 민무늬 바탕에 꽂아 두고 준비되면 바꾼다.
- 사진은 썸네일(`imageThumbnailUrl ?? imageUrl`)을 쓴다. 장면 사진만 큰 것.

### 1-3. 누른 뒤

- `담기` → `cartActions.addMany(전부, contentId, …, 'landing')` — 단추가 `say('담는 중… 3/10', 'Saving… 3/10')`. 끝나면(`failed === 0`) 「더 담기」(§2)로 간다. 실패가 있으면 그 자리에 남고 알림과 「남은 N곳도 담기」.
- `먼저 둘러볼게요` → 그 작품의 상세(`/search/content/{id}`)로 간다(거기에도 「모두 담기」 가 있다).
- 분석: 랜딩이 뜨면 `landing(contentId, 'view')`, 건너뛰면 `'skip'`, ③ 에 닿으면 `'rest'`, 담기 `'save'`, 둘러보기 `'browse'`. `save_all` 의 `source` 에 `landing` 을 더한다.

## 2. 「더 담기」 — `/add` (덮개 `features/keep/AddMoreCover.tsx`)

담은 뒤 시트 ① 을 대신한다. 작품 상세의 「모두 담기」 뒤, 랜딩의 담기 뒤, 내 지도의 「더 담기」 단추가 모두 여기로 온다.

- 머리줄 `say('내 지도 · N곳', 'My map · N spots')` 과 뒤로, 그 아래 한 줄 `say('더 담아 볼까요?', 'Add more to your map')`.
- **다른 작품** `say('다른 작품의 촬영지', 'Spots from other shows')`: 포스터 네모 칸 3열 격자, 최대 9편(`moreWorks(지금 뜨는 작품, 이미 담은 작품들, 9)`). 칸을 누르면 그 작품 촬영지를 `addMany(…, 'more')` 로 담고 칸에 `✓ +N`(받는 중 표시, 실패는 `say('담지 못했어요', "Couldn't save")` 뒤 다시 누를 수 있다). `keep_more`.
- **주변** 세 줄 — `say('주변 쇼핑', 'Shopping nearby')`, `say('주변 명소', 'Sights nearby')`, `say('주변 식당·카페', 'Food & cafés nearby')`: 줄마다 옆으로 넘기는 네모 칸 최대 12곳. 담은 곳 둘레(`nearbyBbox`)에서 `GET /pois`(`sort=spread`)로 받는다.
  - 쇼핑·명소는 **사진이 있는 곳만**(`hasImage=true`) 받아 사진 칸으로 보인다. 식당·카페는 사진이 없는 자료라 분류 아이콘 칸으로 보인다.
  - 칸: 사진(또는 아이콘), 이름(`displayName ?? name`), 분류 이름. 누르면 담긴다(`addPoi`) — 칸에 `✓`. 한 번 더 누르면 뺀다(`removePoi`). 줄 끝의 칸 `say('지도에서 더 보기', 'More on the map')` 은 내 지도를 그 칩이 켜진 채로 연다.
  - 그 둘레에 없으면 그 줄을 그리지 않는다.
- 아래 고정: 주 단추 `say('이 N곳으로 여행 일정 짜기', 'Plan my trip with N spots')`(영어 1곳이면 `1 spot`) → 간직하기(§3) → 계획. 보조 `say('내 지도 보기', 'See my map')` → `/my-map`.
- 분석: 뜨면 `add_more('view')`, 주변 칸으로 담으면 `save_poi`(이미 있음) + `add_more('poi')`, 일정 짜기 `add_more('plan')`.
- 담은 뒤 시트(`KeepSheet`)의 ① 단계는 없앤다. `keepActions.openAfterSave` 는 「더 담기」 로 보내는 것으로 바꾼다. ②(간직하기)·③(계획)은 그대로 쓴다 — 「일정 짜기」 를 누르면 ② 부터 연다(홈 화면에 이미 설치된 환경이면 ③).

## 3. 간직하기는 그대로, 한 줄을 더한다

- 앱 안 브라우저에서 「더 담기」 와 내 지도의 머리줄 아래에 얇은 띠: `say('이 창을 닫으면 사라져요', 'Saved only in this window')` + `say('브라우저에서 열기', 'Open in browser')`(누르면 §5-2 ② 의 넘기기와 같다, `keep_prompt(env, 'open_browser')`). 앱 안 브라우저가 아니면 띠가 없다.

## 4. 다시 온 사람의 홈

위에서부터. 지금의 「어디로 여행 가세요?」 사진 카드는 맨 위에서 내린다.

| 순서 | 담은 것이 있을 때 | 담은 것이 없을 때 |
| --- | --- | --- |
| 1 | 인사 머리줄(`SceneTrip`) | 같음 |
| 2 | **내 지도** 카드(지금 것): 수·작품 칩·사진 줄·`지도에서 보기`·`일정 짜기` | **작품 고르기** `say('좋아하는 작품에서 시작하세요', 'Start with a show you love')`: 포스터 네모 칸 3열 6편, `전체 보기`. 칸을 누르면 그 작품의 랜딩(`/go/{id}`) |
| 3 | **더 담기** 줄 `say('더 담아 볼까요?', 'Add more')`: 포스터 칸 옆으로 넘기기(아직 안 담은 작품) + 끝에 `say('주변 식당·쇼핑', 'Food & shopping nearby')` 칸 → `/add` | **오늘의 성지**(지금 것) |
| 4 | 내 여행(코스 카드 — 코스가 있을 때만) | **도시로 찾기**: 도시 칩 줄(지금 사진 카드의 칩만 남긴다, 한 줄) |
| 5 | 오늘의 성지 | 커뮤니티 지금 |
| 6 | 커뮤니티 지금 | 내 기록 |
| 7 | 내 기록 | |

- 떠 있는 「Plan a trip」 줄: 담은 것이 있으면 `say('일정 짜기', 'Plan my trip')`(→ 간직하기를 거치지 않고 바로 「담은 곳으로」 마법사), 없으면 지금 그대로.
- 홈의 포스터를 누를 때: 담은 것이 **없는** 사람은 랜딩(`/go/{id}`), 있는 사람은 지금처럼 작품 상세.
- 「지금 뜨는 작품」 줄은 위 2·3번이 대신한다(같은 자료다). 전체 보기는 지금의 `/trending`.
- 분석: `home_tap` 의 `section` 에 `pick_title`·`add_title`·`add_nearby`·`city_chip` 을 더한다.

## 5. 계약 1.31.0 에 더하는 것 (서버)

「더 담기」 의 사진 칸 때문에 편의시설 목록이 사진을 같이 줘야 한다.

- `PoiSummary.imageUrl`(nullable, uri): 그 편의시설의 대표 사진 — 우리가 가진 사진(`poi_image`, 관광공사)의 첫 장. 없으면 null. `GET /pois`·`GET /pois/{id}`·`CartPoi` 에 모두 온다. 네이버에서 온 카드 사진(`PoiCard.images`)과는 다른 것이다.
- `GET /pois` 의 새 쿼리 `hasImage`(boolean): `true` 면 `imageUrl` 이 있는 것만. `false`·없음이면 거르지 않는다. `total` 과 `sort=spread` 의 칸 순위도 거른 뒤 기준이다.

## 6. 분석 이벤트

| 이벤트 | 함수 | 매개변수 |
| --- | --- | --- |
| `landing` | `webEvent.landing(contentId, action)` | `content_id`, `action` = `view`·`skip`·`rest`·`save`·`browse` |
| `add_more` | `webEvent.addMore(action)` | `action` = `view`·`poi`·`plan` |
| `save_all` (있음) | — | `source` 에 `landing` |
| `home_tap` (있음) | — | `section` 에 `pick_title`·`add_title`·`add_nearby`·`city_chip` |

`screen_view` 의 화면 이름: `/go/…` 는 `landing`, `/add` 는 `add_more`.

## 7. 상태

| 화면 | 받는 중 | 비었음 | 실패 | 됨 |
| --- | --- | --- | --- | --- |
| 랜딩 | 보라 바탕 + 해태 | 촬영지 0곳 → 홈으로 | 홈으로 | 세 박자 |
| 랜딩의 담기 | 「담는 중… 3/10」 | — | 알림 + 「남은 N곳도 담기」 | 「더 담기」 로 |
| 더 담기 | 칸 자리 회색 | 다른 작품이 없으면 그 구획을 뺀다, 주변이 없으면 그 줄을 뺀다 | 구획마다 `say('불러오지 못했어요', "Couldn't load")` + 다시 시도 | 칸들 |
| 홈 | 지금처럼 | 위 표의 오른쪽 열 | 지금처럼 | 위 표 |

## 8. 하지 않는 것

- 영상, 소리, 새 의존성(애니메이션 라이브러리). iOS·Android.
- 랜딩에서 로그인·언어 고르기·권한 요청.
- 광고 소재와 광고 설정.

## 9. 디자인 기준 (2026-11-14 밤, VisitKorea·Visit Japan·Visit Seoul 모바일 조사 반영 — 앞 절과 다르면 이 절이 이긴다)

조사에서 본 것: 세 사이트 모두 첫 화면은 정지 사진 슬라이더이고(영상 없음), **찜 피드백이 약하다**(하트 색만 바뀜, 숫자·토스트·움직임 없음), 담은 것을 지도로 보여 주는 곳은 VisitKorea 일정(로그인 필요)뿐이다. 「장면 → 실제 장소」 를 움직임으로 보여 주는 곳은 없다. 우리가 앞설 곳이 여기다. 참고 스크린샷: 스크래치패드 `ref/` (vk·jnto·vs).

1. **랜딩 ① 은 사진이 화면을 꽉 채운다**(Visit Japan 히어로처럼). 위에 작게 `SceneTrip`, 아래 큰 흰 글씨 제목. 버튼 없음.
2. **② 의 뒤집기는 같은 자리에서 장면 → 실제 사진**(교차 전환 약 0.9초), 캡션은 「작품 장면 이름 → 영어 장소명 (한글)」 병기 — 예 `Rumi & Jinu sing "Free" → Naksan Park (낙산공원)`. 장면 설명이 길면 첫 문장의 앞 40자.
3. **담기 피드백을 우리 무기로**: 담는 순간마다 (a) 사진·카드가 화면 위쪽의 「내 지도」 단추로 날아가고, (b) 그 단추의 숫자 배지가 튀어 오르고, (c) 알림 `say('내 지도에 담았어요', 'Added to my map')` + `say('되돌리기', 'Undo')`(3초). 랜딩·작품 상세·더 담기·정보 카드 어디서 담든 같은 움직임이다. 확인 창은 띄우지 않는다.
   - 「내 지도」 단추는 랜딩 ③ 뒤 모든 화면 머리줄 오른쪽에 같은 자리로 있다(배지 = 담은 수). 떠 있는 단추를 여럿 겹치지 않는다 — 홈의 떠 있는 줄은 이 단추와 겹치지 않게 「일정 짜기」 하나만.
4. **더 담기는 모자이크 격자**(Visit Japan 「Trending」·「Explore by Interest」): 맨 위 큰 가로 카드 1장 + 그 아래 정사각 2열. 사진 위 왼쪽 아래 굵은 흰 글씨(아래쪽 어둡게 깐 그라데이션), 칸 오른쪽 위에 원형 `＋` 단추 — 상세를 열지 않고 바로 담는다(담기면 `✓`). 다른 작품 칸은 포스터, 주변 칸은 사진(없으면 분류 아이콘 칸).
5. **내 지도는 VisitKorea 일정 구조**: 지도가 위에 고정되고 아래 목록이 스크롤된다. 목록에서 보고 있는 줄의 핀이 커지고 강조된다(스크롤과 지도가 함께 움직인다). 줄 사이에 다음 장소까지의 거리 칩 `0.5 km ›`(직선 거리, 1km 미만은 m). 번호 핀과 점선은 「일정」 이 생긴 뒤(코스) 화면의 몫이고 내 지도는 번호 없는 핀.
6. **이름 병기**: 촬영지는 화면 언어 이름 아래에 한글 이름을 작게(영어 화면일 때만) — VisitKorea 의 「Lotte World (롯데월드)」. 편의시설은 keep-poi §9-2(로마자).
7. **줄마다 「장면이 있던 곳」 이 남게**: 내 지도의 촬영지 줄 썸네일은 실제 사진 위에 작은 장면 사진을 모서리에 겹친다(장면 사진이 있을 때). 왜 담았는지가 남는다.
8. **홈 첫 칸**: 담은 것이 없는 사람에게는 지금 뜨는 작품 1편의 장면 사진이 꽉 찬 카드 + 단추 하나 `say('이 작품의 장소 {N}곳 보기', 'See {N} places from this show')`(→ 랜딩). 그 아래가 §4 의 작품 고르기 격자. 담은 것이 있으면 §4 표 그대로(내 지도 카드가 첫 칸).
9. **피할 것**: 첫 화면을 공지·설문·챗봇이 가리는 것, 담을 때 단계를 하나 더하는 것(권역 고르기 창 등), 로그인 벽.

## 10. 느린 폰에서도 (2026-11-15, 독립 검증 — 앞 절과 다르면 이 절이 이긴다)

실측(페이스북 안드로이드 인앱 UA, CPU 4배 느림, Fast 3G, 새 프로필, 운영 빌드): 흰 화면 4.0초 → 보라 대기 8.7초까지 → ① 은 장면 사진 없이 검은 바탕 → ② 카드·핀이 빈 채로 날아감 → 담기 단추 14.5초. 빠른 환경은 0.6초에 장면, 6.4초에 단추. 원인은 (1) 장면 사진이 원본 PNG(장당 2~3MB), (2) 랜딩 밑에서 홈·다른 탭이 API 15건과 사진·지도 타일을 같이 받음, (3) JS 한 덩어리 1.55MB(gzip 480KB)에 첫 화면 HTML 없음.

### 10-1. 목표 (같은 느린 조건)

| 시점 | 목표 |
| --- | --- |
| 보라 바탕 + 해태 + `SceneTrip` 가 보인다 | 1.5초 안 (JS 를 받기 전 — `index.html` 에 그린다) |
| ① 장면 사진이 화면에 있다 | 4초 안 |
| ③ 담기 단추 | 9초 안 (건너뛰기를 누르면 즉시) |

### 10-2. 서버·계약 1.32.0 (더하기만, 필수 칸 없음)

- `Scene.sceneImageThumbnailUrls`(문자열 배열, `sceneImageUrls` 와 같은 순서·같은 길이, 작은 판이 없는 장은 그 자리에 원본 주소) 와 `Scene.sceneImageThumbnailUrl`(첫 장). 작은 판은 긴 변 720px JPEG, 촬영지 사진과 같은 방식(`just place-thumbnails` 도구가 `scene/…` 도 줄여 `scene/thumb-720/…` 에 덮어쓰기 없이 올리고 결과를 seed TSV 로 남긴다 — `PlaceThumbnails` 가 지금 `scene/` 을 일부러 빼고 있다).
- `PlaceSummary.nameKo`·`CartItem.nameKo`(nullable): 촬영지의 한국어 이름(`place_i18n` 의 `ko`). 화면 언어가 한국어가 아닐 때 이름 아래에 작게 병기한다(§9-6).
- `GET /contents/{id}/places` 의 항목에 `sceneImageThumbnailUrl`(nullable): 그 작품의 그 촬영지 장면의 첫 장 작은 판 — 랜딩이 `GET /places/{id}` 를 따로 세 번 부르지 않게.

### 10-3. 웹

1. **첫 화면을 HTML 에**: `index.html` 의 `#root` 안에 보라 바탕 + 해태(인라인 SVG 나 작은 WebP — 10KB 이하) + `SceneTrip` 을 CSS 만으로 그려 둔다. 주소가 `/go/` 로 시작할 때만 보이고(인라인 스크립트로 `document.documentElement` 에 표시), 그 밖의 주소는 지금처럼. React 가 그리면 사라진다.
2. **랜딩 청크 나누기**: `/go/` 로 시작하면 랜딩 코드만 먼저 받고(동적 `import()`), 홈·탭·지도 SDK·분석 라이브러리는 랜딩 ③ 이 그려진 뒤(또는 2초 뒤 한가할 때) 받는다. 랜딩이 떠 있는 동안 **홈·다른 탭을 붙이지 않는다**(API·사진·지도 타일 요청 0).
3. **사진 크기**: 랜딩·내 지도·홈의 장면 사진은 `sceneImageThumbnailUrl(s)` 를 쓴다(없으면 원본 — 옛 서버). ① 의 꽉 찬 장면만 작은 판을 먼저 그리고 원본으로 바꾸지 않는다(720px 이면 충분하다). ② 카드·핀은 촬영지 썸네일. ③ 의 72px 모서리·내 지도의 28px 모서리도 작은 판. 랜딩의 사진은 ① 장면 → ② 첫 카드 3장 → 나머지 순으로 받기(`fetchpriority`, 늦은 것은 `loading=lazy` 아님 — 미리 `new Image()` 로 순서대로).
4. **기다림 없는 시계**: ① 은 장면 사진이 오면 시작한다. 1.2초 안에 안 오면 포스터(작품 상세의 `posterUrl`, 이미 작다)로 ① 을 시작하고, 장면 사진이 오면 바꿔 끼운다. ② 의 카드는 사진이 온 것부터 날린다(빈 카드를 날리지 않는다 — 안 온 카드는 순서를 뒤로).
5. **캡션**: 장면 설명의 상투구를 떼고(`This is the backdrop for the scene where`, `This is where`, `It appears in`, `A scene was filmed here in which`, 한국어 `…장면의 배경이다` 등 — 규칙은 `landingScript.ts` 의 순수 함수 `sceneCaption(text, lang)`) 앞 60자. 비면 `say('{작품}의 장면', 'A scene from {작품}')`. 그 아래 장소명, 영어 화면이면 `nameKo` 병기.
6. **본 랜딩**: 이 탭에서 끝까지(③) 본 작품은 `sessionStorage` 에 적고, 다시 들어오면 ③ 부터(이벤트 `landing(id,'view')` 는 다시 보내지 않는다).
7. **떠난 뒤 끌려가지 않기**: 담는 중에 랜딩을 떠나면 담기는 끝까지 하되 `/add` 로 보내지 않는다(그때는 알림만).
8. **광고 꼬리표 지키기**: 앱이 뜰 때 주소의 `utm_*` 를 세션에 적고 PostHog 의 super property·GA4 의 이후 이벤트 매개변수에 싣는다(첫 `screen_view` 가 나가기 전에 주소가 바뀌어도 잃지 않게). 랜딩의 다른 주소로 보내는 이동(404·다 담음·담기 뒤)은 이것으로 충분하다.
9. **날아가기는 실패하면 안 날린다**: 저장 결과를 받은 뒤 날린다(랜딩은 진행 중에 담긴 것부터 하나씩). 작품 상세 머리줄에도 「내 지도」 단추를 둔다(날아갈 곳). 오늘의 성지·장소 상세의 담기도 같은 움직임.
10. **비싼 효과 빼기**: 랜딩의 `filter: blur`·`backdrop-filter`·`border-radius` 전환을 빼거나 transform 으로 바꾼다.
11. **아래쪽 겹침**: 담은 사람의 홈에서 가입 알약·「Plan my trip」·탭바가 겹쳐 쌓이지 않게 — 가입 알약은 「Plan my trip」 위로 올리거나, 담은 사람에게는 알약을 「Plan my trip」 옆 작은 원으로.
12. **주변 칸 되누름**: 촬영지와 연결된 편의시설 칸을 다시 눌러도 그 촬영지를 빼지 않는다(편의시설 칸은 담기만, 빼기는 내 지도에서).

## 11. 랜딩 = 앱의 첫 화면 (2026-11-15 사용자 정정 — 앞 절 전부보다 이 절이 이긴다)

사용자: 「랜딩페이지는 그냥 앱 시작 전에 앱 첫 화면을 말한 것」. 따로 떨어진 광고 전용 주소가 아니다.

- **언제 뜨나**: 앱을 처음 여는 사람에게, **어느 주소로 들어오든**(`/`, 광고 링크, 작품·장소 주소 모두) 앱이 뜨기 전 첫 화면으로 뜬다. 앱(지금 주소의 화면) 위에 전체 화면 덮개로 그리고, ③ 에서 담거나 「먼저 둘러볼게요」 「건너뛰기」 를 누르면 덮개가 걷히며 **그 자리의 앱**이 보인다(주소를 바꾸지 않는다 — 담기를 누른 경우만 「더 담기」 `/add` 로).
- **누구에게**: 처음 온 사람 = 이 브라우저에서 첫 화면을 끝까지 보거나 건너뛴 적이 없고(`localStorage` `scenetrip.intro.done`), 담은 것(촬영지·편의시설)이 없고, 코스가 없다. 하나라도 있으면 덮개 없이 바로 앱이다. 로그인한 사람도 덮개 없음.
- **무슨 작품**: 주소의 `?show={contentId}` 가 있으면 그 작품, 없으면 기본 작품(설정값 `INTRO_CONTENT_ID = 32`, 케이팝 데몬 헌터스 — 지금 광고 작품). 기본 작품을 받지 못하거나 촬영지가 없으면 덮개 없이 바로 앱.
- **`/go/{id}`**: 남겨 두되 `/?show={id}` 와 같은 뜻으로 바꾼다(첫 화면 덮개 + 그 밑은 홈). 이미 본 사람이 `/go/{id}` 로 오면 그 작품 상세로 간다.
- **덮개 밑의 앱**: 덮개가 떠 있는 동안 밑의 앱은 그리지 않는다(§10-3-2 — API·사진 요청 0). 덮개가 걷히기 직전(③ 이 그려진 뒤)부터 밑의 앱을 준비한다.
- §10-3-1 의 HTML 첫 화면(보라 바탕 + 해태 + SceneTrip)은 처음 온 사람일 가능성이 있을 때(`scenetrip.intro.done` 이 없을 때) 그린다 — 인라인 스크립트가 `localStorage` 를 읽어 정한다.
- §10-3-6 의 「본 랜딩」 은 이 `scenetrip.intro.done` 으로 바뀐다(탭이 아니라 브라우저 기준, 한 번).
- 분석: 이벤트 이름 `landing` 그대로, `screen_view` 의 화면 이름은 `intro`.
