# POI(편의시설) 도입 계획

- **에픽**: [MZ2AZ-106](https://mz2az.atlassian.net/browse/MZ2AZ-106) "검색·지도" · [MZ2AZ-107](https://mz2az.atlassian.net/browse/MZ2AZ-107) "코스"
- **작성일**: 2026-08-20
- **상태**: 스키마·적재 완료(§9). 계약·검색 API 는 아직
- **자료**: `poi_food` · `poi_sight` · `poi_stay` · `poi_transit` (JSONL 4편). 8/13 판 160MB → **8/26 판 190MB** (§5-3)

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

### 5-2. 중복 처리 — 합친다

| 무엇 | 몇 | 처리 |
| --- | --- | --- |
| `source_id` 가 같은 중복 | 12쌍 | `ON CONFLICT (source_id) DO NOTHING`. 같은 장소가 두 파일에 든 것이다 |
| `source_id` 가 다른 중복 | 110쌍 | **합친다.** 아래 규칙 |

`(name, lat, lng)` 가 같으면 한 줄만 남긴다. 좌표는 소수 5자리(≈1m)까지 비교한다.

**이름이 열쇠에 들어 있는 것이 중요하다.** 한 건물에 든 다른 가게는 이름이 다르므로
애초에 묶이지 않는다. 묶이는 것은 *같은 이름이 같은 자리에 두 번 등록된* 경우뿐이다.

### 합치지 않는 예외

그래도 「같은 건물의 다른 가게가 우연히 같은 이름」인 경우가 있을 수 있다. 그럴 때는
**남기지 말고 둘 다 넣는다.**

> **전화번호가 둘 다 있으면서 서로 다르고, 카테고리도 둘 다 구체적이면서 서로 다르면**
> 다른 가게로 본다.

**오늘 자료에서는 0건이다**(§10-1). 그래도 규칙에 두는 이유는 자료를 다시 받았을 때
걸릴 수 있어서다 — 없는 것을 확인한 것과 검사하지 않는 것은 다르다.

### 남길 줄을 고르는 순서

위에서 걸리면 거기서 멈춘다.

1. **자루 카테고리가 아닌 쪽.** `~기타`(`음식점기타`·`카페기타`) 와 `전문음식점` 이 자루다
2. **전화번호가 있는 쪽**
3. **`source_id` 가 작은 쪽**

3번이 있어야 규칙이 **완전해진다.** 어느 자료로 몇 번을 다시 적재해도 같은 줄이 남아야
하고, 그러려면 마지막에 반드시 결정적인 기준이 와야 한다.

### 5-3. 실제 적재 (2026-09-03)

§5 는 8/13 판을 보고 썼다. 실제로 넣은 것은 **8/26 재수집판**이고, 넣으면서 달라진 것을
여기 적는다. 위 절은 고치지 않는다 — 무엇이 왜 뒤집혔는지가 보여야 한다.

**자료가 바뀌었다.** 승길의 인계 문서(볼트 `07_POI 재수집분 인계 (백엔드).md`) — 8/13 판은
`lat` 에 도로 진입점이 들어 있어 섬 숙소가 육지 선착장에 찍혔다(홍도모텔 109 km). 건물
좌표로 다시 수집해 500,807 행(+27,381). 칸이 19개다 — `front_lat`·`front_lng`·`verified`
가 늘었고 **셋 다 읽지 않는다.** 진입점은 경로 엔진이 알아서 도로에 붙이고, `verified`
(공공데이터 대조)는 `0` 이 폐업을 뜻하지 않는 데다 분기마다 낡는 파생값이라 저장하지
않는다. 팀에 물어 확정했다.

**인계 문서가 놓친 것 하나.** 새 판은 `biz_lower` 가 **전 행 빈 값**이고 세부 종류(한식·
중식…)가 `kind` 로 갔다. §2-1 은 「`kind` 는 `biz_lower` 와 전부 일치하므로 버린다」고
했는데 그 전제가 뒤집혔다. `poi.sql` 은 `COALESCE(kind, biz_lower)` 로 두 판을 다 받는다.
`kind` 는 93 종이다.

**원본은 손대지 않고 걸러 낸 파일을 넣는다.** `just poi-filter` 가 원본 네 파일에서
허용목록 갈래(§3-4)만 남긴 파일을 만든다. 빠지는 건 `poi_food` 의 114 행뿐이다 — 수집기가
`keyword=음식점` 으로 긁다 담아 온 정육점·반찬가게(`음식료`, 쇼핑 갈래)·꽃집(`생활서비스`)·
가구점이다. 나머지 세 파일은 빠지는 행이 없다. `poi.sql` 도 같은 목록으로 거르므로
원본을 바로 넣어도 결과는 같다 — 파일을 미리 걸러 두는 건 「무엇을 넣었나」를 파일로
남기기 위해서다. 네 파일을 `just seed-poi` 에 한 번에 준다.

**TRUNCATE 가 아니라 UPSERT.** §5 는 「`v6.sql` 과 같은 모양」이라 했지만 지우지
않는다. `source_id` 가 자연키라 `ON CONFLICT` 로 멱등이 되고, `course_item` 이 `poi` 를
참조하게 되면(§4-2) TRUNCATE 는 사용자 코스를 지우는 일이 된다. 바뀐 것이 없으면
건드리지 않아 「갱신」 건수가 실제로 값이 바뀐 행만 뜻한다. 표본을 두 번 돌려 21 행
전부 「변화 없음」인 것을 확인했다.

**중복을 다시 셌다** (§5-2 의 12/110 은 옛 자료). 8/26 판 넷 전체에서 `source_id` 중복
22(같은 가게가 두 파일에 — 강릉커피박물관이 음식·명소 양쪽), 이름·좌표 같고 id 다른 묶음
156 → 157 행을 접었다. 「합치지 않는 예외」에 걸린 묶음은 **0** — 옛 자료와 같다. 넷을
따로 넣으면 파일을 넘나드는 중복이 안 접히므로 **한 번에** 넣는다.

**뷰포트 인덱스가 하나 더 필요했다** — `V13__poi_geometry_index.sql`. §4-1 의 GiST 는
geography 위에 있어 `geom::geometry && ST_MakeEnvelope(...)` 를 받지 못한다. `place` 가
V6 에서 겪은 것과 같은 일이다.

```
강남역 2 km 뷰포트 · 중심 거리순 30개

V13 없이 (음식점 404,830 행)          Parallel Seq Scan     36 ms
V13 + geometry KNN 정렬 (500,514 행)  Index Scan           0.3 ms
```

표현식 인덱스가 있어야 플래너가 `geom::geometry` 의 선택도도 안다 — 없을 때는 4,027 행을
17 행으로 어림했다. 표 전체 226 MB.

```
services/scene-api/seed/
├── poi-sample.jsonl             표본 23 행 (갈래별 5 + 중복 한 쌍 + 버려질 행 하나)
├── poi.sql                      JSONL → poi. 표본·전량 공용
src/main/resources/db/migration/
├── V12__poi.sql                 표 + 인덱스 (MZ2AZ-276)
└── V13__poi_geometry_index.sql  뷰포트 인덱스
tools/scripts/seed-poi.sh        gz 를 파드로 옮겨 풀고 poi.sql 을 먹인다
tools/scripts/poi-filter.sh      원본 → 허용목록 갈래만
tools/just/k8s.just              seed-poi · poi-filter
```

적재 결과 — food 404,827 · stay 65,444 · sight 27,998 · transit 2,245 = **500,514 행**, 26 초
(kubectl cp 포함). 확인은 `just db-psql "SELECT category_group, count(*) FROM poi GROUP BY 1;"`.

**배포 후 스모크** (2026-09-04, `just poi-smoke`) — kind 의 scene-api 에 강남역 2 km 뷰포트로
`GET /pois` 를 흘렸다. 30 개 · 거리 오름차순 · `total` 4,103 · `Content-Language: ko`,
갈래 필터 · `limit` · 400 두 종류 · 상세 · 404 전부 기대대로. 왕복 **8~18 ms** (예열 뒤).
첫 요청은 256 ms — JIT·커넥션 풀이라 스모크가 한 번 버리고 잰다.

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
| 2 | 스키마 | `V12__poi.sql` — `poi` + 인덱스 ✅ MZ2AZ-276 · `V13__poi_geometry_index.sql` 뷰포트 인덱스 ✅ (§5-3) |
| 3 | 적재 | `seed-poi.sh` · `poi.sql` · `poi-sample.jsonl` · `just seed-poi` · `just poi-filter` ✅ (§5-3) |
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
| 카테고리 칩 묶음 | `category` 가 115종이라 화면에 그대로 못 쓴다. 묶는 규칙은 목업이 정해진 뒤에 |
| POI 를 장바구니에 담기 | **보류** — 전용 티켓(구 MZ2AZ-281)은 8/27 지라 재편 때 삭제됐고 후속 티켓은 없다. 8/11 회의가 「장소는 장바구니」로 정리했을 때 POI 는 존재하지 않았다. `saved_place` 의 기본키가 `(user_id, place_id)` 라 갈래를 나누면 **기본키 자체가 바뀐다** |
| 자료 갱신 주기 | 지금은 한 번 넣고 끝이다. `source_id` 로 다시 넣을 길은 열어 뒀다 |
| POI 상세에 무엇을 담나 | 지금 자료에는 전화번호가 전부다. 영업시간·사진은 없다 |

### 10-1. 중복 110쌍 — 실측 (합치기로 한 근거)

`source_id` 가 서로 다르면서 이름·좌표(소수 5자리 ≈ 1m)·주소가 같은 쌍이다.

| 유형 | 쌍 | 모습 |
| --- | --- | --- |
| 카테고리까지 같음 | 77 | `뚱땡이짬뽕` 중식 × 2 · `옛날그맛` 한식 × 2 |
| 한쪽이 자루 카테고리 | 31 | `커피숍` = 커피전문점 + **카페기타** |
| 둘 다 구체적인데 다름 | **2** | `충북통닭` 분식↔치킨 · `우리할매떡볶이 금호점` 한식↔분식 |

**110쌍은 전부 같은 파일 안이다.** 파일을 넘나드는 중복은 `source_id` 가 같은 12건이
전부이고, 그건 `ON CONFLICT` 가 잡는다.

세 가지를 재서 「다른 가게일 수도 있다」를 확인했다. **셋 다 0건이다.**

| 잰 것 | 건 |
| --- | --- |
| `category_group`(네 갈래)이 갈리는 쌍 | **0** |
| 주소나 도로명이 갈리는 쌍 | **0** |
| 전화번호와 구체 카테고리가 **둘 다** 갈리는 쌍 | **0** |

첫 줄이 합치기로 한 가장 큰 근거다 — 합쳐도 `food`/`sight`/`stay`/`transit` 구분을
잃지 않는다.

**전화번호가 갈리는 쌍은 3건뿐이고 둘은 오타다** — `02-6965-9285`/`02-696**6**-9285`,
`031-5**5**2-2623`/`031-5**2**2-2623`.

「같은 건물의 두 층이거나 본점/분점일 수 있다」는 처음의 우려는 자료가 부정한다. 좌표가
1m 단위까지 같고 주소·도로명도 같다. 출처가 같은 가게를 두 번 등록한 것이다.

위 2건도 같은 가게가 맞다 — `충북통닭` 은 **양쪽 전화번호가 같다.** 라벨만 다르게 붙었다.

## 11. 참고

- 볼트: [[MZ2AZ-275 POI 도메인 스키마 (DBML)]] — 스키마 정본
- [scene-api-search-map.md](./scene-api-search-map.md) — 검색·지도 명세 작업 방식
- [course-api.md](./course-api.md) — 코스 도메인. §4-3 의 두 갈래 제약이 여기서 셋이 된다
- [scene-api-database.md](./scene-api-database.md) — 스키마 v1

## 12. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-08-20 | 최초 작성. 자료 실측 후 결정 여섯을 확정 — 별도 엔드포인트 · 코스에 담기 가능 · 이름+카테고리 검색 · `biz_middle` 허용 목록 · 종교시설 포함 · 자동완성 제외 |
| 2026-09-03 | 적재 완료(§5-3). 8/26 재수집판 네 갈래 500,514 행. 숫자를 다시 쟀다. 적재는 TRUNCATE 가 아니라 UPSERT. `kind` 가 세부 종류다(`biz_lower` 는 빈 값). 뷰포트 인덱스 `V13` 추가 |
