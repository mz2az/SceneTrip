# 촬영지 사진에 출처를 붙인다

- **상태**: 적용(2026-11-03) — 로컬. DEV 는 main 에 합친 뒤 데이터 적재, PRD 는 다음 PRD 배포 묶음(서버 이미지·V42·데이터 적재를 함께)
- **티켓**: MZ2AZ-514 를 넓힌다(처음 범위는 「같은 곳으로 이어진 편의시설의 관광공사 사진을 출처와 함께」)
- **왜**: 사진 없는 촬영지 123 곳 중 60 곳에 쓸 수 있는 사진 151 장을 찾았는데([사진 채우기](place-photo-refresh.md)),
  전부 출처 표시가 이용 조건이다. 촬영지 사진에는 출처를 담을 자리가 없어 지금은 넣을 수 없다

## 1. 지금 상태

| 무엇 | 촬영지 사진 | 편의시설 사진 |
| --- | --- | --- |
| DB | `place_image(url, sort_order)` — 출처 칸 없음 | `poi_image(url, credit, license, thumbnail_url, …)` |
| 사진첩 응답 `photos[]`(`Photo`) | `credit`·`license`·`thumbnailUrl` 을 늘 `null` 로 보낸다(`ReviewStore.photos`) | 칸을 채워 보낸다 |
| 앱 | 세 앱 모두 상세 사진첩을 `photos` 로 그리고, 출처가 있으면 사진 곁에 보인다 — 편의시설 관광공사 사진이 이미 그렇게 나온다(2026-11-02 코드 확인: iOS `PhotoPager`, Android `PhotoGallery`, 웹 `PhotoCredit`) | 같음 |

그래서 **서버가 칸을 채우기만 하면 앱 수정 없이 출처가 나온다.** 계약(`Photo`)도 이미 그 칸을 갖고 있다.

## 2. 바꾸는 것

### 2.1 DB — `V42__place_image_credit.sql`

`place_image` 에 세 칸을 더한다. 모두 비워 둘 수 있다(기존 네이버 업체 사진·편집본은 비어 있다).

| 칸 | 뜻 |
| --- | --- |
| `credit TEXT` | 사진 곁에 보일 출처 문구. 예: `한국관광공사` · `서울역사박물관` · `Christophe95 · CC BY-SA 4.0 · 위키미디어 공용` |
| `license TEXT` | `kogl_type1`·`kogl_type3` 만(편의시설과 같은 제약). **한국관광공사 사진에만 채운다** — 아래 |
| `thumbnail_url TEXT` | 작은 사진. 관광공사 사진만 관광공사가 만든 썸네일이 있다 |

`license` 를 관광공사 사진에만 채우는 이유: 세 앱 모두 `license` 가 있으면 출처를 **「출처: 한국관광공사」 로 고정해** 적고
`credit` 은 그 아래 덧붙인다(iOS `PhotoGalleryRules.credit`, Android `ReviewRules.credit`, 웹 같음). 서울역사박물관·문화재청의
공공누리 사진에 `license` 를 채우면 「출처: 한국관광공사」 가 잘못 나온다. 그래서 그 사진들과 위키미디어 사진은 `license` 를 비우고
조건을 `credit` 문구 하나에 다 쓴다 — `license` 가 없으면 앱은 `credit` 을 그대로 보인다.

| 사진 | `license` | `credit` 예 |
| --- | --- | --- |
| 관광공사 | `kogl_type1`/`kogl_type3` | `한국관광공사` (앱이 언어에 맞춰 「출처: 한국관광공사」 로 적는다) |
| 서울역사박물관·문화재청 | 비움 | `출처: 서울역사박물관 · 공공누리 제1유형` |
| 위키미디어 | 비움 | `Christophe95 · CC BY-SA 4.0 · 위키미디어 공용` (고쳤으면 `· 사람을 지움`) |

`PhotoLicense` 값을 늘리지 않는 이유도 같다 — 계약의 값이 그 둘뿐이고, 늘리면 앱의 생성 클라이언트가 모르는 값을 만나 해석에
실패할 수 있어 세 앱을 함께 고쳐야 한다. `credit` 문구는 언어를 가리지 않고 그대로 나간다(출처 이름은 번역하지 않는다).

### 2.2 데이터 — `seed/place_image_credits.tsv`

`candidates.csv` 의 칸은 늘리지 않는다 — 다른 수집분 CSV 도 같은 칸 수여야 하는 규칙이 있다(`seed.sh`). 대신 사진 주소를 열쇠로 하는
파일을 하나 둔다.

```
url	credit	license	thumbnail_url
https://tong.visitkorea.or.kr/cms/resource/46/1902246_image2_1.jpg	한국관광공사	kogl_type3	https://tong.visitkorea.or.kr/…_image3_1.jpg
https://scenetrip-media-prod.s3…/place/credited-2026-11-02/<sha>.jpg	Christophe95 · CC BY-SA 4.0 · 위키미디어 공용 · 사람을 지움		
```

- `candidates.sql` 이 `place_image` 를 넣은 뒤, 이 파일을 `/tmp/place-image-credits.tsv` 에서 읽어 주소가 같은 줄에 세 칸을 채운다
  (`place_poi_links.tsv` 와 같은 방식).
- 파일을 놓는 곳: 로컬 `tools/scripts/seed.sh`(kind·파드 두 경로), 원격 `seed/remote-load.sh`, Bazel `data`(`services/scene-api/BUILD.bazel`).
- 파일에 없는 주소는 세 칸이 비어 있다 — 지금과 같다.

### 2.3 응답 — `ReviewStore.photos`

촬영지 쪽 `SELECT` 를 `place_image` 의 실제 칸으로 바꾼다(`NULL::TEXT AS credit` 등을 지운다). 상세의 `photos`·`GET /places/{id}/photos`
둘 다 이 한 곳을 지난다. 계약은 바뀌지 않는다 — `Photo` 설명의 「관광공사 사진에만 있다」 같은 문구만 촬영지까지 넓혀 고친다(문서 수정, 버전 그대로).

## 3. 사진 준비

| 종류 | 장수 | 어떻게 |
| --- | --- | --- |
| 관광공사 3유형(변경 금지) | 33 | **원본 주소를 그대로** 쓴다(편의시설과 같음). 사람이 보이면 뺀다 — 지우는 것도 변경이다 |
| 관광공사 1유형 | 54 | 사람이 보이면 지운다. 우리 저장소에 JPEG 1280px 로 올린다 |
| 위키미디어(CC BY·BY-SA·CC0·퍼블릭 도메인·공공누리 1유형) | 59 | 같음. BY-SA 를 고치면 `credit` 에 「사람을 지움」 을 붙인다(변경 사실 표시) |
| 서울역사박물관(공공누리 1유형) | 5 | 같음 |

- 올리는 곳: `scenetrip-media-prod` 의 새 경로 `place/credited-2026-11-02/<원본 주소 SHA-256>.jpg`, 덮어쓰기 금지.
- 관광공사 사진 중 사람을 지우지 않은 것(3 유형 32 장, 1 유형 23 장)은 올리지 않고 관광공사 주소를 그대로 건다. 작은 사진은 관광공사가 따로 준 것만 넣는다.
- 이용 조건은 넣기 전에 기계로 다시 확인했다(2026-11-02): 관광공사 87 장은 우리 DB `poi_image.license` 와 같고,
  위키미디어 59 장은 공용 API 의 라이선스와 같다. 서울역사박물관 5 장은 페이지의 공공누리 1유형 문구를 확인했다.

## 4. 알려진 한계

- **목록·지도 카드의 대표 사진에는 출처가 안 보인다.** 응답의 `imageUrl`(문자열 하나)에 출처 칸이 없다. 출처는 상세 사진첩에서 보인다.
  편의시설의 관광공사 사진도 지금 같은 방식이다. 카드에도 붙이려면 계약·세 앱을 고쳐야 해서 이번 범위에서 뺀다.
- 3유형 사진은 관광공사 서버에서 바로 받는다 — 관광공사가 지우면 깨진다(편의시설과 같은 위험).

## 5. 시험

- 통합 레인: `place_image` 에 출처가 있는 줄을 넣으면 `ReviewStore.photos(PLACE, …)` 가 `credit`·`license`·`thumbnailUrl` 을 내준다.
  없는 줄은 셋 다 `null`.
- 새 마이그레이션이라 `//tools/aws/integration:database_test` 도 돌린다.
- 적재: 로컬 `just seed` 뒤 출처 있는 사진 수 확인, `GET /v1/places/{id}` 의 `photos[].credit` 확인(SMOKE).

## 6. 순서

1. 이 계획 확인
2. 시험 먼저(새 서브에이전트) → V42·`ReviewStore`·적재 스크립트
3. 사진 준비(사람 확인·지우기·올리기) → `candidates.csv` 주소, `place_image_credits.tsv`
4. `just check`·로컬 적재·SMOKE → PR. DEV 적재는 main 에 합친 뒤, PRD 는 다음 PRD 배포 묶음

## 7. 결과 (2026-11-03)

| 단계 | 결과 |
| --- | --- |
| 사람 확인 | 151 장 — 사람을 지움 68, 사람 없음 82, 3 유형인데 사람이 있어 뺌 1(연세대) → **150 장** |
| 사진 | 관광공사 주소 그대로 55 장, 우리 저장소에 올림 95 장(JPEG 1280px, 최대 614KB). 올린 것은 공개 주소로 받아 해시가 같음을 확인 |
| 데이터 | `candidates.csv` 의 빈 `place_image_url` 64 행(60 곳), `place_image_credits.tsv` 150 줄 — `license` 86(관광공사), 작은 사진 22 |
| 로컬 | `just seed` 뒤 출처 있는 사진 150 장. 사진 있는 촬영지 353 → **413 곳(476 곳 중 87%)** |
| SMOKE | `GET /v1/places/{id}` 의 `photos[]` 에 관광공사(3 유형·1 유형+「사람을 지움」)·위키미디어·서울역사박물관 출처가 나온다. 출처 없는 사진은 세 칸 null |

## 8. PRD 에 먼저 넣은 것 (2026-11-03, 사용자 요청)

앱 심사 중이라 서버 배포 없이 **데이터만** PRD DB 에 넣었다. 임시 파드(`app: data-load` 이름표)에서 적재 계정으로 psql, 한 트랜잭션.

| 무엇 | 결과 |
| --- | --- |
| 촬영지 사진 — 출처 표시가 필요 없는 것만 | 사람 지운 편집본(10-22) + 네이버 업체 사진(#401) + 이 PR 의 CC0·퍼블릭 도메인 15 장. 촬영지 열쇠 479 개가 로컬과 같음을 확인하고 그 사진을 지운 뒤 다시 넣었다 — 724 → 770 장, 사진 있는 촬영지 336 → **362 곳**(공개 API 로 확인) |
| 편의시설 관광공사 상세 10-29 판 | `poi_detail_load.sql`(10-17 PRD 와 같은 SQL) 국문·영문 — 사진 124,137 → 241,587 장(26,171 → 42,392 곳) |
| 넣지 않은 것 | 출처 표시가 꼭 필요한 촬영지 사진 135 장(51 곳) — PRD 서버가 V42 전이라 출처를 못 보인다. 다음 PRD 배포 묶음에서 |
| 백업 | 바꾸기 전 PRD 의 `place_image`·`poi_image` 를 저장소 밖 `.cache/prd-photo-2026-11-03/` 에 받아 두었다 |
