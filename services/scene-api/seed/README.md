# scene-api 시드 데이터

`just seed` 가 읽는 것들. 스키마는 Flyway 가 만들고(`src/main/resources/db/migration/`),
데이터는 여기서 온다.

| 파일 | 내용 |
| --- | --- |
| `candidates.csv` | 성지후보 **544 행** — 128 작품 × 촬영지 487 곳, 53 컬럼(2026-10-10 통째로 교체). 키는 `title_key`(tv197067…)·`place_key`(nv… 네이버 장소 번호, px… 번호를 못 찾은 곳)·`sanctum_key`(작품-촬영지), 그리고 `place_popularity_score`. 원본의 「닭칼」(여신강림, id 379)은 뺐다 — 「율곡로3길」 과 네이버 URL·좌표가 같아 UNIQUE 에 걸린다(원본을 고치면 다시 넣는다). 전 판(98 행·51 컬럼)은 git 이력에 |
| `candidates.sql` | CSV 를 15 개 테이블로 옮기는 변환. 이 CSV 와 같은 컬럼의 다른 파일에도 쓴다 |
| `poi-sample.jsonl` | POI(편의시설) 표본 **31 행** — 갈래별 5 행 + 중복 한 쌍 + 버려질 행 하나(TMAP 판, 정승길 수집) + 공공데이터 규칙용 4 행(상가정보·겹치는 관광공사·안 겹치는 관광공사·좌표 뒤바뀜) + 제외 분류 4 행 |
| `poi.sql` | JSON Lines 를 `poi` 표로 옮기는 변환. `just seed-poi` 가 쓴다 |
| `place_poi_link.sql` | 촬영지와 같은 곳인 편의시설을 연결하고 편의시설 쪽 리뷰를 촬영지로 옮긴다(MZ2AZ-371). `just place-poi-link [--dry-run]` 이 쓴다 — `just seed`·`just seed-poi --update` 뒤에 |
| `course_pin_to_poi.sql` | 개인 핀으로 저장된 편의시설을 편의시설 항목으로 바꾼다(MZ2AZ-377). `just course-pin-to-poi [--dry-run]` — **앱(MZ2AZ-380) 배포 뒤에** |
| `place_poi_links.tsv` | 같은 곳 **사람 판정** — `place_key` · `poi_source_id` · `same`/`not` · 메모. 자동 판정(도로명 주소가 같음 · 5 m 안)보다 앞선다. 판정이 필요한 후보는 `just place-poi-link` 가 끝에 붙여 넣을 줄로 보여 준다 ([계획](../../../docs/project/plans/place-poi-link.md) §2·§3) |

```bash
just seed                          # 저장소의 성지후보 98 행
just seed <다른 CSV 경로>          # 같은 53 컬럼 형식이어야 한다(끝의 키 네 칸 포함)
just seed-poi                      # 저장소의 POI 표본 31 행
just seed-poi <파일.jsonl(.gz) ...>  # 전량. 여러 파일은 이어 붙여 한 번에
```

## 키 체계 바꾸기 (2026-10-10)

키를 `C0001`·`P0001` 에서 `tv…`·`nv…` 로 바꿨다. 적재가 **이 CSV 에 없는 옛 키를 가진 같은 작품(한국어 제목)·같은 촬영지(네이버 URL, 없으면 이름+주소)** 에 새 키를 붙인다 — id 가 그대로라 리뷰·코스·장바구니·같은 곳 연결이 이어진다. 옛 촬영지 93 곳 중 55 곳이 이어졌고 38 곳은 새 CSV 에 없어 숨겨졌다. 지난 판에서 옮겨 둘 수정(K팝 스퀘어 링크, 주소 셋)은 새 CSV 에 이미 들어 있다.

## 전량을 저장소에 두는 이유

v6 시절에는 표본 12 행만 두고 전량은 볼트에 있었다 — 데이터가 정제 전이었다. 성지후보
v3 는 10 작품으로 골라졌고 이미지가 우리 S3 에 있고 `last_updated` 가 찍힌, 앱에 그대로
보여 줄 수 있는 데이터라 저장소에 전량을 둔다. 98 행이다. 정본은 여전히
볼트(`~/mz2az/01_Raw/김태환/4주차_촬영지수집/`)이고, 다음 수집분이 나오면 파일을 갈아
끼운다.

## 키 — `content_key` · `place_key` (2026-10-07)

**`just seed` 는 지우지 않는다.** 작품·촬영지를 이 두 키로 알아보고 있으면 갱신, 없으면 추가, CSV 에서
빠지면 숨긴다(`hidden_at`). 그래서 촬영지·작품을 가리키는 장바구니·코스·찜·마켓이 적재 때마다 지워지지
않는다 — 예전에는 `TRUNCATE ... CASCADE` 로 통째로 갈아서 같이 지워졌다. 계획:
[place-key-seed.md](../../../docs/project/plans/place-key-seed.md).

수집 담당자가 지킬 규칙:

1. 같은 작품·같은 촬영지는 어느 줄에서나 같은 키를 쓴다(한 촬영지가 두 작품에 나오면 두 줄 모두 같은 `place_key`).
2. 한 번 정한 키는 바꾸지 않는다 — 이름·주소·좌표가 바뀌어도.
3. 지운 키는 다시 쓰지 않는다. 새 것은 마지막 번호 다음.
4. 모양은 자유다(`C0001` · `P0001`). 키가 빈 줄이 있거나, 한 `content_key` 에 제목이 둘이면 적재가 멈춘다.

숨긴 작품·촬영지는 목록·지도·검색 제안에서 빠지고, 상세와 사용자가 저장해 둔 것에는 남는다. 다시 CSV 에
나오면 숨김이 풀린다. 지금 98 행의 키는 옛 묶음 규칙(작품은 제목, 촬영지는 네이버 URL · 없으면 이름+주소)대로
붙였다 — 작품 11 개(`C0001`~`C0011`), 촬영지 95 곳(`P0001`~`P0095`).

## 컬럼 — 51 개

`\copy` 는 헤더를 건너뛸 뿐 이름으로 맞추지 않는다. **CSV 헤더 순서가 `candidates.sql`
의 `seed_staging` 컬럼 순서와 같아야 한다.** 다른 순서의 파일을 넣으면 오류 없이 엉뚱한
칸에 들어간다. 30 컬럼짜리 옛 파일은 컬럼 수가 안 맞아 `\copy` 가 거부한다 — 뒤에 빈
컬럼을 붙이는 것이 아니라 아래 순서대로 끼워 넣어야 한다.

30 컬럼(v3)에 **다국어 21 개**와 **`scene_image_url`** 을 더한 것이 52 다(2026-09-12).
더한 컬럼은 전부 형제 옆에 있다. 다국어는 지금 비어 있고, `scene_image_url` 은 v3 의
`place_image_url` 값을 그대로 옮긴 것이다 — 그 사진들이 장소 사진이 아니라 장면 스틸이라서다.
옮긴 뒤 비어 있던 `place_image_url` 에는 네이버 플레이스(`place_naver_url`)의 상위 사진
URL 을 `;` 로 이어 넣었다(2026-09-12). 업체가 등록한 사진을 앞에 두고, 나머지는 네이버 표시
순서를 따른다. 네이버 URL 이 없거나 네이버에 사진이 없는 22 행은 여전히 비어 있다.

**`title_producer_url`** 을 더했다(2026-09-23). `title_tmdb_url` 옆에 있다. 포스터를 누르면
이동할 **작품 제작사**의 페이지다. 법률 멘토와 상의해 정한 규칙이 두 가지다. 포스터를 누르면
원저작권자인 제작사로 가야 한다. 포스터 이미지는 그 작품을 제공하는 플랫폼(tvN·넷플릭스)이
올린 사진을 우리가 저장하지 않고 그 주소 그대로 쓴다. 그래서 `poster_url` 도 TMDB 이미지에서
플랫폼 이미지 주소로 바꿨다. 제작사에 사이트가 없는 작품(화앤담픽쳐스·본팩토리·문화창고)은
함께 크레디트된 모회사·공동제작사(스튜디오드래곤·CJ ENM)의 작품 페이지를 넣었다.

같은 날 `recent_rank` · `audience_acc` · `award` · `famous_rank` 네 컬럼을 뺐다. 채울 규칙을 두지 않기로
했기 때문이다. 그래서 53 이 아니라 49 다. 거기에 키 두 칸(`content_key` 는 `title` 앞, `place_key` 는
`place_name` 앞)을 더해 51 이다(2026-10-07). `famous_rank` 는 인기 점수(`popularity_score`)의 재료였으므로,
뺀 뒤로는 모든 작품이 중간값 50 에서 시작한다.

| 컬럼 | 가는 곳 |
| --- | --- |
| `content_key` | `content.content_key` — 같은 작품을 알아보는 키(위 「키」) |
| `place_key` | `place.place_key` — 같은 촬영지를 알아보는 키 |
| `title` `title_category` `poster_url` | `content` · `content_i18n(ko)` |
| `title_en` `title_ja` `title_zh_hant` | `content_i18n` — 채워진 것만. 지금은 전부 비어 있다 |
| `title_description` · `_en` `_ja` `_zh_hant` | `content_i18n.description` — 작품 소개. 제목 행에 얹혀 가므로 그 언어 제목이 없으면 안 들어간다 |
| `title_aliases` | `content_alias`. `title_en` 이 비면 첫 라틴 항목을 `en` 제목으로 승격 |
| `title_cast` `director` | `person` · `person_i18n` · `content_cast` |
| `title_cast_en` `_ja` `_zh_hant` · `director_en` `_ja` `_zh_hant` | `person_i18n` — `;` 로 나눈 **같은 자리끼리** 짝. 한국어 목록보다 짧으면 그 자리는 비고, 길면 남는 것은 버려진다 |
| `place_name` `place_type` `place_address` `place_latitude` `place_longitude` `place_naver_url` | `place` · `place_i18n(ko)` |
| `place_type_code` | `place.type` — 있으면 이것이, 없으면 `place_type` 한국어 라벨이 들어간다. 코드 매핑표가 생기기 전 과도기 |
| `place_name_en` `place_name_ja` `place_name_zh_hant` | `place_i18n` — 채워진 것만. 지금은 전부 비어 있다 |
| `place_address_en` `_ja` `_zh_hant` | `place_i18n.address` — 없으면 그 언어 행에도 한국어 주소가 들어간다 |
| `place_description` · `_en` `_ja` `_zh_hant` | `place_i18n.description` — 장소 자체의 소개(장면 설명이 아니다) |
| `place_aliases` | `place_alias` — 30 행에 있다 |
| `place_image_url` | `place_image` — 장소 사진. `;` 로 나눠 순서대로 `sort_order` 10, 20, 30… 을 매긴다. 65 행이 차 있고 비어 있는 22 행은 장소 썸네일이 NULL 이다 |
| `scene_description` `last_updated` | `place_content` · `place_content_i18n(ko)` |
| `scene_description_en` `_ja` `_zh_hant` | `place_content_i18n` — 채워진 언어만. 없으면 API 가 `ko` 로 폴백 |
| `scene_image_url` | `place_content.scene_image_url` — 장면 스틸. (장소, 작품) 한 쌍에 한 장 |
| `id` `title_tmdb_url` `title_producer_url` `source_url` `notes` | 안 넣는다 — `candidates.sql` 머리에 이유 |

## 무엇을 갱신하고 무엇을 다시 넣나

사용자 데이터가 가리키는 `content` · `place` 만 키로 UPSERT 해 id 를 지킨다. 나머지는 적재가
소유하므로 이 CSV 에 있는 작품·촬영지의 것만 지우고 다시 넣는다 — `content_i18n` · `content_alias` ·
`place_i18n` · `place_alias` · `place_image` · `place_content`(+ `_i18n`). 인물(`person` · `person_i18n` ·
`content_cast`)은 키가 없고 사용자 데이터가 가리키지 않아 통째로 다시 넣는다.

키가 생기기 전(V20 이전)의 DB 에 처음 돌리면, 키가 빈 기존 작품·촬영지를 옛 묶음 규칙으로 찾아 키를
붙인 뒤 갱신한다 — 장바구니·코스가 그대로 이어진다. 한 번 붙으면 다시 하지 않는다.

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

| | v6 (승길, 25 컬럼) | 성지후보 v3 (태환, 30 컬럼 → 다국어·장면 스틸 더해 52) |
| --- | --- | --- |
| 작품 | 4 (drama 2 · movie 2) | 10 (전부 drama) |
| 행 | 표본 12 / 전량 164 | 87 |
| 방송사·연도·장르 | 있음 | 없음 → `broadcaster`·`release_year` NULL, `genres '{}'` |
| 장면 이미지 | `scene_image_url` | `scene_image_url` — v3 의 `place_image_url` 을 옮긴 것 |
| 다국어 제목·장소명·주소·소개·인물·장면 설명 | 없음 (별칭에서 승격) | 컬럼 있음 (아직 비어 있음) |
| 장소 별칭 | 없음 | `place_aliases` 30 행 |

**`movie` 카테고리가 이번 시드에 없다.** 카테고리 필터를 확인하려면 다른 파일이 필요하다.

## 정제 전이라 감수한 것

| 무엇 | 지금 |
| --- | --- |
| `place_type` 이 한국어 라벨 35 종 | 코드 매핑표가 아직 없다. 값 그대로 넣는다 |
| 장소 이름·주소·소개, 인물 이름, 장면 설명이 한국어뿐 | 컬럼은 있지만 비어 있다. 채워지면 자동으로 들어간다 |
| `place_i18n.description` 이 비어 있음 | `scene_description` 은 "이 작품의 이 장면" 설명이라 장소 자체의 설명이 아니다 |
| `popularity_score` 가 임의값 | 모든 작품이 50. `user_event` 가 쌓이면 배치가 계산한다 |
| 좌표 없는 2 곳 | 위 「건너뛰는 행」 |

## POI(편의시설) — `poi-sample.jsonl` · `poi.sql`

성지와는 다른 자료, 다른 규칙이다. 계획은 [poi.md](../../../docs/project/plans/poi.md).

**전량은 저장소에 없다.** 250 MB 다. 공공데이터 판(`~/Downloads/SceneTrip_POI_20260907/out/`,
2026-09-07 판, 여섯 파일 973,000 행 — 상가정보 음식·숙박, 관광공사 관광·음식·숙박, 교통)을 경로로
넘긴다. 여섯을 `just seed-poi` 에 **한 번에** 준다 — 파일을 넘나드는 중복과 관광공사↔상가정보
겹침을 한 번의 적재 안에서 접기 위해서다. TMAP 은 쓰지 않는다(ADR 0014). `just poi-filter` 는
필요 없어졌다 — 전부 허용목록 안이다.

**분기 갱신은 `--update`**(2026-10-05). 상가정보는 분기마다 새 판이 나오고, 가게 번호는 이름 정정만으로도
바뀐다. `--update` 는 입력에 없는 가게를 지우지 않는다 — 좌표 30 m 안에서 같은 가게로 보이는 새 번호가
있으면 번호만 갈아 잇고(`poi.id` 와 붙은 번역·사진이 남는다), 없으면 `closed_at` 에 폐업 표시만 한다.
서버는 폐업 표시된 POI 를 내보내지 않는다. 같은 출처(`source_id` 앞 영문)끼리만 보므로 상가정보 파일만
넣어도 관광지는 그대로다. 규칙은 `poi_update.sql`, 근거는
[poi-i18n-image.md](../../../docs/project/plans/poi-i18n-image.md) §8.

```bash
just seed-poi --update ~/Downloads/SceneTrip_POI_<새 판>/out/poi_*.jsonl
```

| 방식 | 입력에 없는 가게 | 쓰는 때 |
| --- | --- | --- |
| 기본 | 그대로 둔다 | 같은 판을 다시 넣을 때, 일부 파일만 고쳐 넣을 때 |
| `--update` | 같은 가게면 잇고, 아니면 폐업 표시 | **분기 갱신** |

**제외 분류 넷은 넣지 않는다**(2026-10-05) — `일반 유흥 주점` · `무도 유흥 주점` · `구내식당` ·
`기숙사/고시원`. 큰 갈래는 허용목록 안이지만 여행 앱에 보일 일이 없다. 상권 소분류(`category`)로
거르고, 이미 들어 있는 행은 적재 때 숨긴다(`closed_at` — 지우지 않는다). 근거는
[poi-i18n-image.md](../../../docs/project/plans/poi-i18n-image.md) §7.

**POI 는 지우지 않는다.** 성지 시드처럼 `TRUNCATE` 가 없다. 출처가 준 `source_id` 가
자연키라 `ON CONFLICT` 로 UPSERT 한다 — 있는 행은 갱신, 없는 행은 추가, 바뀐 것이
없으면 건드리지 않는다. 몇 번을 돌려도 안전하다. 리뷰·코스 항목·번역·사진이 POI 를
`ON DELETE CASCADE` 로 참조해, 지우면 함께 사라진다 — 보이지 않아야 할 POI 는 `closed_at` 으로 숨긴다
([ADR 0022](../../../docs/architecture/adr/0022-poi-multiple-source-ids-and-never-delete.md)).
TMAP → 공공데이터 출처 교체(2026-09-09)에 쓰던 `--prune`(입력에 없는 행 지우기)은 없앴다 — 주면 멈춘다.

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

