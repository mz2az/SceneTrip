# DB 구축과 V6 데이터 적재 계획 (MZ2AZ-152)

- **티켓**: [MZ2AZ-152](https://mz2az.atlassian.net/browse/MZ2AZ-152) — 에픽 MZ2AZ-109 "데이터·AI"
  - [MZ2AZ-176](https://mz2az.atlassian.net/browse/MZ2AZ-176) DBML 문서 기준 DB 스키마 구축
  - [MZ2AZ-177](https://mz2az.atlassian.net/browse/MZ2AZ-177) V6 데이터 적재
- **브랜치**: `MZ2AZ-152-db-구축-적재`
- **작성일**: 2026-08-06
- **상태**: 진행 중

---

## 1. 무엇을 만드는가

PostgreSQL 을 로컬 클러스터에 세우고, [MZ2AZ-111 DBML](https://mz2az.atlassian.net/browse/MZ2AZ-111)
기준으로 스키마를 만들고, V6 데이터 일부를 넣는다.

**이것이 [MZ2AZ-149](https://mz2az.atlassian.net/browse/MZ2AZ-149) 를 막고 있다.** DB 에
데이터가 없으면 검색·지도 엔드포인트(166~171)를 구현할 수 없다. 지라에 `blocks` 링크로
표시해 두었다.

## 2. 원본 설계가 이미 말해 둔 것

`docs/installs/k8s_install.md` §16 이 흐름을 이렇게 규정한다.

> PostgreSQL을 먼저 세우고 기동을 기다린 뒤 백엔드를 적용합니다.
> **DB가 먼저 떠야 합니다.** 백엔드는 기동 시 Flyway 마이그레이션을 돌리므로, DB 없이
> 뜨면 접속 실패로 죽습니다. `deploy.sh`가 순서를 지키고, Deployment의 initContainer가
> 한 번 더 기다립니다.

**그런데 넷 다 아직 없다.**

| 가이드가 말한 것 | 현재 |
| --- | --- |
| PostgreSQL 이 클러스터에 뜬다 | 매니페스트 없음 |
| 백엔드가 기동 시 Flyway 마이그레이션 | 의존성 없음 |
| `deploy.sh` 가 DB 를 먼저 세운다 | 순서 로직 0 건 |
| initContainer 가 한 번 더 기다린다 | 없음 |

이 티켓이 그 넷을 만든다. 문서가 앞서 있고 구현이 따라가는 구조다.

## 3. 확정된 결정

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 이미지 | **PostGIS** (`postgis/postgis`) | 일반 PostgreSQL 로는 스키마가 만들어지지 않는다 — DBML 이 `geography(Point,4326)` 와 GiST 인덱스를 쓴다 |
| 확장 | `postgis` · `pg_trgm` | `search_term` 의 `gin_trgm_ops` 인덱스에 필요 |
| 마이그레이션 | Flyway, 앱 기동 시 실행 | 설치 가이드 §16 이 규정 |
| 적재 | **별도 명령 `just seed`** | 스키마와 데이터를 분리한다. 정제된 V7·V8 이 나와도 명령 한 번으로 다시 넣는다. Flyway 마이그레이션에 INSERT 를 넣으면 데이터가 바뀔 때마다 새 마이그레이션을 쌓아야 한다 |
| 저장소의 CSV | **샘플 몇 행만** | 데이터가 아직 정제 전이다. 볼트 없이도 `just seed` 로 동작을 확인할 수 있게 하되, 수집 산출물의 정본은 볼트에 둔다. 전량은 `just seed <볼트 경로>` |
| 자격 증명 | 로컬은 ConfigMap 고정값, 원격은 Secret | 로컬 kind 는 외부에 나가지 않는다. 시크릿이 아닌 값을 시크릿처럼 다루면 진짜 시크릿 관리가 느슨해진다 (AGENTS.md §9) |

## 4. DBML 을 SQL 로 옮길 때 손봐야 할 것

DBML 문서가 스스로 셋을 지적해 뒀다 — *"`Export to PostgreSQL` 로 뽑은 DDL 은 위
3가지를 손봐야 실행된다."*

| 항목 | DBML | SQL 에서 |
| --- | --- | --- |
| `place.geom` | `geography` | `GEOGRAPHY(Point,4326)` — DBML 이 괄호·쉼표 타입을 파싱하지 못해 note 로 뺐다 |
| 정렬 인덱스 | `(popularity_score, id)` | `... DESC, id DESC` — DBML 에 `DESC` 문법이 없다 |
| `search_term` | Table | **MATERIALIZED VIEW** — DBML 에 뷰 개념이 없어 Table 로 표현했을 뿐이다. Table 로 만들면 안 된다 |
| Enum 7 개 | `Enum lang { … }` | **TEXT + 제약** — 아래 |

`search_term` 은 다섯 곳(`place_i18n`·`place_alias`·`content_i18n`·`content_alias`·
`person_i18n`)을 `UNION ALL` 한 MV 다. 적재 후 `REFRESH MATERIALIZED VIEW CONCURRENTLY`
가 필요하며, `CONCURRENTLY` 를 쓰려면 **유니크 인덱스가 하나 있어야 한다.**

### 넷째 항목 — Enum 은 DBML 의 표현이지 스키마의 결정이 아니다

문서가 셋을 지적했지만 실제로 옮겨 보니 넷이었다. **원본 문서(MZ2AZ-111)는 `lang`,
`category`, `role_type`, `trans_status`, `entity_type` 을 전부 `TEXT` 로 정의한다.**
DBML 쪽이 Enum 인 것은 DBML 에 CHECK 제약 문법이 없어 "값이 이 넷 중 하나" 를 적을
방법이 Enum 밖에 없었기 때문이고, `geography` 나 `search_term` 이 Table 로 표현된 것과
같은 종류의 변환이다. 실제로 DBML 문서의 "DBML로 옮기면서 생긴 차이" 표에 그렇게 적혀
있다.

실무적으로도 TEXT 쪽이 맞다. PostgreSQL Enum 은 **값을 뺄 수 없고**, 추가한 값은 같은
트랜잭션 안에서 쓸 수 없다 — Flyway 는 마이그레이션을 트랜잭션으로 감싸므로 "값 추가 +
그 값으로 데이터 이관" 을 한 마이그레이션에 담을 수 없게 된다. CHECK 제약은 DROP/ADD 로
갈아 끼우면 끝난다.

| 값 | SQL |
| --- | --- |
| `lang`, `trans_status` | **DOMAIN** — 여러 테이블이 공유하므로 제약을 한 곳에서 고칠 수 있게 |
| 나머지 | 컬럼에 **CHECK** 직접 |

`search_term.entity_type` 과 `user_event.entity_type` 은 값이 같아도 각자 CHECK 를
가진다. DBML 이 두 Enum 으로 분리한 의도(늘어날 방향이 다르다)를 그대로 옮긴 것이다.

## 5. 명세와 스키마가 어긋나는 지점

**이미 머지된 API 명세가 약속했는데 DBML v1 에 자리가 없는 컬럼이 둘 있다.**

```
ContentSummary   id · category · title · posterUrl · broadcaster · releaseYear · genres · placeCount
DBML content     id · category · broadcaster · poster_url · popularity_score · created_at · updated_at
                                                        ↑ releaseYear · genres 에 대응하는 것이 없음
```

이건 [MZ2AZ-116 §5.2](https://mz2az.atlassian.net/browse/MZ2AZ-116) 의 갭 1·2 번이고,
검색·지도 계획 문서 §7 에도 후속 항목으로 적어 두었던 것이다. **명세를 낼 때는
"데이터가 실제로 있으니 넣는다" 로 정했는데, 그 데이터를 담을 컬럼을 만들지 않으면
명세가 거짓이 된다.**

**결정: 스키마에 컬럼을 더한다.** `content.release_year INT` 와 `content.genres TEXT[]`
를 넣는다. (컬럼 이름은 명세의 `genres` 에 맞췄다. 별도 테이블로 빼지 않은 이유는
장르로 조인·집계할 요구가 아직 없고 명세도 문자열 배열로 내보내기 때문이다 — 장르별
필터가 생기면 그때 코드 테이블로 승격한다.) 근거는 셋이다 — V6 CSV 의 `title_year`·`title_genre` 가 4 작품 전부 채워져
있고, 목업의 작품 카드가 연도·장르를 표시하며, 명세가 이미 머지되어 프론트에
전달됐다. 명세에서 빼는 것은 이미 전달한 계약을 줄이는 변경이라 더 비싸다.

**이것은 MZ2AZ-111 확정본(스키마 v1)을 건드리는 변경이다.** DBML 문서의 `content`
Note 는 `air_period`·`air_status` 를 "TEXT 범위라 정렬 불가 + 파생값이라 낡는다" 는
이유로 뺐는데, [MZ2AZ-116 §5.3-2](https://mz2az.atlassian.net/browse/MZ2AZ-116) 가
지적하듯 **연도 단일 INT 에는 그 두 문제가 없다** — 정렬 가능하고 낡지 않는다.
DBML 문서에도 이 변경을 반영해야 한다(후속).

## 6. 무엇이 어디에 놓이나

```
platform/kubernetes/postgres/
├── statefulset.yaml            PostGIS. 데이터가 재시작에 살아남아야 하므로 Deployment 가 아니다
├── service.yaml                클러스터 내부 전용 (ClusterIP). 외부로 열지 않는다
├── configmap.yaml              DB 이름·사용자. 로컬 전용 고정값
└── pvc 는 StatefulSet 의 volumeClaimTemplates 로

services/scene-api/src/main/resources/db/migration/
├── V1__extensions.sql          postgis · pg_trgm
├── V2__schema.sql              테이블 14 개 + 인덱스
├── V3__search_term.sql         search_normalize() + MATERIALIZED VIEW + 유니크 인덱스
└── V4__drop_unused_postgis_extensions.sql
                                이미지가 기본으로 켜는 tiger geocoder·topology 제거

services/scene-api/seed/
├── candidates.csv              성지후보 10작품 87행 (김태환 수집 v3, 2026-08-24)
├── candidates.sql              CSV → 15 개 테이블 변환 (place_alias 포함)
├── poi-sample.jsonl            POI 표본 23 행 — 전량은 저장소 밖 (poi.md §5-3)
├── poi.sql                     JSONL → poi. UPSERT 라 지우지 않는다
└── README.md                   표본 선정 근거와 정제 전이라 감수한 것들

tools/scripts/seed.sh           CSV 를 파드로 옮기고 candidates.sql 을 먹인다
tools/scripts/seed-poi.sh       JSONL(.gz) 을 파드로 옮겨 풀고 poi.sql 을 먹인다
tools/scripts/poi-filter.sh     POI 원본 → 허용목록 갈래만
tools/just/k8s.just             seed · seed-poi · poi-filter 레시피
```

계획과 두 곳이 다르다. **`src/main/resources/seed/` 가 아니라 `seed/`** 인 이유는
앱이 이 CSV 를 읽지 않기 때문이다 — 읽는 것은 `seed.sh` 다. `resources/` 에 두면
쓰지도 않는 데이터가 실행 jar 에 들어간다. **레시피가 `dev.just` 가 아니라
`k8s.just`** 인 이유는 적재가 클러스터의 DB 파드를 상대로 돌기 때문이다 — `db-psql`
같은 이웃 레시피와 같은 자리다.

## 7. 완료 조건

```
1. just deploy postgres local        파드 Running, PVC 바인딩              ✅ MZ2AZ-176
2. just deploy scene-api local       Flyway 마이그레이션이 돌고 앱이 뜬다   ✅ MZ2AZ-176
3. just db-schema                    테이블 14 개 확인                      ✅ MZ2AZ-176
4. just seed                         샘플이 들어간다                        ✅ MZ2AZ-177
5. search_term 조회                  '도깨비' · 'Goblin' 둘 다 걸린다       ✅ MZ2AZ-177
6. 반경 검색 SQL                     GiST 인덱스를 타는지 EXPLAIN 으로 확인  ✅ MZ2AZ-176
7. just check                        게이트 초록                            ✅
```

5 번이 핵심이다. 스키마가 제대로 섰는지는 테이블 개수가 아니라 **검색이 실제로
동작하는가**로 판정한다. 6 번은 5,000 개를 흩뿌린 뒤 `Bitmap Index Scan on
place_geom_idx` 를 확인했다.

적재 후 실측:

```
표본 12 행  →  작품 4 · 장소 10 · place_content 12 · search_term 45
전량 164 행 →  작품 4 · 장소 155 · place_content 157 · search_term 190

'도깨' 검색       도깨비 (ko, 109)  ·  Goblin (lang 없음, 89)
'케데헌' 검색     케데헌 (content)              ← 별칭
'공유' 검색       공유 (person)                 ← 인물
북촌한옥마을      도깨비 / 케이팝 데몬 헌터스   ← N:M
일월수목원        눈물의 여왕 / 이태원 클라쓰   ← N:M
광화문 반경 5km   북촌한옥마을 955m · 낙산공원 2754m · N서울타워 2908m
```

`just seed` 를 두 번 연속 돌려 결과가 같은 것도 확인했다(작품 4 · 장소 10 · id 1~10).
전량 164 행도 같은 변환으로 오류 없이 들어간다 — 저장소에 두지 않을 뿐 변환이
표본 전용은 아니다.

## 7-1. 만들면서 드러난 것 (실측)

문서만으로는 알 수 없었고 실제로 돌려 보고서야 나온 것들이다. 셋 다 **조용히 실패하는**
종류라 적어 둔다.

| 무엇 | 증상 | 대응 |
| --- | --- | --- |
| Spring Boot 4 는 자동 구성을 기술별 모듈로 쪼갰다 | `flyway-core` 만 넣으면 **오류 없이 마이그레이션이 안 돈다.** 앱은 뜨고 헬스체크도 초록이다 | `spring-boot-starter-flyway` 를 넣어야 `FlywayAutoConfiguration` 이 온다 |
| DB 로케일이 `C` 라 `[[:alnum:]]` 이 ASCII 만 문자로 친다 | 정규화를 allowlist(남길 문자 지정)로 쓰면 `도깨비` 가 통째로 지워져 빈 문자열이 된다 | blocklist(지울 문자 지정)로 뒤집었다 — `search_normalize()` |
| PostGIS 이미지가 `postgis_tiger_geocoder`·`postgis_topology` 를 기본으로 켠다 | 테이블 35 개와 스키마 셋이 생겨 `\dt` 가 우리 스키마를 보여 주지 못한다 | `V4` 에서 제거. 미국 주소 지오코딩과 위상 모델은 쓰지 않는다 |

한 가지 더 — **적용된 마이그레이션 파일은 고치지 않는다.** Flyway 가 체크섬을 기록해
두어 파일이 바뀌면 다음 기동에서 검증 실패로 죽는다. 위 셋째 항목을 `V1` 에 넣지 않고
`V4` 로 붙인 이유가 그것이다.

적재(177)에서도 하나 더 나왔다. **`ON CONFLICT` 로 멱등을 만들 수 없다.** `place` 는
`naver_place_url` 이 유니크라 되지만 `content`·`person` 에는 자연키가 없다 — 같은
작품을 두 번 넣어도 DB 는 그것이 같은 작품인지 알 방법이 없다. 그래서 `just seed` 는
**시드 데이터를 통째로 갈아 끼운다**(`TRUNCATE ... RESTART IDENTITY CASCADE`).
로컬 개발 DB 의 표본 데이터라서 성립하는 방식이고, `seed.sh` 가 kind 컨텍스트가
아니면 실행을 거부한다.

수집 데이터 자체의 문제도 드러났다. 정제 전이라 예상된 것이고, 스키마를 바꾸지 않고
넘어간다 — 목록은 [`services/scene-api/seed/README.md`](../../../services/scene-api/seed/README.md)
에 있다. 가장 눈에 띄는 것은 **같은 장소의 `place_type` 이 행마다 다른 경우**다
(일월수목원 = `자연` · `공원`).

## 8. 아직 정하지 않은 것

| # | 항목 | 성격 |
| --- | --- | --- |
| 1 | ~~`release_year`·`genre` 컬럼~~ | **결정됨(§5)** — 더한다. DBML 문서 갱신은 후속 |
| 2 | 스키마 v1 의 나머지 미결 항목 | DBML 문서 말미의 `search_term.subtitle`, `user_event.surface`, `scene_image`, `route`, 사용자 테이블 — 전부 이번 범위 밖으로 둔다 |
| 3 | 원격 환경의 DB | `platform/environments/` 가 비어 있다. 로컬만 만든다 |
| 4 | ~~MVP2 테이블(`user_event`·`saved_place`)~~ | **결정됨** — 만들었다. `user_id` 에만 FK 를 걸지 않았다. `impression`·`position` 은 소급 수집이 불가능해서 지금 만든다 |
| 5 | 스키마 마이그레이션의 자동 테스트 | 지금은 단위 레인이 **파일이 패키징됐는지**까지만 본다(`MigrationResourceTest`). 스키마가 실제로 서는지는 `just deploy` 후 사람이 확인한다. Testcontainers 로 통합 레인에 넣을 수 있으나 Maven 의존성과 테스트 시점 Docker 요구가 늘어난다 — 엔드포인트(149)가 생겨 검증할 것이 많아질 때 함께 판단한다 |

## 9. 후속 (이 티켓 밖)

| 항목 | 내용 |
| --- | --- |
| DBML 문서 갱신 | `content.release_year`·`content.genres` 추가와 Enum→TEXT 를 [MZ2AZ-111](https://mz2az.atlassian.net/browse/MZ2AZ-111) 에 반영 |
| 인기도 계산 | 지금은 적재 시 하드코딩. 사용자 행동(`user_event`)이 쌓이면 배치로 계산 |
| 장르 다국어 | `content.genres` 는 현재 수집 언어(한국어) 문자열이다. 장르별 필터가 생기면 코드 테이블로 승격하고 i18n 을 붙인다 |
| 원격 환경 DB | `platform/environments/` 가 생기면 ConfigMap 참조를 Secret 참조로 바꾼다. 애플리케이션은 그대로다 |
| `place_type` 코드표 | 지금은 한국어 라벨 37 종이 그대로 들어간다. 스키마 주석은 '코드값' 이라고 되어 있으나 매핑표가 정의된 적이 없다. 유형 필터가 생길 때 함께 정한다 |
| 장소 다국어 | `place_i18n` 에 `ko` 만 있다. 수집 데이터에 영어 장소명이 없다 — 번역이 들어오면 채운다 |
| 데이터 정제 | `place_type` 이 같은 장소에서 갈리는 문제 등. 정제된 V7 이 나오면 `just seed <새 경로>` 로 갈아 끼운다 |
