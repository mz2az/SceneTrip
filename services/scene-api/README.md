# scene-api

> 모듈 종류: `service` · 언어: `java` · 경로: `services/scene-api`

## 목적

SceneTrip 앱의 **검색·지도 탭**을 받치는 백엔드다. 작품·촬영지·인물을 검색하고, 지도
뷰포트 안의 촬영지를 조회하고, 방문할 장소를 장바구니에 담는 API 를 제공한다.

**현재는 껍데기다.** 기동과 상태 점검만 한다 — 실제 엔드포인트는
[MZ2AZ-149](https://mz2az.atlassian.net/browse/MZ2AZ-149), DB 연결은
[MZ2AZ-152](https://mz2az.atlassian.net/browse/MZ2AZ-152) 가 채운다.

## 인터페이스

| 항목 | 값 |
| --- | --- |
| 프로토콜 | HTTP |
| 컨테이너 포트 | `8080` |
| 클러스터 노출 | NodePort `30081` → 호스트 `8081` (`platform/kind/cluster.yaml` 이 매핑) |
| 계약 | [`contracts/openapi/scene-api-v1.yaml`](../../contracts/openapi/scene-api-v1.yaml) — **아직 구현하지 않음.** MZ2AZ-149 에서 생성된 인터페이스를 구현한다 |

### 상태 점검 경로

| 경로 | 용도 |
| --- | --- |
| `/actuator/health` | 전체 상태. `startupProbe` 가 본다 |
| `/actuator/health/liveness` | 프로세스가 살아 있는가. `livenessProbe` |
| `/actuator/health/readiness` | 트래픽을 받을 준비가 됐는가. `readinessProbe` |

`liveness`·`readiness` 그룹은 Spring 기본값이 꺼져 있어 `application.yaml` 에서 켰다.

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| Spring Boot 4.1 (`web`, `actuator`) | HTTP 서버와 상태 점검 |
| `@maven//:...spring_boot_loader` | 실행 가능 jar 의 부트스트랩. `springboot()` 의 `deps` 로 넘긴다 |

`libs/` 와 다른 서비스에는 아직 의존하지 않는다.

## 빌드 방식

**Bazel 이 실행 가능 jar 를 만들고 컨테이너는 그것을 담기만 한다**
([ADR 0003](../../docs/architecture/adr/0003-spring-boot-bazel.md)).
Gradle·Maven 을 빌드 경로로 쓰지 않으므로 이 모듈에 `build.gradle` 은 없다.
의존성은 루트 `MODULE.bazel` 한 곳에서 관리한다.

`Dockerfile` 은 빌드하지 않는다 — 최종 이미지에 JDK 가 들어가지 않는다.
빌드 컨텍스트는 `tools/scripts/image-build.sh` 가 임시 디렉터리에 만든다.

## 명령

```bash
just build-module services/scene-api    # 빌드
just test-module  services/scene-api    # 테스트
just run //services/scene-api:bin       # 로컬 실행 (기본 8080)

just image  scene-api                   # 이미지 굽고 kind 에 적재
just deploy scene-api local             # 클러스터에 배포
just update scene-api                   # 코드 변경 후 재빌드 → 적재 → 롤링 재시작
just logs   scene-api                   # 로그 따라가기
```

배포 후 확인 — `port-forward` 가 필요 없다.

```bash
curl http://localhost:8081/actuator/health
```

## 설정

기본값은 `src/main/resources/application.yaml`, 환경별 덮어쓰기는
`platform/kubernetes/scene-api/configmap.yaml`.

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 아니오 | 없음 | 활성 프로파일. 로컬 배포는 `local` |
| `JAVA_TOOL_OPTIONS` | 아니오 | 없음 | 힙 비율. 컨테이너 메모리 한도에 맞춘다 |

시크릿은 시크릿 매니저에서 온다. 커밋하는 것은 `.env.example` 뿐이다.

## 운영

컨테이너는 **비-root(UID 10001)** 로 돌고 루트 파일시스템이 읽기 전용이다.
`/tmp` 만 쓰기 가능한 임시 볼륨으로 열려 있다 — JVM 과 Tomcat 이 거기 쓴다.

종료는 `SIGTERM` → graceful shutdown 이다. `Dockerfile` 의 `ENTRYPOINT` 가 exec
형식이라 `java` 가 PID 1 이 되고 신호를 직접 받는다.

런북: `docs/ops/` · 대시보드: SigNoz (MZ2AZ-182 에서 연결) · 알림: 미정
