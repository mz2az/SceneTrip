---
number: 0003
title: Spring Boot 서비스를 Bazel 로 빌드하고 컨테이너에는 산출물만 담는다
status: accepted
date: 2026-08-05
supersedes:
superseded-by:
amended-by:
---

# ADR 0003: Spring Boot 서비스를 Bazel 로 빌드하고 컨테이너에는 산출물만 담는다

## 배경

첫 백엔드 서비스(MZ2AZ-181)를 만들면서 `MODULE.bazel` 이 미뤄 둔 결정을 내려야 한다.
그 파일에 이렇게 적혀 있다.

> Spring Boot 실행 가능 jar 패키징은 `rules_java` 만으로는 안 된다. 커뮤니티 규칙
> (예: salesforce/rules_spring) 또는 `tools/bazel/defs/` 의 자체 매크로가 필요하다 —
> **첫 서비스가 들어올 때 결정한다.**

미뤄 둔 이유는 실재한다. Bazel 의 `java_binary` 가 만드는 deploy jar 는 모든 의존성
클래스를 하나로 평탄화하는데, 이때 **같은 경로의 리소스는 첫 번째 것만 남기고 버린다.**
Spring 은 자동 구성 목록(`META-INF/spring/…AutoConfiguration.imports`)처럼 여러 jar 에
흩어져 있고 **합쳐져야** 하는 파일에 의존한다. 그대로 두면 앱이 뜨긴 하는데 자동 구성이
조용히 절반만 동작한다.

저장소에 갈래가 둘 있다는 점도 이 결정을 강제한다.

| 어디 | 무엇을 전제하나 |
| --- | --- |
| `AGENTS.md` §4.1 | `:image` = OCI 컨테이너 이미지 → Bazel 타깃 |
| `tools/scripts/image-build.sh` | `docker build` + `services/<모듈>/Dockerfile` |

그리고 그 위에 [ADR 0001](./0001-bazel-as-the-single-build-system-and-just-as-the-single-command-surface.md)
의 제1법칙이 있다 — **Bazel 이 전부 빌드한다. 언어별 빌드 도구를 정본으로 쓰지 않는다.**

실제 제약: 3 인 팀, 마감이 있는 프로젝트, Bazel + Spring 조합은 사용자가 적어 막혔을 때
검색으로 답을 찾기 어렵다.

## 결정

**Bazel 이 실행 가능 jar 를 만들고, 컨테이너 이미지는 그 산출물만 담는다.**

- `rules_java` · `rules_jvm_external` · `rules_spring` 을 `MODULE.bazel` 에 선언한다.
  Maven 의존성은 `maven.install` 한 곳에서 관리하고 잠금 파일을 커밋한다.
- `services/<모듈>/BUILD.bazel` 의 `springboot()` 타깃이 실행 가능 jar 를 만든다.
  `just build //services/...` 로 서비스가 실제로 지어진다.
- **`Dockerfile` 은 빌드하지 않는다.** Bazel 이 만든 jar 를 JRE 베이스 이미지에 담고
  비-root 계정으로 실행하는 것이 전부다. 빌드 도구가 최종 이미지에 들어가지 않는다.
- Gradle · Maven 을 빌드 경로로 쓰지 않는다. `build.gradle` 을 두지 않는다.

**컨테이너 이미지는 당분간 `Dockerfile` 경로를 유지한다.** `AGENTS.md` §4.1 이 예고한
Bazel `:image` 타깃(`rules_oci`)으로 옮기는 것은 후속 작업이다 — 로컬 kind 흐름
(`just image` 가 이미지를 굽고 `kind load` 까지 한다)이 docker 이미지를 전제하고 있어,
지금 함께 바꾸면 "첫 서비스가 뜬다" 를 증명하는 일과 빌드 방식을 바꾸는 일이 한 변경에
섞이고 실패 지점이 둘로 늘어난다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| 멀티스테이지 `Dockerfile` 안에서 Gradle 이 빌드 | 제1법칙과 §11 안티패턴("`./gradlew build` 를 권위 있는 빌드 단계로 쓰지 말 것")의 정면 위반. 더 실질적으로는 **서비스가 Bazel 그래프 밖에 놓인다** — `just check` 가 서비스 코드를 검사하지 않고, `just test` 가 그 테스트를 돌리지 않으며, `just rdeps` 가 계약 변경의 영향 범위를 계산하지 못한다. 계약 위반이 게이트를 통과해 `docker build` 단계에서야 드러난다. 의존성 선언도 `MODULE.bazel` 과 `build.gradle` 로 갈라져 §4.4 가 금지한 병렬 빌드 경로가 된다 |
| `rules_oci` 로 Bazel 이 이미지까지 | 방향은 맞고 결국 여기로 간다. 다만 `image-build.sh` 와 kind 적재 흐름을 함께 고쳐야 해서 이번 변경에 섞을 수 없다. 후속 작업으로 분리한다 |
| `tools/bazel/defs/` 에 자체 패키징 매크로 | `rules_spring` 이 Bazel Central Registry 에 있고 bzlmod 를 지원한다(2.6.3). 같은 문제를 이미 푼 규칙이 있는데 직접 만들어 유지보수를 떠안을 이유가 없다. 그 규칙이 방치되면 그때 만든다 |

## 결과

**좋아지는 것**

- 서비스가 Bazel 그래프 안에 들어온다. `just check` 가 서비스 코드를 컴파일하고,
  `just test` 가 그 테스트를 돌리고, `just coverage` 가 잴 대상을 갖는다.
- **계약 위반이 게이트에서 걸린다.** 컨트롤러가 `contracts/openapi` 로부터 생성된
  인터페이스를 구현하므로, 명세를 어기면 `just check` 가 빨간불이 된다. 배포하려다
  발견하는 것과 커밋 전에 걸리는 것의 차이다.
- 의존성이 `MODULE.bazel` 한 곳에 모인다. 버전이 두 파일로 갈라지지 않는다.
- 증분 빌드가 동작한다. 파일 하나를 고치면 그것만 다시 빌드된다.
- 최종 이미지에 JDK·Gradle 이 없다. JRE 와 jar 뿐이다.

**나빠지는 것 / 감수하는 비용**

- Maven 잠금 파일이 수천 줄이라 커밋 diff 가 지저분해진다.
- Bazel + Spring 사용자가 적어 **막혔을 때 검색으로 답을 찾기 어렵다.** 이것이 이
  결정의 가장 큰 실질적 위험이다.
- `rules_spring` 은 Salesforce 가 사내 필요로 만든 규칙이고 **마지막 릴리스가
  2025-02(2.6.3)** 다. Spring Boot 4.0 이 2025-11 에 나왔으므로 그 조합은 검증된 바가
  없다. 런처 클래스가 설정 가능한 속성이라 이름만 맞추면 될 가능성이 있지만 확실하지
  않다.
- Spring Boot DevTools 같은 일부 편의 기능이 덜 매끄럽다.
- IDE 연동이 Gradle 프로젝트만큼 자연스럽지 않다.

**후속 작업**

- `rules_oci` 로 `:image` · `:push` 타깃을 만들고 `image-build.sh` 를 그쪽으로 옮긴다.
- Java 정적 분석(ErrorProne 또는 Checkstyle) 연결 — ADR 0002 가 "첫 Spring Boot
  서비스와 함께" 로 예고한 항목이다.
- `just coverage` 를 게이트에 넣는다. 잴 대상이 생겼으므로 80% 기준을 강제할 수 있다.
- Java Gazelle 확장(`bazel-contrib/rules_jvm` 의 `java/gazelle`) 도입 검토 — 소스가
  늘면 `srcs` · `deps` 손 관리 부담이 커진다.

## 검증

**후퇴 기준을 미리 정한다.** `rules_spring` 으로 실행 가능 jar 를 만들고
`/actuator/health` 가 응답하는 데까지가 판정선이다. Spring Boot 4.1 로 먼저 시도하고,
`rules_spring` 이 패키징하지 못하면 3.5 로 내려 재시도한다. 어느 쪽으로 갔든 이유를
기록한다.

**둘 다 실패하면 이 ADR 을 다시 연다.** 그때 선택지는 자체 매크로, `java -cp` 방식
(fat jar 를 포기하고 클래스패스로 실행), 또는 위에서 기각한 Gradle 경로다. 마지막을
택한다면 제1법칙을 고치는 ADR 이 함께 필요하다.

이 결정이 옳았는지는 **서비스가 늘어난 뒤**에 본다. 두세 번째 서비스를 추가하는 비용이
첫 번째보다 확연히 싸다면 맞은 것이고, 매번 같은 고생을 반복한다면 규칙이 우리 구성과
맞지 않는다는 뜻이므로 자체 매크로로 옮긴다.
