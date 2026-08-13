# scene-api

> 모듈 종류: `service` · 언어: `java` · 경로: `services/scene-api`

## 목적

SceneTrip 앱의 **검색·지도 탭**과 **경로여정 탭**을 받치는 백엔드다. 작품·촬영지·인물을
검색하고, 지도 뷰포트 안의 촬영지를 조회하고, 방문할 장소를 장바구니에 담고, 담은
장소로 며칠짜리 코스를 짜는 API 를 제공한다.

엔드포인트는 **26개**이고 전부 구현돼 있다. 경로는 명세의 `servers.url` 을 따라 `/v1`
아래에 있다.

> **경로여정 탭은 절반쯤 와 있다.** 명세(`scene-api-v1.yaml` v1.1.0)에 코스·작품찜·
> 마켓·길찾기 경로 18개 중 **17개가 구현됐다** — 코스 7
> ([229](https://mz2az.atlassian.net/browse/MZ2AZ-229) ·
> [230](https://mz2az.atlassian.net/browse/MZ2AZ-230)), 작품 찜 3
> ([231](https://mz2az.atlassian.net/browse/MZ2AZ-231)), 마켓 7
> ([232](https://mz2az.atlassian.net/browse/MZ2AZ-232)). 남은 하나는 길찾기(233)이고
> 선행 티켓을 기다리며 `501` 이다.
>
> **마켓의 쓰기 넷(올리기·담기·좋아요·내리기)은 지금 아무도 통과하지 못한다.** 가입
> 사용자만 할 수 있는데 가입시키는 경로가 아직 없다(로그인 8/23 주차). 의도한 상태이고
> 저장소 로직은 통합 테스트가 실제 DB 로 확인한다 — `app_user.registered_at` 이 채워지는
> 순간 열린다.
>
> **스키마는 이미 들어와 있다** (`V8`~`V10`). 그 과정에서 장바구니가 `cart_item` 에서
> `saved_place` 로 옮겨 갔고 주체가 설치 UUID 에서 `app_user.id` 로 바뀌었다 —
> 아래 [데이터베이스](#데이터베이스) 참고. **계약과 앱은 그대로다.**

| 경로 | 하는 일 |
| --- | --- |
| `GET /v1/search/suggestions` | 자동완성. **이름·별칭만** 본다 (설명 제외) |
| `GET /v1/contents` | 작품 목록·검색 |
| `GET /v1/contents/{contentId}` | 작품 상세 |
| `GET /v1/contents/{contentId}/places` | 한 작품의 촬영지 |
| `GET /v1/places` | 촬영지 목록·검색·지도 조회 (`q`·`bbox`·반경) |
| `GET /v1/places/{placeId}` | 촬영지 상세 |
| `GET /v1/cart` | 장바구니 조회 |
| `POST /v1/cart/items` | 장바구니에 담기 |
| `DELETE /v1/cart/items/{placeId}` | 장바구니에서 빼기 |
| `GET /v1/courses` | 내 코스 목록. 여행 중이 위, 예정이 아래 |
| `POST /v1/courses` | 코스 만들기. **받는 것은 기간과 만든 방식뿐** |
| `GET /v1/courses/{courseId}` | 코스 상세 — 일차·장소·하루 소요 시간 |
| `PUT /v1/courses/{courseId}` | **편집 완료.** 코스 전체 교체 |
| `DELETE /v1/courses/{courseId}` | 코스 삭제 |
| `PUT /v1/courses/{courseId}/progress` | 코스 시작·일차 이동 (여행 중, 즉시) |
| `PUT /v1/courses/{courseId}/items/{itemId}/visit` | 방문 체크 (여행 중, 즉시) |
| `GET /v1/favorites/contents` | 찜한 작품 목록 |
| `POST /v1/favorites/contents` | 작품 찜하기 |
| `DELETE /v1/favorites/contents/{contentId}` | 찜 해제 |
| `GET /v1/market/courses` | 올라온 코스 목록. `q`=작품 이름, `sort`=담기순·좋아요순 |
| `POST /v1/market/courses` | 올리기 — **사본을 뜬다** |
| `GET /v1/market/courses/{marketCourseId}` | 올라온 코스 상세 |
| `DELETE /v1/market/courses/{marketCourseId}` | 내리기 |
| `POST /v1/market/courses/{marketCourseId}/saves` | 담기 — 내 코스로 복사 |
| `POST /v1/market/courses/{marketCourseId}/likes` · `DELETE …` | 좋아요 토글 |

**코스 편집은 완료를 누를 때 한 번이다.** 제목·기간·장소·순서·체류시간을 고치는 동안
서버로는 아무것도 나가지 않고, `PUT` 이 최종 모습을 통째로 받는다. 그래서 되돌리기가
공짜이고 반쪽만 반영되는 상태가 없다. 여행 중 동작(시작·일차 이동)만 즉시다.

`PUT` 에서 **기존 장소는 받았던 `id` 를 그대로 실어 보내야 한다.** 빠뜨리면 새 장소로
보아 방문 체크가 사라진다. 요청에 없는 항목은 지워지고, 그 항목이 직접 찍은 핀이었다면
핀도 함께 사라진다.

**체류시간을 비워 보내면 서버가 장소 유형에 맞는 기본값을 채운다** — 고궁 90, 카페 40,
다리 15, 모르는 유형 60. 표는 `application.yaml` 의 `scenetrip.course.dwell` 하나가
들고, 프론트는 그것을 알 필요가 없다. 기동 로그에 `체류시간 기본값 N종 적재` 가 찍히니
**N 이 0 이면 설정이 안 붙은 것이다** — 그 상태로도 서비스는 멀쩡히 돌기 때문에 로그가
유일한 신호다.

**작품에는 찜, 장소에는 장바구니다. 장소에 찜은 없다** (8/11 회의). 표도 따로다 —
`saved_content` 와 `saved_place`. 그리고 **찜은 담기도 빼기도 멱등이라 `204` 뿐이다.**
하트는 토글이라 같은 상태를 두 번 요청하는 일이 흔한데, 그때마다 오류를 내면 프론트가
사용자에게 보여 줄 것이 없다 — 장바구니가 중복에 `409` 를 내는 것과 갈리는 지점이다.

**마켓에 올린 코스는 사본이다.** 올리는 순간의 일차·순서·머무는 시간을 통째로 뜬다.
원본을 고쳐도 마켓의 것은 그대로다 — 남이 담아 간 코스가 갑자기 바뀌면 안 되기 때문이다.
그래서 고치는 방법이 없고 「내리기」가 짝으로 있다. **직접 찍은 핀은 올릴 때 빠진다**
(개인 숙소 위치). 장소 정보 자체는 사본으로 굳히지 않고 `place` 를 계속 참조한다 —
사본이어야 하는 것은 장소가 아니라 순서와 머무는 시간이다.

**검색은 통합검색이다.** `GET /contents?q=` 와 `GET /places?q=` 가 같은 텍스트 뭉치(장소명·
장소 설명·작품 제목·작품 설명·인물 이름)를 보고, 걸린 것을 `place_content` 로 **양방향**
으로 옮긴다. 그래서 `도깨비` 를 쳐도 `북촌한옥마을` 을 쳐도 두 탭이 모두 채워진다.
엔드포인트를 나눈 이유는 탭마다 페이지네이션이 따로 필요해서다.

두 방향이 어긋나면 한쪽 탭이 조용히 비므로 `SearchSymmetryIntegrationTest` 가 지킨다
(아래 [테스트](#테스트)).

## 인터페이스

| 항목 | 값 |
| --- | --- |
| 프로토콜 | HTTP |
| 컨테이너 포트 | `8080` |
| 클러스터 노출 | NodePort `30081` → 호스트 `8081` (`platform/kind/cluster.yaml` 이 매핑) |
| 경로 접두사 | `/v1` — `server.servlet.context-path`. 명세의 `servers.url` 이 정본이다 |
| 계약 | [`contracts/openapi/scene-api-v1.yaml`](../../contracts/openapi/scene-api-v1.yaml) — 컨트롤러가 여기서 **생성된 인터페이스를 구현**한다. 명세와 어긋나면 컴파일이 실패한다 |

`/v1` 은 손으로 붙인 접두사다. OpenAPI 의 `servers.url` 은 "이 API 가 어디에 매달려 있는가"
이고 `paths` 는 그 아래의 경로라, 생성기는 서버 인터페이스에 `/places` 만 매핑한다 —
접두사는 배포가 붙이는 몫이다. **actuator 도 함께 밀린다**(아래 상태 점검 경로).

응답에서 **값이 없는 필드는 빠진다** (`spring.jackson.default-property-inclusion: non_null`).
기준점 없이 부른 목록에 `distanceMeters` 가 없고, 500 이 아닌 오류에 `traceId` 가 없다.

### 상태 점검 경로

| 경로 | 용도 |
| --- | --- |
| `/v1/actuator/health` | 전체 상태. `startupProbe` 가 본다 |
| `/v1/actuator/health/liveness` | 프로세스가 살아 있는가. `livenessProbe` |
| `/v1/actuator/health/readiness` | 트래픽을 받을 준비가 됐는가. `readinessProbe` |

`liveness`·`readiness` 그룹은 Spring 기본값이 꺼져 있어 `application.yaml` 에서 켰다.

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| Spring Boot 4.1 (`web`, `actuator`) | HTTP 서버와 상태 점검 |
| `@maven//:...spring_boot_loader` | 실행 가능 jar 의 부트스트랩. `springboot()` 의 `deps` 로 넘긴다 |
| Spring Boot 4.1 (`jdbc`, `flyway`) | DataSource 와 마이그레이션 자동 구성 |
| Spring Boot 4.1 (`validation`) | `@Size`·`@Min` 을 **실제로 검사하는 구현**. 없으면 표식만 붙고 검사가 일어나지 않아, 명세가 400 을 약속한 요청이 통과한다 |
| `spring-webmvc` | `NoResourceFoundException` — 없는 경로를 404 로 바꾼다. `spring-web` 이 아니다 |
| `contracts/openapi:scene_api_spring_lib` | 명세에서 생성한 인터페이스와 응답 모델 |
| Flyway 12.4 + `flyway-database-postgresql` | 스키마 마이그레이션 |
| PostgreSQL JDBC 42.7 | 드라이버 |
| **PostgreSQL 17 + PostGIS 3.5** | `platform/kubernetes/postgres/`. 같은 네임스페이스의 `postgres` Service |

`libs/` 와 다른 서비스에는 아직 의존하지 않는다.

## 데이터베이스

스키마는 [MZ2AZ-111 DBML](https://mz2az.atlassian.net/browse/MZ2AZ-111) 을 옮긴 것이고,
옮기면서 손본 곳은 [계획 문서 §4](../../docs/project/plans/scene-api-database.md) 에 있다.

마이그레이션은 `src/main/resources/db/migration/` 에 있고 **앱이 기동할 때 Flyway 가
돌린다.** DB 가 먼저 떠 있어야 하므로 Deployment 에 `pg_isready` 로 기다리는
initContainer 가 있다.

| 파일 | 내용 |
| --- | --- |
| `V1__extensions.sql` | `postgis` · `pg_trgm` |
| `V2__schema.sql` | 테이블 14 개 + 인덱스 |
| `V3__search_term.sql` | `search_normalize()` 함수와 `search_term` MATERIALIZED VIEW |
| `V4__drop_unused_postgis_extensions.sql` | 이미지가 기본으로 켜는 tiger geocoder·topology 제거 |
| `V5__cart_item.sql` | 장바구니. **V8 이 이 표를 없앴다** (아래) |
| `V6__place_geometry_index.sql` | 지도 뷰포트 질의(`geom::geometry`)가 탈 표현식 GiST 인덱스 |
| `V7__place_content_scene_image.sql` | 장면 스틸 URL |
| `V8__app_user.sql` | `app_user`·`user_device`, `cart_item` → `saved_place`, `user_event.user_id` → UUID |
| `V9__course.sql` | `course`·`custom_pin`·`course_item`·`saved_content` |
| `V10__market.sql` | `market_course`·`_item`·`_content`·`market_like` |

### 주체는 계정이다 — 설치 UUID 가 아니다

```
X-Device-Id 헤더 (설치 UUID) → user_device.install_uuid → app_user.id → 저장
```

앱이 보내는 값은 그대로다. 변환은 `UserStore.resolve()` 안에 갇혀 있어 **계약에도 앱에도
드러나지 않는다.** 처음 보는 설치본이면 비회원 계정을 하나 만들어 준다.

설치 UUID 를 주체로 쓰지 않는 이유는 그것이 사람이 아니라 **설치본**을 가리키기
때문이다. 앱을 지웠다 깔면 새로 생기는데 그것이 주체이면 그 사람의 장바구니와 코스가
통째로 끊긴다. 로그인이 붙으면 `user_device` 가 가리키는 곳만 바꿔 달면 되고 데이터는
움직이지 않는다.

`app_user.id` 는 **애플리케이션이 만들어 넣는다.** 설계는 UUIDv7 을 권하지만
`uuidv7()` 이 PostgreSQL 18 부터라(우리는 17) 컬럼에 기본값을 두지 않았다 —
`gen_random_uuid()` 를 기본값으로 걸면 v4 가 되고, 나중에 v7 로 바꿔도 이미 들어간 행은
v4 로 남아 두 세대가 섞인다. PG 18 로 올라가면 `DEFAULT uuidv7()` 을 붙이고 애플리케이션
쪽을 지운다.

**적용된 마이그레이션 파일은 고치지 않는다.** Flyway 가 체크섬을 기록해 두어, 파일이
바뀌면 다음 기동에서 검증 실패로 죽는다. 뒤에 새 번호로 붙인다.

```bash
just db-schema            # 테이블·뷰·확장
just db-migrations        # 적용 이력. 실패한 것은 success 가 f
just db-psql              # psql 접속 (파드 안에서 실행)
just db-refresh-search    # 적재 후 search_term 갱신
```

### 데이터 적재

스키마는 Flyway 가, **데이터는 `just seed` 가** 넣는다. 마이그레이션에 `INSERT` 를
넣지 않은 이유는 데이터가 바뀔 때마다 마이그레이션이 쌓이기 때문이다 — 정제된
V7·V8 이 나와도 명령 한 번으로 갈아 끼운다.

```bash
just seed                    # 저장소의 표본 12 행
just seed <볼트 CSV 경로>    # 전량
```

**적재된 기존 데이터를 지우고 다시 넣는다.** 자세한 것은
[`seed/README.md`](seed/README.md).

### search_term 은 왜 MATERIALIZED VIEW 인가

검색어 색인은 `place_i18n`·`place_alias`·`content_i18n`·`content_alias`·`person_i18n`
다섯 곳에서 나온 파생값이다. 테이블로 두면 원본이 바뀔 때마다 동기화해야 하고, 그
동기화가 어긋나면 "DB 에는 있는데 검색은 안 되는" 상태가 조용히 생긴다. MV 는 정의가
곧 동기화 규칙이다.

정규화(`소문자 + 공백·구두점 제거`)는 DB 함수 `search_normalize()` 하나로 두었다.
색인할 때와 조회할 때 정규화가 다르면 오류 없이 결과가 0 건이 된다 — 애플리케이션이
Java 로 따로 구현하지 않고 이 함수를 부른다.

## 빌드 방식

**Bazel 이 실행 가능 jar 를 만들고 컨테이너는 그것을 담기만 한다**
([ADR 0003](../../docs/architecture/adr/0003-spring-boot-bazel.md)).
Gradle·Maven 을 빌드 경로로 쓰지 않으므로 이 모듈에 `build.gradle` 은 없다.
의존성은 루트 `MODULE.bazel` 한 곳에서 관리한다.

`Dockerfile` 은 빌드하지 않는다 — 최종 이미지에 JDK 가 들어가지 않는다.
빌드 컨텍스트는 `tools/scripts/image-build.sh` 가 임시 디렉터리에 만든다.

## 테스트

레인이 둘이고 **보는 것이 다르다.**

| 타깃 | 레인 | 무엇을 보는가 | DB |
| --- | --- | --- | --- |
| `:unit_test` | `just test` | HTTP 계층 — 파라미터 검증, 오류 코드, 응답 모양 | 필요 없음 (Store 를 가짜로 끼움) |
| `:integration_test` | `just test-integration` | Store 의 SQL 이 실제로 성립하는가 | **실제 PostgreSQL** |

통합 테스트가 필요한 이유는 단위 테스트가 Store 를 가짜로 바꿔 끼우기 때문이다. 그
상태에서는 SQL 이 한 줄도 실행되지 않아, 컬럼 이름 오타나 `ST_DWithin` 의 인자 순서
같은 것이 게이트를 그대로 통과해 운영에서 처음 터진다. 실제로 검색 분기 하나가 빠져
장소 이름으로 검색하면 작품 탭이 비어 있었는데, 게이트는 내내 초록이었다.

```bash
just cluster-up          # 최초 1회 — 클러스터와 postgres
just seed                # 데이터 적재
just db-refresh-search   # 검색 색인 갱신
just test-integration    # 포트포워드를 세우고 실행한다
```

`just test-integration` 이 `kubectl port-forward` 를 세우고 접속 정보를 환경변수로
넘긴다. 접속 정보가 없으면 테스트는 **건너뛰지 않고 실패한다** — 조용히 0 개 실행되고
초록인 상태가 가장 나쁘기 때문이다.

가장 중요한 것은 `SearchSymmetryIntegrationTest` 다. "검색어 하나가 작품 탭과 장소 탭을
모두 채운다" 를 지킨다. 두 Store 의 매칭 규칙이 각자의 SQL 에 나뉘어 적혀 있어, 검색
대상을 늘릴 때 한쪽만 고치면 다시 어긋난다.

## 명령

```bash
just build-module services/scene-api    # 빌드
just test-module  services/scene-api    # 테스트
just test-integration                   # 실제 DB 에 SQL 을 태우는 레인
just run //services/scene-api:bin       # 로컬 실행 (기본 8080)

just stack-up                           # 클러스터부터 데이터까지 한 번에 (아래 참고)

just image  scene-api                   # 이미지 굽고 kind 에 적재
just deploy scene-api local             # 클러스터에 배포
just seed                               # 데이터 적재 — 배포만으로는 DB 가 비어 있다
just db-refresh-search                  # 검색 색인 갱신 — 없으면 검색만 0 건
just update scene-api                   # 코드 변경 후 재빌드 → 적재 → 롤링 재시작
just logs   scene-api                   # 로그 따라가기
```

**단계를 직접 밟을 때 빠지는 것이 `seed` 와 `db-refresh-search` 다.** 둘 다 빠져도
배포는 성공하고 health 는 초록이며, `/v1/contents` 가 200 에 빈 배열을 준다. 화면만
비고 아무것도 깨지지 않아 가장 찾기 어렵다. 그래서 순서를 기억하지 않아도 되도록
`just stack-up` 이 전 단계를 묶고, 마지막에 실제 요청으로 건수까지 확인한다.

배포 후 확인 — `port-forward` 가 필요 없다.

```bash
curl http://localhost:8081/v1/actuator/health
```

## 설정

기본값은 `src/main/resources/application.yaml`, 환경별 덮어쓰기는
`platform/kubernetes/scene-api/configmap.yaml`.

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 아니오 | 없음 | 활성 프로파일. 로컬 배포는 `local` |
| `JAVA_TOOL_OPTIONS` | 아니오 | 없음 | 힙 비율. 컨테이너 메모리 한도에 맞춘다 |
| `SPRING_DATASOURCE_URL` | 아니오 | `jdbc:postgresql://postgres:5432/scenetrip` | DB 주소 |
| `SPRING_DATASOURCE_USERNAME` | 아니오 | `scenetrip` | DB 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | **예** | 없음 | DB 비밀번호. 값이 없으면 접속이 거부되어 기동이 실패한다 |

비밀번호는 `application.yaml` 에 두지 않는다 — 이미지 안에 박히면 이미지를 가진 사람이
곧 자격 증명을 가진 것이 된다. 로컬에서는 `postgres` ConfigMap 의 키를
`configMapKeyRef` 로 가리키고, 원격에서는 같은 자리가 `secretKeyRef` 로 바뀐다.
접속 정보의 정본은 DB 를 정의한 쪽 하나다.

시크릿은 시크릿 매니저에서 온다. 커밋하는 것은 `.env.example` 뿐이다.

## 운영

컨테이너는 **비-root(UID 10001)** 로 돌고 루트 파일시스템이 읽기 전용이다.
`/tmp` 만 쓰기 가능한 임시 볼륨으로 열려 있다 — JVM 과 Tomcat 이 거기 쓴다.

종료는 `SIGTERM` → graceful shutdown 이다. `Dockerfile` 의 `ENTRYPOINT` 가 exec
형식이라 `java` 가 PID 1 이 되고 신호를 직접 받는다.

## 관측성

**OpenTelemetry 자바 에이전트가 이미지 안에 있다**([ADR 0004](../../docs/architecture/adr/0004-opentelemetry-javaagent.md)).
코드에 계측이 없고 `-javaagent` 가 `ENTRYPOINT` 에 붙어 있다. Spring MVC·JDBC·
HikariCP·Logback 이 자동으로 계측된다.

에이전트 버전과 sha256 은 `MODULE.bazel` 한 곳이 정본이고, `just image scene-api` 가
Bazel 로 받아 이미지에 담는다. 보낼 곳은 `platform/kubernetes/scene-api/configmap.yaml`
의 `OTEL_*` 이 정한다.

```bash
just signoz              # UI 주소와 필터 안내
just signoz-verify       # 텔레메트리가 실제로 적재됐는지 ClickHouse 로 확인
just signoz-forward      # 맥에서 직접 실행할 때 OTLP 포워딩
```

**500 응답의 `traceId` 가 SigNoz 의 트레이스 ID 다.** 사용자가 그 문자열 하나를
알려 주면 요청 하나를 그대로 찾을 수 있다. 에이전트가 MDC 에 넣어 준 값을 읽으므로
애플리케이션에 OpenTelemetry 의존성이 없다 — 에이전트가 없으면 그냥 비어 있다.

> SigNoz 에 **관리자 계정이 없으면 수집기가 파이프라인 설정을 받지 못해 OTLP 포트
> 자체가 열리지 않는다.** 앱에는 `Connection refused` 로 보인다.
> `docs/installs/signoz_install.md` 참조.

런북: `docs/ops/` · 대시보드: SigNoz · 알림: 미정
