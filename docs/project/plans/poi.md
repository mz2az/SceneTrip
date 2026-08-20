# POI(편의시설) 도입 계획

- **에픽**: [MZ2AZ-106](https://mz2az.atlassian.net/browse/MZ2AZ-106) "검색·지도" · [MZ2AZ-107](https://mz2az.atlassian.net/browse/MZ2AZ-107) "코스"
- **작성일**: 2026-08-20
- **상태**: 계획 — 티켓 분해 전
- **자료**: `poi_food` · `poi_sight` · `poi_stay` · `poi_transit` (JSONL 4편, 160MB)

---

## 1. 무엇을 만드는가

성지(촬영지) 곁에 **음식점·카페·숙박·관광명소·교통시설**을 붙인다. 47만 건이다.

쓰이는 곳은 둘이다.

| 화면 | 쓰임 |
| --- | --- |
| 지도 탭 검색 | 검색하면 POI 도 함께 뜬다 |
| 코스 탭 장소 검색 | 코스에 담을 수 있다 |

**성지와 같은 표에 넣지 않는다.** 성지는 155개이고 POI 는 47만 개다. 성격도 다르다 —
성지는 작품과 이어져 있고(`place_content`) 장면 설명을 가지지만, POI 는 그냥 거기 있는
가게다. 한 표에 담으면 `place` 를 읽는 모든 질의가 3,000배 커진 표를 훑게 된다.

## 2. 자료 실측 (2026-08-20)

네 파일이 **완전히 같은 스키마**다. 필드 16개, 키 조합 1종, 빠진 키 없음.

```
id · name · lat · lng · addr · road · tel
kind · biz_upper · biz_middle · biz_lower · keyword
region · city · area · near_spot
```

| 파일 | 행 | 고유 id |
| --- | --- | --- |
| `poi_food` | 377,835 | 377,835 |
| `poi_stay` | 65,394 | 65,394 |
| `poi_sight` | 27,952 | 27,952 |
| `poi_transit` | 2,245 | 2,245 |
| **합** | **473,426** | 473,414 |

### 2-1. 쓸 수 있는 것과 버릴 것

| 필드 | 판정 | 근거 |
| --- | --- | --- |
| `id` | **출처 키로 쓴다** | 전역 고유. 파일 간 중복 12건뿐이고 그 12건은 *같은 장소가 두 파일에 들어간 것* (`강릉커피박물관` 이 food·sight 양쪽) |
| `name` `lat` `lng` `addr` `road` `tel` | 쓴다 | |
| `biz_middle` `biz_lower` | **분류 두 단계로 쓴다** | |
| `kind` | **버린다** | `biz_lower` 와 **473,426행 전부 일치**한다. 완전 중복 |
| `keyword` | 버린다 | 80% 는 `biz_middle`, 13% 는 `biz_lower`, 나머지는 제3의 값(`모텔`). 분류 체계가 아니라 표시용 라벨이라 믿고 쓸 수 없다 |
| `biz_upper` | 버린다 | 네 값뿐이고 `biz_middle` 에서 유도된다 |
| `near_spot` | **버린다** | 96~100% 가 빈 값 |
| `area` | 버린다 | 장소의 속성이 아니라 **수집 범위 표시**다 — 서울 255k · 전국 93k · 부산 91k · 경주 23k · 강릉 7.9k. 나중에 "어디까지 수집했나" 를 물으려면 적재 로그로 남긴다 |
| `region` `city` | 쓴다 | 주소에서 유도할 수도 있으나 이미 정제돼 있다 |

### 2-2. 적재 전에 반드시 고칠 것

**역슬래시가 섞여 있다.** `/` 앞에 `\` 가 붙은 값이 있다.

```
poi_stay  biz_upper:   여행\/레저 37,744   vs   여행/레저 27,650
poi_food  biz_lower:   박물관\/기념관       vs   박물관/기념관
                       공방\/공예           vs   공방/공예
```

**같은 값이 두 표기로 갈려 있다.** 정규화하지 않으면 카테고리 필터가 반쪽만 걸리고,
그 실패가 조용하다 — 오류 없이 결과가 절반이 된다. 적재 시점에 `\/` → `/` 로 바꾼다.

## 3. 결정된 것

2026-08-20 에 정했다. 근거를 함께 남긴다 — 뒤집을 때 무엇을 다시 저울질해야 하는지
알아야 하기 때문이다.

### 3-1. 검색은 별도 엔드포인트다

```
GET /places?q=강릉   →  total 3        성지
GET /pois?q=강릉     →  total 8,412    POI
```

한 목록에 섞지 않는다. **섞으면 `limit`·`offset`·`total` 이 하나뿐이라 「성지만 더
보기」를 표현할 수 없다.** 이것은 `/contents` 와 `/places` 를 나눈 것과 같은 이유다
(계약 §검색 범위). 프론트가 둘을 호출해 각 자리에 꽂는다.

### 3-2. 코스에 담을 수 있다 — `course_item` 이 세 갈래가 된다

지금은 `place_id` **XOR** `custom_pin_id` 다. 여기에 `poi_id` 를 더해 **셋 중 정확히
하나**가 된다. 마켓 사본(`market_course_item`)도 같이 갈린다.

복사가 아니라 참조로 두는 이유: POI 정보가 갱신되면(전화번호가 바뀌면) 코스에도
반영돼야 한다. 직접 찍은 핀은 사용자가 만든 것이라 복사가 맞지만, POI 는 우리가
관리하는 자료다.

### 3-3. 검색은 이름 + 카테고리를 훑는다

`name` 과 `biz_lower` 둘이다. 그래서 `스타벅스` 로도 `카페` 로도 걸린다.

**주소는 훑지 않는다.** `강남구` 로 그 동네 가게가 전부 나오면 검색이 아니라 목록이
된다. 위치로 좁히는 것은 `bbox`·`radiusMeters` 의 몫이다.

### 3-4. `biz_middle` 허용 목록으로 거른다

| 남긴다 | 행 |
| --- | --- |
| 음식점 | 306,388 |
| 카페 | 70,918 |
| 숙박 | 65,403 |
| 관광명소 | 13,101 |
| 종교 | 12,023 |
| 문화생활시설 | 2,560 |
| 교통시설 | 2,245 |
| 술집 | 388 |
| 레저/스포츠 | 299 |
| **합** | **473,325** |

버려지는 것은 **101행**이다 — `생활서비스`(31) 아래로 가구·가방·꽃집·카메라·내의판매점
등이 한두 건씩 섞여 있다.

> **정정** — 처음에 「음식 파일이 오염됐다」고 보고했는데 실제 오염은 **0.02%** 다.
> `biz_lower` 가 95종이라 넓어 보였을 뿐, 행 수로는 음식점·카페가 99.9% 다.

허용 목록을 코드에 두는 이유는 **새 값이 조용히 들어오지 않게** 하기 위해서다. 자료를
다시 받았을 때 모르는 `biz_middle` 이 있으면 적재가 실패해야 한다.

### 3-5. 종교시설을 넣는다

12,023건(절·탑)이 `poi_sight` 의 절반이다. 불국사·해인사 같은 주요 관광지가 여기
들어 있어 빼면 「관광」 목록이 반쪽이 된다. 동네 작은 교회도 함께 들어오는 것이 대가다.

### 3-6. 자동완성에는 넣지 않는다

`search_term` 은 지금 **186행**이다. 47만 개를 넣으면 두 가지가 깨진다.

- `REFRESH MATERIALIZED VIEW CONCURRENTLY` 가 감당할 크기를 넘는다
- 더 큰 문제는 **제안 목록이 POI 로 덮인다.** `강릉` 을 치면 작품·성지가 8,412개
  가게 밑으로 밀린다

POI 는 자기 색인을 따로 가진다(§6). 자동완성은 지금 그대로 이름류만 본다.

## 4. 스키마

**정본은 볼트의 [[MZ2AZ-275 POI 도메인 스키마 (DBML)]] 다.** 이 절은 그것을 SQL 로 옮기면서
갈린 곳과, DBML 이 표현하지 못해 손으로 붙인 것만 적는다 — `course-api.md` 와 같은 방식이다.

```sql
CREATE TABLE poi (
    id         BIGSERIAL PRIMARY KEY,
    source_id  TEXT NOT NULL UNIQUE,
    name       TEXT NOT NULL,
    geom       GEOGRAPHY(Point, 4326) NOT NULL,
    category       TEXT NOT NULL,   -- biz_lower, \/ 정규화 후
    category_group TEXT NOT NULL,   -- food · sight · stay · transit
    address    TEXT,
    road       TEXT,
    tel        TEXT,
    region     TEXT,
    city       TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 4-0. 네 갈래는 원본 파일이 아니라 `biz_middle` 로 정한다

갈래 이름은 출처 파일과 같지만 **파일을 그대로 믿지 않는다.** `poi_food` 안에 다른 종류가
40건쯤 섞여 있다 — `그린랜드(펜션)` · `강릉커피박물관` · `가나아트파크(미술관)` 가 음식
파일에 들어 있다. 파일로 정하면 그것들이 `food` 가 된다.

| `biz_middle` | 행 | → `category_group` |
| --- | --- | --- |
| 음식점 · 카페 · 술집 | 377,694 | `food` |
| 숙박 | 65,403 | `stay` |
| 관광명소 · 종교 · 문화생활시설 · 레저/스포츠 | 27,983 | `sight` |
| 교통시설 | 2,245 | `transit` |
| **합** | **473,325** | |

이 표가 곧 §3-4 의 허용 목록이다. **모르는 `biz_middle` 이 나오면 적재를 실패시킨다.**

`biz_middle` 자체는 저장하지 않는다 — 네 갈래로 접고 나면 남는 정보가 `category`(=`biz_lower`)
와 겹친다.

**`place` 와 달리 `poi_i18n` 을 만들지 않는다.** 자료가 한국어뿐이라 표를 나눌 근거가
없다. 다국어 표기가 생기면 그때 `place` 와 같은 모양으로 뗀다 — 지금 미리 만들면
언제나 `ko` 한 줄뿐인 표가 된다.

**`source_id` 가 TEXT 인 이유**: 지금 값은 전부 숫자지만 출처가 부여한 식별자이지 우리
숫자가 아니다. 다음 자료가 영숫자를 섞어 오면 타입을 바꿔야 하는데, 그때는 이미 47만
행이 들어 있다.

**`popularity_score` 를 두지 않는다.** `place` 에는 있지만 그 값은 지금 임시 고정값이고,
POI 에는 순위를 매길 근거가 아무것도 없다. 정렬은 거리순과 이름순만 낸다.

### 4-1. 인덱스

```sql
CREATE INDEX poi_geom_idx ON poi USING gist (geom);
CREATE INDEX poi_name_trgm_idx     ON poi USING gin (search_normalize(name) gin_trgm_ops);
CREATE INDEX poi_category_trgm_idx ON poi USING gin (search_normalize(category) gin_trgm_ops);
CREATE INDEX poi_category_group_idx ON poi (category_group);
```

`search_normalize()` 는 `V3` 가 만든 함수를 그대로 쓴다. **색인과 조회가 같은 정규화를
써야 한다** — 다르면 오류 없이 결과가 0건이 된다.

이름과 카테고리를 **한 인덱스로 합치지 않는다.** 합치면 `카페` 로 검색할 때 이름에
`카페` 가 든 가게와 종류가 카페인 가게를 구분할 수 없어, 나중에 「종류로만 거르기」를
붙일 수 없다. PostgreSQL 이 두 GIN 을 `BitmapOr` 로 묶어 준다.

### 4-2. `course_item` 이 세 갈래가 된다

```sql
ALTER TABLE course_item ADD COLUMN poi_id BIGINT REFERENCES poi (id);

ALTER TABLE course_item DROP CONSTRAINT course_item_target_check;
ALTER TABLE course_item ADD CONSTRAINT course_item_target_check
    CHECK (num_nonnulls(place_id, poi_id, custom_pin_id) = 1);
```

`num_nonnulls()` 를 쓰는 이유: `<>` 로 쓴 배타적 논리합은 둘일 때만 성립하고 셋에서는
읽을 수 없게 된다.

### 4-3. 마켓 사본도 갈린다

`market_course_item.place_id` 가 지금 `NOT NULL` 이다. POI 가 담긴 코스를 올리면 그
항목이 통째로 사라진다.

```sql
ALTER TABLE market_course_item ALTER COLUMN place_id DROP NOT NULL;
ALTER TABLE market_course_item ADD COLUMN poi_id BIGINT REFERENCES poi (id);
ALTER TABLE market_course_item ADD CONSTRAINT market_course_item_target_check
    CHECK (num_nonnulls(place_id, poi_id) = 1);
```

**직접 찍은 핀은 여전히 빠진다.** 개인 숙소 위치를 남에게 공개하지 않기 위한 기존
결정(course-api.md §6)이고 POI 도입과 무관하다.

## 5. 적재

`v6.sql` 과 같은 모양이다 — 스크립트는 파일을 옮기고 SQL 이 변환한다.

```
just seed-poi <디렉터리>
  → tools/scripts/seed-poi.sh    JSONL 넷을 psql 이 도는 기계로 옮긴다
  → services/scene-api/seed/poi.sql   \copy 로 읽어 파싱하고 흩뿌린다
```

**저장소에 전량을 두지 않는다.** 160MB 이고 정제 전이다. `v6` 과 같은 규칙으로 표본만
두고(`services/scene-api/seed/poi-sample.jsonl`) 전량은 경로를 넘긴다.

### 5-1. JSONL 을 읽는 방법

`\copy` 의 기본 TEXT 형식을 쓰면 안 된다. **역슬래시를 탈출 문자로 해석해서** `여행\/레저`
가 깨진다. 한 줄을 통째로 한 칸에 담는 형태로 읽는다.

```sql
CREATE TEMP TABLE t_raw (doc TEXT);
\copy t_raw FROM '/tmp/poi-food.jsonl' WITH (FORMAT csv, QUOTE E'\x01', DELIMITER E'\x02')
```

쓰이지 않는 제어문자를 인용·구분자로 지정해 **CSV 파서가 아무것도 쪼개지 않게** 만드는
방법이다. 그 뒤 `doc::jsonb ->> 'name'` 으로 꺼낸다.

### 5-2. 중복 처리

| 무엇 | 몇 | 처리 |
| --- | --- | --- |
| 파일 간 `id` 중복 | 12 | `ON CONFLICT (source_id) DO NOTHING`. 같은 장소가 두 파일에 든 것이라 하나만 남기면 된다 |
| 이름+좌표 중복 | 122쌍 | **그대로 둔다** (§10) |

## 6. 검색

`/pois?q=` 는 `/places?q=` 와 같은 모양이되 훨씬 단순하다. 다리를 건너지 않는다 —
POI 는 작품과 이어져 있지 않다.

```sql
WITH params AS (SELECT search_normalize(:q) AS norm)
SELECT ... FROM poi p CROSS JOIN params pa
WHERE (:q IS NULL OR
       search_normalize(p.name)     LIKE '%' || pa.norm || '%' OR
       search_normalize(p.category) LIKE '%' || pa.norm || '%')
  AND (:categoryGroup IS NULL OR p.category_group = :categoryGroup)
  AND (:minLng IS NULL OR p.geom::geometry && ST_MakeEnvelope(...))
  AND (:radiusMeters IS NULL OR ST_DWithin(p.geom, :origin, :radiusMeters))
```

`bbox` 를 `geometry` 로 보는 것, 반경에 `ST_DWithin` 을 쓰는 것 모두 `PlaceStore` 와
같은 이유다(180도 간선 문제 / GiST 인덱스).

**정렬에 인기도가 없다.** 기준점이 있으면 거리순, 없으면 이름순이다.

## 7. 계약

```
GET /pois            목록·검색·지도 조회
GET /pois/{poiId}    상세
```

`/places` 의 파라미터 모양을 그대로 따른다 — `q` · `bbox` · `lat`+`lng`+`radiusMeters` ·
`sort` · `limit` · `offset`. 여기에 `categoryGroup` 이 붙는다.

`Accept-Language` 는 **받지 않는다.** 자료가 한국어뿐이라 받아도 할 일이 없고, 받아
두면 「번역이 있는 것처럼」 보인다.

`CourseItemInput` 에 `poiId` 가 붙는다. 지금 `placeId` 와 `customPin` 중 하나였던 것이
셋 중 하나가 된다.

## 8. 프론트에 생기는 일

**새로 만들어야 하는 것이 있다.** 지금까지의 작업과 달리 이번엔 프론트 작업이 따로
필요하다.

- 지도 탭 검색이 `/places` 와 `/pois` 를 **둘 다** 부르고 결과를 나눠 보여 준다
- 코스 편집의 장소 검색이 `/pois` 를 부르고, 담을 때 `poiId` 를 보낸다
- POI 핀과 성지 핀을 시각적으로 구분한다

이 계획은 **백엔드만** 다룬다. 프론트 작업은 별도 티켓으로 뗀다.

## 9. 구현 순서

| 순서 | 내용 | 산출물 |
| --- | --- | --- |
| 1 | 이 문서 | 계획 |
| 2 | 스키마 | `V12__poi.sql` — `poi` + 인덱스 |
| 3 | 적재 | `seed-poi.sh` · `poi.sql` · 표본 JSONL · `just seed-poi` |
| 4 | 계약 | `/pois` 두 경로 + `PoiSummary`·`PoiDetail` |
| 5 | 검색 API | `PoiStore` · `PoiController` |
| 6 | 코스에 담기 | `V13__course_item_poi.sql` + `CourseStore`·`MarketStore` 수정 |
| 7 | 프론트 | 별도 티켓 |

2·3 을 먼저 하는 이유는 **자료가 실제로 들어가 봐야 4·5 의 모양이 정해지기** 때문이다.
47만 행에서 `카페` 를 검색했을 때 얼마나 걸리는지 재 보고 계약의 `limit` 상한을 정한다.

6 은 앞의 것과 겹치는 파일이 없어 병행할 수 있다.

## 10. 열어 둔 것

| 항목 | 왜 지금 정하지 않는가 |
| --- | --- |
| 이름+좌표가 같은 110쌍 | **판정 대기.** 파일 간 12건은 `source_id` 가 같아 `ON CONFLICT DO NOTHING` 이 해결한다. 나머지 110쌍은 `source_id` 가 달라 그대로 두면 둘 다 들어간다 — §10-1 |
| 카테고리 칩 묶음 | `category` 가 115종이라 화면에 그대로 못 쓴다. 묶는 규칙은 목업이 정해진 뒤에 |
| POI 를 장바구니에 담기 | **보류** — [MZ2AZ-281](https://mz2az.atlassian.net/browse/MZ2AZ-281). 8/11 회의가 「장소는 장바구니」로 정리했을 때 POI 는 존재하지 않았다. `saved_place` 의 기본키가 `(user_id, place_id)` 라 갈래를 나누면 **기본키 자체가 바뀐다** |
| 자료 갱신 주기 | 지금은 한 번 넣고 끝이다. `source_id` 로 다시 넣을 길은 열어 뒀다 |
| POI 상세에 무엇을 담나 | 지금 자료에는 전화번호가 전부다. 영업시간·사진은 없다 |

### 10-1. 중복 110쌍 — 실측

`source_id` 가 서로 다르면서 이름·좌표(소수 5자리 ≈ 1m)·주소가 같은 쌍이다.

| 유형 | 쌍 | 모습 |
| --- | --- | --- |
| 종류까지 같음 | 77 | `뚱땡이짬뽕` 중식 × 2, `옛날그맛` 한식 × 2 |
| 한쪽이 뭉뚱그린 종류 | 33 | `커피숍` = 커피전문점 + **카페기타** · `충북통닭` = 분식 + 치킨 |

**전화번호가 갈리는 쌍은 3건뿐이고 둘은 오타로 보인다** — `02-6965-9285`/`02-6966-9285`,
`031-552-2623`/`031-522-2623`.

## 11. 참고

- 볼트: [[MZ2AZ-275 POI 도메인 스키마 (DBML)]] — 스키마 정본
- [scene-api-search-map.md](./scene-api-search-map.md) — 검색·지도 명세 작업 방식
- [course-api.md](./course-api.md) — 코스 도메인. §4-3 의 두 갈래 제약이 여기서 셋이 된다
- [scene-api-database.md](./scene-api-database.md) — 스키마 v1

## 12. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-08-20 | 최초 작성. 자료 실측 후 결정 여섯을 확정 — 별도 엔드포인트 · 코스에 담기 가능 · 이름+카테고리 검색 · `biz_middle` 허용 목록 · 종교시설 포함 · 자동완성 제외 |
