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

`search_term` 은 다섯 곳(`place_i18n`·`place_alias`·`content_i18n`·`content_alias`·
`person_i18n`)을 `UNION ALL` 한 MV 다. 적재 후 `REFRESH MATERIALIZED VIEW CONCURRENTLY`
가 필요하며, `CONCURRENTLY` 를 쓰려면 **유니크 인덱스가 하나 있어야 한다.**

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

**결정: 스키마에 컬럼을 더한다.** `content.release_year INT` 와 `content.genre` 를
넣는다. 근거는 셋이다 — V6 CSV 의 `title_year`·`title_genre` 가 4 작품 전부 채워져
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
└── V3__search_term.sql         MATERIALIZED VIEW + 유니크 인덱스

services/scene-api/src/main/resources/seed/
└── v6-sample.csv               확인용 소수 행

tools/scripts/seed.sh           CSV → DB. `just seed` 가 호출
tools/just/dev.just             seed 레시피
```

## 7. 완료 조건

```
1. just deploy postgres local        파드 Running, PVC 바인딩
2. just deploy scene-api local       Flyway 마이그레이션이 돌고 앱이 뜬다
3. just db-psql \dt                  테이블 14 개 확인
4. just seed                         샘플이 들어간다
5. search_term 조회                  '도깨비' · 'Goblin' 둘 다 걸린다
6. 반경 검색 SQL                     GiST 인덱스를 타는지 EXPLAIN 으로 확인
7. just check                        게이트 초록
```

5 번이 핵심이다. 스키마가 제대로 섰는지는 테이블 개수가 아니라 **검색이 실제로
동작하는가**로 판정한다.

## 8. 아직 정하지 않은 것

| # | 항목 | 성격 |
| --- | --- | --- |
| 1 | ~~`release_year`·`genre` 컬럼~~ | **결정됨(§5)** — 더한다. DBML 문서 갱신은 후속 |
| 2 | 스키마 v1 의 나머지 미결 항목 | DBML 문서 말미의 `search_term.subtitle`, `user_event.surface`, `scene_image`, `route`, 사용자 테이블 — 전부 이번 범위 밖으로 둔다 |
| 3 | 원격 환경의 DB | `platform/environments/` 가 비어 있다. 로컬만 만든다 |
| 4 | MVP2 테이블(`user_event`·`saved_place`) | 사용자 테이블이 없어 `user_id` 가 참조할 대상이 없다. 스키마에는 만들되 FK 는 걸지 않는다 |
