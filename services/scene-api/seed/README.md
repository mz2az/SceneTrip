# scene-api 시드 데이터

`just seed` 가 읽는 것들. 스키마는 Flyway 가 만들고(`src/main/resources/db/migration/`),
데이터는 여기서 온다.

| 파일 | 내용 |
| --- | --- |
| `candidates.csv` | 성지후보 **87 행** — 10 작품 × 촬영지. 김태환 수집 v3 (2026-08-24) |
| `candidates.sql` | CSV 를 15 개 테이블로 옮기는 변환. 이 CSV 와 같은 컬럼의 다른 파일에도 쓴다 |
| `poi-sample.jsonl` | POI(편의시설) 표본 **27 행** — 갈래별 5 행 + 중복 한 쌍 + 버려질 행 하나(TMAP 판, 정승길 수집) + 공공데이터 규칙용 4 행(상가정보·겹치는 관광공사·안 겹치는 관광공사·좌표 뒤바뀜) |
| `poi.sql` | JSON Lines 를 `poi` 표로 옮기는 변환. `just seed-poi` 가 쓴다 |

```bash
just seed                          # 저장소의 성지후보 87 행
just seed <다른 CSV 경로>          # 같은 30 컬럼 형식이어야 한다
just seed-poi                      # 저장소의 POI 표본 27 행
just seed-poi <파일.jsonl(.gz) ...>  # 전량. 여러 파일은 이어 붙여 한 번에
```

## 전량을 저장소에 두는 이유

v6 시절에는 표본 12 행만 두고 전량은 볼트에 있었다 — 데이터가 정제 전이었다. 성지후보
v3 는 10 작품으로 골라졌고 이미지가 우리 S3 에 있고 `last_updated` 가 찍힌, 앱에 그대로
보여 줄 수 있는 데이터라 저장소에 전량을 둔다. 87 행·165 KB 다. 정본은 여전히
볼트(`~/mz2az/01_Raw/김태환/4주차_촬영지수집/`)이고, 다음 수집분이 나오면 파일을 갈아
끼운다.

## 컬럼 — 30 개

`\copy` 는 헤더를 건너뛸 뿐 이름으로 맞추지 않는다. **CSV 헤더 순서가 `candidates.sql`
의 `seed_staging` 컬럼 순서와 같아야 한다.** 다른 순서의 파일을 넣으면 오류 없이 엉뚱한
칸에 들어간다.

| 컬럼 | 가는 곳 |
| --- | --- |
| `title` `title_category` `famous_rank` `poster_url` | `content` · `content_i18n(ko)` |
| `title_en` `title_ja` `title_zh_hant` | `content_i18n` — 채워진 것만. 지금은 전부 비어 있다 |
| `title_aliases` | `content_alias`. `title_en` 이 비면 첫 라틴 항목을 `en` 제목으로 승격 |
| `title_cast` `director` | `person` · `person_i18n` · `content_cast` |
| `place_name` `place_type` `place_address` `place_latitude` `place_longitude` `place_naver_url` | `place` · `place_i18n(ko)` |
| `place_name_en` `place_name_ja` `place_name_zh_hant` | `place_i18n` — 채워진 것만. 지금은 전부 비어 있다 |
| `place_aliases` | `place_alias` — 30 행에 있다 |
| `place_image_url` | `place_image` |
| `scene_description` `last_updated` | `place_content` · `place_content_i18n(ko)` |
| `id` `title_tmdb_url` `source_url` `recent_rank` `audience_acc` `award` `notes` | 안 넣는다 — `candidates.sql` 머리에 이유 |

## 적재는 지우고 다시 넣는다

`candidates.sql` 의 첫 동작이 `TRUNCATE` 다. `content` 와 `person` 에는 자연키가 없어 —
같은 작품을 두 번 넣어도 DB 는 그것이 같은 작품인지 알 방법이 없다 — `ON CONFLICT` 로는
멱등을 만들 수 없다. 로컬 개발 DB 라서 성립하는 방식이고, `seed.sh` 가 kind 컨텍스트가
아니면 실행을 거부한다.

## 건너뛰는 행

**좌표가 없는 행은 넣지 않는다.** `place.geom` 이 `NOT NULL` 이라 그 한 행 때문에 적재
전체가 롤백되면 나머지가 볼모가 된다. 건너뛴 행의 `id` 와 이름을 적재 로그에 찍는다.
지금 파일에서는 둘이다 —

| `id` | 장소 |
| --- | --- |
| `kt_018` | 태안 안면도 북한마을 세트 |
| `kt_055` | 수원 행궁동 파란대문집 |

좌표가 채워진 파일로 다시 `just seed` 하면 들어온다.

## 장소 중복은 어떻게 접나

`place_naver_url` 이 1차 키다(MZ2AZ-111). 9 행이 비어 있어서 그대로 `DISTINCT ON` 을
걸면 그 9 곳이 한 곳으로 뭉개진다 — 비어 있으면 `이름|주소` 를 대신 쓴다. 청라호수공원과
중앙고가 두 작품에 나오고, 이 둘이 `place_content` 의 N:M 을 검증하는 실제 사례다.

## 이 파일이 v6 와 다른 것

| | v6 (승길, 25 컬럼) | 성지후보 v3 (태환, 30 컬럼) |
| --- | --- | --- |
| 작품 | 4 (drama 2 · movie 2) | 10 (전부 drama) |
| 행 | 표본 12 / 전량 164 | 87 |
| 방송사·연도·장르 | 있음 | 없음 → `broadcaster`·`release_year` NULL, `genres '{}'` |
| 장면 이미지 | `scene_image_url` | 없음 → `place_content.scene_image_url` NULL |
| 다국어 제목·장소명 | 없음 (별칭에서 승격) | 컬럼 있음 (아직 비어 있음) |
| 장소 별칭 | 없음 | `place_aliases` 30 행 |

**`movie` 카테고리가 이번 시드에 없다.** 카테고리 필터를 확인하려면 다른 파일이 필요하다.

## 정제 전이라 감수한 것

| 무엇 | 지금 |
| --- | --- |
| `place_type` 이 한국어 라벨 35 종 | 코드 매핑표가 아직 없다. 값 그대로 넣는다 |
| 장소 이름·주소가 한국어뿐 | 컬럼은 있지만 비어 있다. 채워지면 자동으로 들어간다 |
| `place_i18n.description` 이 비어 있음 | `scene_description` 은 "이 작품의 이 장면" 설명이라 장소 자체의 설명이 아니다 |
| `popularity_score` 가 임의값 | `famous_rank` 를 뒤집은 값. `user_event` 가 쌓이면 배치가 계산한다 |
| 좌표 없는 2 곳 | 위 「건너뛰는 행」 |

## POI(편의시설) — `poi-sample.jsonl` · `poi.sql`

성지와는 다른 자료, 다른 규칙이다. 계획은 [poi.md](../../../docs/project/plans/poi.md).

**전량은 저장소에 없다.** 250 MB 다. 공공데이터 판(`~/Downloads/SceneTrip_POI_20260907/out/`,
2026-09-07 판, 여섯 파일 973,000 행 — 상가정보 음식·숙박, 관광공사 관광·음식·숙박, 교통)을 경로로
넘긴다. 여섯을 `just seed-poi` 에 **한 번에** 준다 — 파일을 넘나드는 중복과 관광공사↔상가정보
겹침을 한 번의 적재 안에서 접기 위해서다. TMAP 은 쓰지 않는다(ADR 0014). `just poi-filter` 는
필요 없어졌다 — 전부 허용목록 안이다.

**지우지 않는다 — 기본은.** 성지 시드와 달리 `TRUNCATE` 가 없다. 출처가 준 `source_id` 가
자연키라 `ON CONFLICT` 로 UPSERT 한다 — 있는 행은 갱신, 없는 행은 추가, 바뀐 것이
없으면 건드리지 않는다. 몇 번을 돌려도 안전하다. **출처를 통째로 바꿀 때만 `--prune`** —
이번 입력에 없는 `source_id` 를 지운다(TMAP → 공공데이터 때 썼다). 표본과 함께는 막는다.

**읽는 칸.** `id` `name` `lat` `lng` `kind` `biz_middle` `addr` `road` `tel` `region`
`city`. 세부 종류는 `COALESCE(kind, biz_lower)`. `src`(원본 전 칸)는 읽지 않는다 — 관광공사
행의 영문·일문 이름도 거기 있는데 지금은 보류(2026-09-09).

**관광공사 음식·숙박은 상가정보에 없는 것만.** 같은 갈래의 상가정보 행이 100 m 안에 있고 이름이
포함 관계면 넣지 않는다 — 같은 가게가 두 출처에 있으면 좌표가 몇 m 어긋나 1 m 규칙에 안
걸린다. 좌표가 한국 밖이면 버린다. 이유와 숫자는 poi.md §5-4.

**버리는 행.** `biz_middle` 이 허용목록(음식점·카페·술집·숙박·관광명소·종교·문화생활
시설·레저/스포츠·교통시설) 밖이면 넣지 않고 건수를 찍는다. 수집기가 같이 담아 온
정육점·꽃집 같은 것들이다. 표본의 「내츄럴비프」(정육점)가 그 예다.

**중복.** 이름과 좌표(소수 5자리 ≈ 1 m)가 같으면 한 줄만 남긴다 — 자루 카테고리가
아닌 쪽 → 전화번호 있는 쪽 → `source_id` 작은 쪽. 표본의 「뚱땡이짬뽕」 두 줄이 그
예다. 전화번호와 구체 카테고리가 둘 다 갈리면 다른 가게로 보고 둘 다 넣는다.

적재 결과(2026-09-03): 500,693 행 → 같은 id 22 · 같은 이름·좌표 156 묶음 접어 **500,514 행**
(food 404,827 · stay 65,444 · sight 27,998 · transit 2,245), 26 초, 226 MB.

