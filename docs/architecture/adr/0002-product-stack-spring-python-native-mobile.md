---
number: 0002
title: 제품 스택을 Spring · Python · iOS/Android 네이티브로 확정한다
status: accepted
date: 2026-08-04
supersedes:
superseded-by:
---

# ADR 0002: 제품 스택을 Spring · Python · iOS/Android 네이티브로 확정한다

## 배경

[ADR 0001](./0001-bazel-as-the-single-build-system-and-just-as-the-single-command-surface.md)
은 Bazel 과 `just` 를 채택하면서 그 배경에 "Go, TypeScript, Python 이 공존한다"고
적었다. 그 문장은 저장소 골격을 세울 당시의 **가정**이었을 뿐, 실제로 그 언어들을
쓰기로 결정한 적은 없다. 골격이 만들어진 커밋(`8987ecf`)은 Go/TypeScript/Python 전제의
디렉터리·스크립트와, Spring Boot 를 전제한 인프라 문서(`/actuator/health` 경로 등)를
한꺼번에 담고 있어 처음부터 서로 어긋나 있었다.

실제로 만들 제품은 모바일 앱과 그것을 받치는 백엔드, 그리고 AI 기능이다. 작업을
시작하려면 언어를 확정해야 한다 — 이 결정 없이는 `MODULE.bazel` 에 어떤 규칙을 넣을지,
`just new-*` 의 기본값이 무엇인지, `just fmt`/`just lint` 가 어떤 도구를 부를지가
전부 미정으로 남는다.

## 결정

**백엔드는 Java(Spring Boot), AI 는 Python, 모바일은 iOS 와 Android 를 각각
네이티브(Swift · Kotlin)로 만든다.**

- Go 와 TypeScript 는 쓰지 않는다. 관련 디렉터리(`libs/go/`, `libs/ts/`)와 도구
  연결(gofmt, golangci-lint, prettier)을 제거한다.
- 크로스 플랫폼 프레임워크(Flutter · React Native)를 쓰지 않는다. iOS 앱과 Android 앱은
  코드를 공유하지 않는 별개의 모듈이며, 두 앱이 공유하는 것은 `contracts/` 의 정의다.
- ADR 0001 의 두 법칙(Bazel 이 빌드, `just` 가 명령 창구)은 그대로 유지한다. 이 ADR 은
  0001 을 대체하지 않고 그 배경에 적힌 언어 가정만 갱신한다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| Flutter 로 iOS·Android 를 한 번에 | 유지보수되는 Bazel 규칙 세트가 없어 ADR 0001 의 제1법칙과 정면 충돌한다. Bazel 을 유지하면 Flutter 를 못 짓고, Flutter 를 택하면 빌드 시스템 결정을 뒤집어야 한다. |
| React Native | 위와 같은 충돌에 더해, TypeScript 를 스택에 다시 들여와야 한다. 네이티브 성능·플랫폼 API 접근이 필요한 기능에서 결국 네이티브 코드를 쓰게 된다. |
| 백엔드를 Go 로 | 팀이 실제로 쓰는 언어가 아니다. 골격에 Go 가 들어 있던 것은 결정이 아니라 템플릿의 잔재였다. |
| 백엔드를 Python 하나로 통일 | AI 쪽과 언어는 같아지지만, 트랜잭션·스키마·운영 도구가 필요한 도메인 서비스에는 Spring 생태계의 이점이 크다. AI 는 Python, 도메인은 Java 로 나눈다. |

## 결과

**좋아지는 것**

- 네 언어 모두 성숙한 Bazel 규칙이 존재한다(`rules_java`·`rules_jvm_external`,
  `rules_python`, `rules_swift`·`rules_apple`, `rules_android`·`rules_kotlin`).
  ADR 0001 을 뒤집지 않고 스택을 확정할 수 있다.
- 각 플랫폼의 최신 API·성능·심사 대응을 프레임워크의 중개 없이 그대로 쓴다.
- 인프라 문서가 이미 전제하던 Spring Boot 와 코드가 처음으로 일치한다.

**나빠지는 것 / 감수하는 비용**

- **모바일 기능을 두 번 만든다.** iOS 와 Android 가 어긋나지 않도록 하는 책임이
  `contracts/` 와 `tests/contract/` 로 넘어간다.
- **iOS 빌드는 정식 Xcode 가 설치된 macOS 에서만 된다.** 이는 ADR 0001 이 내세운
  헤르메틱 원칙("어떤 컴퓨터에서든 동일한 빌드")의 명백한 예외다. CI 도 macOS 러너가
  필요해진다.
- 툴체인이 넷으로 늘어 워크스테이션 세팅이 무거워진다.
- **Bazel 이 관리할 수 있는 범위 밖의 도구가 늘어난다** — Xcode 프로젝트 설정,
  Android SDK 버전 등은 Bazel 밖에서 관리된다.

**후속 작업**

- `MODULE.bazel` 에 Java·Python·Android·Kotlin 규칙 선언 — 완료. Apple 규칙 3종은
  **의도적으로 주석 상태**로 둔다(아래 검증 참고).
- Gazelle 과 `rules_go` 제거 — 완료. Gazelle 은 Go 용 BUILD 생성기이고 Java·Kotlin·
  Swift 를 지원하지 않으므로, 이 저장소의 BUILD 파일은 손으로 쓴다.
- `libs/` 를 `java`·`python`·`swift`·`kotlin`·`proto` 로 재편 — 완료.
- 첫 Spring 서비스와 함께 Java 정적 분석(ErrorProne 또는 Checkstyle) 연결.
- CI 에 macOS 러너 잡 추가 — 첫 iOS 모듈이 생길 때.

## 검증

이 결정이 작동하는지는 세 가지로 본다.

1. **`just check` 가 계속 초록인가.** 스택을 반영한 뒤에도 게이트가 통과해야 한다.
2. **두 모바일 앱이 실제로 어긋나지 않는가.** iOS 와 Android 의 동작 차이가 계약
   테스트에서 잡히는지, 아니면 사용자 제보로 발견되는지를 본다. 후자가 반복되면
   계약의 표현력이 부족한 것이므로 `contracts/` 를 손봐야 한다.
3. **모바일 기능 하나를 두 번 만드는 비용이 감당 가능한가.** 이 비용이 네이티브가
   주는 이점을 넘어서면 크로스 플랫폼을 다시 논의한다 — 그때는 빌드 시스템 결정
   (ADR 0001)까지 함께 열어야 한다.

Apple 규칙에 대한 실측 기록: `apple_support` 를 `bazel_dep` 으로 선언하기만 해도,
정식 Xcode 없이 Command Line Tools 만 있는 macOS 에서는 iOS 타깃이 하나도 없는데도
`bazel build //...` 전체가 크래시한다(`DottedVersion` 파싱 실패). 그래서 Apple 규칙
3종은 첫 iOS 모듈을 만들고 빌드 참여 장비에 Xcode 설치를 확인한 뒤에 활성화한다.
