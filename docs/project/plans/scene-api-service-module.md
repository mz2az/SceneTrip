# 백엔드 서비스 모듈 구현 계획 (MZ2AZ-181)

- **티켓**: [MZ2AZ-181](https://mz2az.atlassian.net/browse/MZ2AZ-181) — 상위 MZ2AZ-157
- **브랜치**: `MZ2AZ-181-백엔드-서비스-모듈`
- **작성일**: 2026-08-05
- **상태**: 진행 중

---

## 1. 무엇을 만드는가

`services/scene-api/` 를 만들고 로컬 kind 클러스터에 올려 **응답까지** 확인한다.

이번 범위는 **껍데기**다. 증명할 명제는 하나다 — *"우리 코드가 이 환경에서 실제로
뜬다."* 검색·지도 API 구현은 [MZ2AZ-149](https://mz2az.atlassian.net/browse/MZ2AZ-149),
DB 스키마와 적재는 [MZ2AZ-152](https://mz2az.atlassian.net/browse/MZ2AZ-152) 가 맡는다.

## 2. 범위 밖

| 항목 | 어디로 |
| --- | --- |
| DB(PostGIS) 연결, Flyway 마이그레이션 | MZ2AZ-152 |
| 검색·지도 엔드포인트 구현 | MZ2AZ-149 (166~171) |
| 생성된 OpenAPI 인터페이스 구현 | MZ2AZ-149 — 이번엔 계약과 코드를 연결하지 않는다 |
| `rules_oci` 기반 `:image` 타깃 | 후속 (ADR 0003 참조) |
| OpenTelemetry 연결 | MZ2AZ-182 |

**티켓이 참조한 `JiraDocs/MZ2AZ-157 Docker 개발 환경 구성.md` 는 볼트에 없다.** 스택
버전이 어디에도 기록돼 있지 않아 새로 정했다(§3).

## 3. 확정된 결정

빌드 경로 결정은 [ADR 0003](../../architecture/adr/0003-spring-boot-bazel.md) 에 있다.
요약하면 **Bazel 이 실행 가능 jar 를 만들고 `Dockerfile` 은 그것을 담기만 한다.**

| 항목 | 값 | 근거 |
| --- | --- | --- |
| 모듈 이름 | `scene-api` | `justfile` 예시와 SigNoz 기본 서비스명(`scenetrip-scene-api`)이 이미 이 이름을 쓴다 |
| Java | 21 (LTS) | 이 장비에 Temurin 21.0.11 설치됨. Spring Boot 3·4 모두 지원 |
| Spring Boot | **4.1 시도 → 실패 시 3.5** | 4.1 이 현재 안정판(무상 지원 2027-07). 다만 `rules_spring` 마지막 릴리스가 2025-02 로 Boot 4(2025-11) 이후 검증된 바 없음 |
| 빌드 | `rules_java` 9.7.0 · `rules_jvm_external` 7.1 · `rules_spring` 2.6.3 | BCR 에서 실측 확인(2026-08-05) |
| 컨테이너 | 단일 스테이지 `Dockerfile`, JRE 베이스, 비-root 실행 | Bazel 이 이미 빌드했으므로 빌드 단계가 필요 없다. 티켓의 "빌드 도구를 최종 이미지에 남기지 않는다" 를 더 강하게 달성한다 |
| 노출 | NodePort **30081** → 호스트 **8081** | `platform/kind/cluster.yaml` 에 이미 뚫려 있다. 그래서 `port-forward` 없이 `curl` 이 된다 |
| 자바 패키지 | `com.mz2az.scenetrip.sceneapi` | GitHub 조직명 `mz2az` 기준. 나중에 바꾸기 번거로우므로 여기 명시해 둔다 |

## 4. 무엇이 어디에 놓이나

```
services/scene-api/
├── BUILD.bazel                     springboot() 타깃 + java_test
├── README.md                       목적·포트·의존성·실행법
├── Dockerfile                      jar 만 담는다 (빌드하지 않음)
├── src/main/java/com/mz2az/scenetrip/sceneapi/
│   └── SceneApiApplication.java
├── src/main/resources/
│   └── application.yaml            포트·actuator 노출 설정
├── tests/                          모듈 단위 테스트
└── deploy/                         → platform/kubernetes/scene-api/ 를 가리키는 README

platform/kubernetes/scene-api/
├── deployment.yaml                 프로브 3종
├── service.yaml                    NodePort 30081
└── configmap.yaml                  환경별로 달라지는 값

MODULE.bazel                        Java 블록 주석 해제 + rules_spring
```

**매니페스트 위치에 문서 간 어긋남이 있다.** `AGENTS.md` §3 은 *"`services/<name>/deploy/`
— k8s/helm 오버레이"* 라 적었는데, `deploy.sh` 는 `platform/kubernetes/<모듈>/` 을
적용하고 `platform/kubernetes/README.md` 도 그렇게 규정한다. 티켓도 후자를 지시하므로
**`platform/kubernetes/scene-api/` 에 둔다.** 모듈의 `deploy/` 에는 그곳을 가리키는
README 만 두고, `AGENTS.md` §3 문구 정리는 후속으로 남긴다.

## 5. 프로브 3종

티켓이 명시적으로 요구한다 — *"준비되기 전에 트래픽을 받으면 그대로 죽는다."*

| 프로브 | 경로 | 무엇을 막나 |
| --- | --- | --- |
| `startupProbe` | `/actuator/health` | JVM·Spring 기동이 느릴 때 liveness 가 먼저 죽이는 것 |
| `readinessProbe` | `/actuator/health/readiness` | 준비 전에 Service 가 트래픽을 보내는 것 |
| `livenessProbe` | `/actuator/health/liveness` | 죽은 파드가 계속 살아 있는 것처럼 보이는 것 |

Spring Boot Actuator 의 `liveness`·`readiness` 그룹은 기본 비활성이라
`application.yaml` 에서 켜야 한다.

## 6. 완료 조건

티켓의 조건을 그대로 검증 절차로 옮긴다.

```
1. just build //services/scene-api/...     jar 가 만들어진다
2. just test  //services/scene-api/...     모듈 테스트가 통과한다
3. just image scene-api                    이미지를 굽고 kind 에 적재한다
4. just deploy scene-api local             파드가 Running, 재시작 0
5. curl http://localhost:8081/actuator/health   port-forward 없이 응답
6. just logs scene-api                     로그가 보인다
7. 코드 한 줄 고치고 just update scene-api  반영되는 것을 확인
8. just check                              게이트 초록
```

**3번의 `kind load` 를 빠뜨리면 오류 없이 옛 이미지가 계속 돈다.** `image-build.sh` 가
이미 처리하지만, 손으로 `docker build` 를 부르면 여기서 조용히 어긋난다.

## 7. 후퇴 기준

`rules_spring` + Spring Boot 4.1 조합이 검증된 바 없으므로 판정선을 미리 정한다.

```
판정선   실행 가능 jar 가 만들어지고 /actuator/health 가 응답하는가
1차      Spring Boot 4.1 로 시도
2차      실패하면 3.5 로 내려 재시도
둘 다 실패  ADR 0003 을 다시 연다 (자체 매크로 · java -cp 방식 · Gradle 경로)
```

### 결과 — 1차 통과 (2026-08-05)

**Spring Boot 4.1 + rules_spring 2.6.3 조합이 동작한다.** 후퇴하지 않았다.

```
/actuator/health            {"groups":["liveness","readiness"],"status":"UP"}
/actuator/health/liveness   {"status":"UP"}
/actuator/health/readiness  {"status":"UP"}
기동 0.78 초 · BOOT-INF/lib 에 의존성 49 개
```

다만 **기본값 그대로는 안 됐다.** 두 가지를 손봐야 했고, 다음 서비스도 같은 것이
필요하다.

| 무엇 | 왜 |
| --- | --- |
| `boot_launcher_class = "org.springframework.boot.loader.launch.JarLauncher"` | rules_spring 의 기본값은 Boot 2 시절 경로(`…loader.JarLauncher`)다. Boot 3 에서 런처가 다시 쓰이며 패키지가 `…loader.launch` 로 바뀌었다. 기본값이면 MANIFEST 의 Main-Class 가 없는 클래스를 가리켜 jar 가 실행되지 않는다 |
| `deps = ["@maven//:org_springframework_boot_spring_boot_loader"]` | `springboot_pkg.sh` 가 이름에 `spring-boot-loader` 가 든 jar 를 찾아 **jar 루트**에 푼다. 부트스트랩 클래스는 중첩 jar 밖에 있어야 JVM 이 찾는다. 이 아티팩트를 안 넣으면 런처 클래스 자체가 jar 에 없다 |

**빌드 환경도 두 곳 바꿔야 했다.**

- `.bazelversion` 8.0.0 → **8.7.0**. `rules_java` 9.7.0 이 Bazel 8.0.0 에 없는 API
  (`use_header_compilation_direct_deps`)를 써서 분석 단계에서 죽었다.
- `.bazelrc` 에 Java 21 고정. 안 하면 Bazel 이 `-source 11 -target 11` 로 컴파일해
  Spring Boot 4(Java 17+) 클래스를 읽지 못한다.

또 하나 — **`spring-boot-starter-*` 는 POM 만 있는 묶음이라 클래스가 없다.** 컴파일
의존성에는 `spring-boot` · `spring-boot-autoconfigure` 처럼 클래스를 담은 아티팩트를
넣고, 스타터는 `runtime_deps` 로 보낸다.

## 8. 작업 순서

`AGENTS.md` §6 을 따른다.

```
[완료] UNDERSTAND  티켓·k8s 흐름·포트 매핑·BCR 버전 확인
[완료] PLAN        ADR 0003 + 이 문서
[해당없음] CONTRACT  통신 형식을 바꾸지 않는다 (명세는 MZ2AZ-165 에서 이미 확정)
[진행] TEST FIRST  실패하는 테스트를 먼저 — 컨텍스트가 뜨는가
       IMPLEMENT   MODULE.bazel → BUILD.bazel → 소스 → Dockerfile → 매니페스트
       VERIFY      §6 의 8 단계
       DOCUMENT    모듈 README · 이 문서에 결과 기록
       COMMIT      티켓 키로 커밋
```

테스트는 Spring 컨텍스트가 실제로 뜨는지 보는 것부터 시작한다. 컨텍스트 로딩 테스트는
자동 구성이 조용히 반만 동작하는 상황(ADR 0003 의 배경)을 잡아 주므로, 이번 빌드 경로
선택에서 특히 값어치가 있다. 네트워크·컨테이너가 필요 없으므로 `unit` 태그를 붙여 빠른
레인에 둔다.
