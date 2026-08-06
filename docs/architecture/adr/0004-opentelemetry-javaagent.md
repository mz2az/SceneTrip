---
number: 0004
title: 관측성은 OpenTelemetry 자바 에이전트로 붙이고 에이전트는 Bazel 이 받는다
status: accepted
date: 2026-08-06
supersedes:
superseded-by:
amended-by:
---

# ADR 0004: 관측성은 OpenTelemetry 자바 에이전트로 붙이고 에이전트는 Bazel 이 받는다

## 배경

[MZ2AZ-182](https://mz2az.atlassian.net/browse/MZ2AZ-182) 가 scene-api 의 텔레메트리를
SigNoz 로 보내는 일을 요구한다. SigNoz 는 이미 로컬 클러스터에 떠 있고 OTLP 수집기가
`signoz-ingester:4317` 에 있다.

정할 것이 둘이다 — **무엇으로 계측하는가**, 그리고 **에이전트가 필요하다면 어떻게
이미지에 들어가는가**.

## 결정

**OpenTelemetry 자바 에이전트를 `-javaagent` 로 붙인다. 에이전트 jar 는 Bazel 이
`http_file` 로 받고, 이미지는 그것을 담기만 한다.**

## 근거

### 왜 SDK 가 아니라 에이전트인가

후보는 둘이었다.

| | 에이전트 | Micrometer + OTLP 내보내기 |
| --- | --- | --- |
| 코드 변경 | 없음 | 의존성 · 설정 · 로그 appender |
| 계측 범위 | Spring MVC · JDBC · HikariCP · Logback 자동 | HTTP 는 자동, JDBC·로그는 따로 붙여야 |
| 이미지 | jar 24MB 추가 | 없음 |
| 파이썬 에이전트(`agents/`) | 같은 방식(`opentelemetry-instrument`) | 해당 없음 |

**결정적인 것은 설치 가이드가 이미 에이전트를 전제하고 있다는 점이다.**
`docs/installs/signoz_install.md` 가 팀에게 로컬 실행 방법을 `-javaagent` 로 가르치고
있고, 교육 슬라이드도 같은 흐름이다. 서버만 다른 방식으로 계측하면 문서와 실제가
갈라지고, 문서를 고치는 비용이 얻는 것보다 크다.

부수적으로, Spring Boot 4 는 자동 구성을 기술별 모듈로 쪼개 두어 SDK 경로를 고르면
어떤 모듈이 어떤 계측을 들고 오는지 매번 확인해야 한다. Flyway 와 `@WebMvcTest` 에서
이미 두 번 겪었다 — **클래스패스에 있는데 아무 일도 일어나지 않는** 실패 방식이다.

### 왜 Dockerfile 에서 받지 않는가

설치 가이드는 `Dockerfile` 의 `OTEL_AGENT_VERSION` 을 버전의 정본으로 적어 두었다.
그대로 하면 `RUN curl` 이 되는데 둘이 걸린다.

- **이미지 빌드가 네트워크를 탄다.** 릴리스가 사라지거나 GitHub 이 느리면 빌드가
  실패한다. AGENTS.md §4.3 이 금지하는 것과 같은 성질이다.
- **받은 것이 우리가 기대한 파일인지 확인하지 않는다.** 실행 중인 JVM 에 코드를 주입하는
  jar 다 — 검증 없이 받을 물건이 아니다.

`MODULE.bazel` 의 `http_file` 은 `sha256` 이 어긋나면 빌드를 세운다. 버전도 그 한 곳에만
적힌다. [ADR 0003](0003-spring-boot-bazel.md) 의 *"Bazel 이 산출물을 만들고 도커는
담기만 한다"* 를 에이전트에도 그대로 적용한 것이다.

### 왜 ENTRYPOINT 이고 JAVA_TOOL_OPTIONS 가 아닌가

`JAVA_TOOL_OPTIONS` 는 ConfigMap 이 이미 힙 비율에 쓰고 있다. 거기에 `-javaagent` 를
더하면 한쪽을 고칠 때 다른 쪽이 지워진다. 에이전트는 **이미지의 성질**이지 환경의
성질이 아니므로 이미지가 들고 있는 편이 맞다.

## 결과

**좋아지는 것**

- 코드에 계측이 없다. `ApiExceptionHandler` 가 MDC 에서 `trace_id` 를 읽는 한 줄이
  전부이고, OpenTelemetry API 에 컴파일 의존이 생기지 않는다.
- 에이전트가 없는 환경(단위 테스트, 수집기 없는 로컬)에서 그 값은 `null` 이 되고 코드는
  그대로 돈다.
- 파이썬 에이전트가 생길 때 같은 모양으로 붙는다.

**감수하는 것**

- 이미지가 24MB 커진다.
- 기동이 1 초쯤 느려진다. 프로브의 `failureThreshold` 안에 들어간다(실측 2.6 초).
- 에이전트 버전을 올릴 때 `sha256` 을 함께 고쳐야 한다. 잊으면 빌드가 실패하므로
  조용히 어긋나지는 않는다.

**전제 조건 하나** — SigNoz 는 관리자 계정(=조직)이 있어야 수집기가 파이프라인 설정을
받고 OTLP 포트를 연다. 계정이 없으면 앱에는 `Connection refused` 로 보인다. 설치
가이드가 경고해 둔 것이고 실제로 그렇게 걸렸다.

## 대안

| 대안 | 왜 아닌가 |
| --- | --- |
| Micrometer Tracing + OTLP 내보내기 | 문서·교육 자료와 갈라진다. JDBC·로그 계측을 손으로 붙여야 한다 |
| Dockerfile 에서 `curl` | 이미지 빌드가 네트워크를 타고 체크섬 검증이 없다 |
| 에이전트 jar 를 저장소에 커밋 | 24MB 바이너리를 git 에 넣는다. 갱신할 때마다 저장소가 커진다 |
| 사이드카 수집기 | 파드마다 컨테이너가 하나 늘어난다. 클러스터에 수집기가 이미 있어 얻는 것이 없다 |
