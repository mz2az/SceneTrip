# libs/ — 공유 라이브러리

둘 이상의 모듈이 import 하는 코드. Bazel 툴체인 설정이 단순해지도록 언어별로 묶는다.

| 디렉터리 | 내용 |
| --- | --- |
| `libs/java/` | 여러 Spring Boot 서비스가 공유하는 Java 패키지 |
| `libs/kotlin/` | 여러 Android 앱이 공유하는 Kotlin 패키지 |
| `libs/swift/` | 여러 iOS 앱이 공유하는 Swift 패키지 |
| `libs/python/` | AI 에이전트가 공유하는 Python 패키지 |
| `libs/proto/` | `contracts/proto` 로부터 만든 공용 Bazel proto 라이브러리 |

## 규칙

- **복제가 아니라 승격.** 두 번째 모듈이 어떤 유틸리티를 필요로 하는 순간 이곳으로
  옮긴다 — 복사하는 것은 결함이다.
- 라이브러리는 특정 서비스·앱·에이전트를 알지 못한다.
- 라이브러리는 `services/`·`apps/`·`agents/` 를 거꾸로 참조하지 않는다.
- **iOS 와 Android 는 코드를 공유하지 않는다.** 두 앱은 각자 네이티브로 구현하므로
  `libs/swift/` 와 `libs/kotlin/` 은 서로 독립이다. 두 앱이 같은 규칙을 지켜야 한다면
  코드가 아니라 `contracts/` 의 정의를 공유한다.
- 모든 라이브러리에 테스트가 있다. 테스트 없는 라이브러리는 import 대상이 아니다.

```bash
just new-lib java   <이름>
just new-lib kotlin <이름>
just new-lib swift  <이름>
just new-lib python <이름>
```
