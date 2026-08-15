# 온보딩 — SceneTrip에서 개발하기

이 문서 하나로 이 저장소에서 작업을 시작하는 데 필요한 걸 다 알 수 있게 하는 게 목표다.
규칙의 정본은 [AGENTS.md](../../AGENTS.md)이고, 이 문서는 그걸 실제 순서로 풀어 쓴 것이다.
둘이 어긋나면 AGENTS.md가 이긴다.

## 1. 이 저장소를 한 문단으로

SceneTrip은 백엔드(Spring Boot), 모바일 앱(iOS/Android 네이티브), AI 에이전트(Python)를
한 모노레포에 담는다. 지배하는 법칙은 두 개뿐이다.

1. **Bazel이 빌드·테스트·실행·패키징을 전부 담당한다.** `./gradlew`, `xcodebuild`, `pytest`를
   직접 정본으로 쓰지 않는다.
2. **`just`가 유일한 명령 창구다.** 날것의 `bazel …`은 문서·CI·대화 어디에도 쓰지 않는다.

이 두 개를 어기게 생겼으면 일단 멈추고 그렇게 말한다 — 우회하지 않는다.

## 2. 처음 세팅

```bash
just setup      # 최초 1회, 멱등
just doctor     # 도구가 다 있고 버전이 맞는지 확인
just ide        # VS Code 에서 자바 코드 탐색이 되게 한다 (아래 설명)
just --list     # 전체 명령을 그룹별로 훑어보기
```

**`just ide` 를 왜 따로 실행하는가.** VS Code 의 자바 확장은 프로젝트를 `pom.xml` ·
`build.gradle` · `.classpath` 셋 중 하나로만 인식한다. 이 저장소는 Bazel 로만 빌드하므로
셋 다 없고, 확장은 `.java` 파일을 낱장으로 취급해 **정의로 가기(F12·Ctrl+클릭)가 아무 일도
하지 않는다.** `just ide` 가 소스 루트와 jar 목록을 `.vscode/settings.json` 에 써 주면 해결된다.

그 파일은 **커밋되지 않는다.** jar 경로에 Bazel `output_base` 가 들어가고 그 값은 사용자
이름과 워크스페이스 해시로 만들어져 기계마다 다르기 때문이다. 각자 한 번씩 실행한다.
의존성(`MODULE.bazel`)이나 API 명세를 고친 뒤에는 다시 실행해야 새 클래스가 잡힌다.

계약에서 생성되는 인터페이스(`PlacesApi` 등)도 이때 함께 연결된다 — 그 소스는 저장소에
없고 빌드할 때 `bazel-bin/` 아래에 생기므로, 빌드 없이는 확장이 찾을 수 없다.

**공통으로 필요한 것**: [bazelisk](https://github.com/bazelbuild/bazelisk)(`bazel`이라는
이름으로), [just](https://github.com/casey/just) 1.34+, git. 컴파일러·인터프리터·SDK는
JVM(Java)과 Python은 Bazel이 격리된 형태로 직접 받아오므로 미리 깔 필요가 없다.

**작업할 모듈에 따라 추가로 필요한 것**:

| 무엇을 만지든 | 추가로 필요 | 왜 |
| --- | --- | --- |
| `apps/<name>-ios/` (iOS) | **Xcode** (버전은 `tools/bazel/toolchains/`에 고정) | Apple 라이선스상 Bazel이 대신 받아올 수 없는 유일한 예외. 없으면 iOS 타깃은 아예 안 돈다 |
| `apps/<name>-android/` (Android) | **Android SDK** (Android Studio 불필요) + 라이선스 동의 + `.env` 의 `ANDROID_HOME` | 아래 설치 절차 참고. Xcode 와 달리 GUI 프로그램은 필요 없다 |
| 로컬 클러스터(`just cluster-up` 등) | Docker Desktop, kind, kubectl, Helm, k9s | [k8s 설치 가이드](../installs/k8s_install.md) 참고 |

### Android SDK 설치 (Android 를 만질 때만)

`just doctor` 가 빠진 것을 알려 준다. 처음이라면 순서대로 하면 된다 —
**앞의 것이 없으면 뒤로 못 간다.**

```bash
# 1) 다운로더 설치. 이것만으로는 앱을 짓지 못한다 — 내용물은 아래에서 받는다
brew install --cask android-commandlinetools

# 2) 라이선스 동의. 안 하면 다운로드가 거부된다
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools --licenses

# 3) 구성요소. 세 가지면 충분하다 (약 360MB)
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools \
  "platform-tools" "platforms;android-36" "build-tools;36.1.0"

# 4) 경로를 .env 에 적는다. just 레시피가 자동으로 읽는다
echo 'ANDROID_HOME=/opt/homebrew/share/android-commandlinetools' >> .env

# 5) 에뮬레이터. 3번의 구성요소로는 빌드만 되고 앱이 뜨지는 않는다 (약 1.5GB)
sdkmanager --sdk_root=/opt/homebrew/share/android-commandlinetools \
  "emulator" "system-images;android-36;google_apis;arm64-v8a"
```

5번을 건너뛰어도 `just build` 는 통과한다 — **APK 가 만들어지는 것과 그것이 뜨는 것은
다르다.** `just android-run` 이 실행할 명령을 알려주고 멈추므로, 지금 안 받아도 나중에
막히지는 않는다.

AVD(어떤 기기를 흉내 낼지 적어 둔 설정)는 손으로 만들지 않는다. `just android-run` 이
없으면 Pixel 7 로 만든다 — 사람마다 다른 기기를 고르면 「내 화면에선 잘리는데」 가
생긴다.

**Kotlin 포매터는 깔지 않는다.** ktlint 은 Bazel 이 `//:ktlint` 로 받아온다
(`MODULE.bazel`) — google-java-format·checkstyle·buildifier 와 같은 방식이다. 그래서
`just fmt` · `just lint` 이 아무것도 미리 깔지 않은 기계에서도 그대로 돈다.

**JDK 가 없으면 2번에서 막힌다.** `sdkmanager` 자체가 자바 프로그램이기 때문이다.
macOS 의 `/usr/bin/java` 는 "자바를 설치하세요" 안내만 띄우는 껍데기다. Bazel 이 이미
받아 둔 JDK 를 빌려 쓰면 따로 설치할 필요가 없다.

```bash
export JAVA_HOME=$(ls -d "$(bazel info output_base)"/external/*remotejdk21_macos_aarch64 | head -1)
export PATH="$JAVA_HOME/bin:$PATH"
```

Android Studio 로 이미 깔았다면 경로가 `~/Library/Android/sdk` 다. `.env` 에 그쪽을 적으면 된다.

## 3. 저장소 구조 — 코드가 어디로 가는지

새 파일을 만들기 전에 이 표로 위치를 정한다. 새 최상위 디렉터리는 만들지 않는다.

| 만드는 것 | 위치 |
| --- | --- |
| Spring Boot HTTP/gRPC 서버 | `services/<name>/` |
| iOS 또는 Android 네이티브 앱 | `apps/<name>/` |
| LLM 에이전트 런타임·도구·오케스트레이션 | `agents/<name>/` |
| 2개 이상 모듈이 쓰는 코드 | `libs/<lang>/<name>/` |
| proto/OpenAPI/JSON-Schema 정의 | `contracts/<kind>/` |
| Terraform·Helm·k8s 매니페스트 | `platform/<kind>/` |
| 모듈 하나짜리 단위 테스트 | 그 모듈 안, 코드 옆 |
| 2개 이상 모듈을 가로지르는 테스트 | `tests/<kind>/` |
| 여러 모듈이 쓰는 Bazel 매크로 | `tools/bazel/defs/` |
| 새 명령 | `tools/just/<area>.just`의 레시피 |
| 산문·스펙·다이어그램·결정 | `docs/<area>/` |

생성된 클라이언트/서버 스텁은 **어디에도 커밋하지 않는다** — 빌드 시점에 Bazel이 만든다.

## 4. 모듈 하나의 모양

`apps/`, `services/`, `agents/` 아래 모든 모듈은 같은 뼈대를 갖는다.

```
services/scene-api/
├── BUILD.bazel        # 필수 — 모든 타깃 선언
├── README.md          # 필수 — 목적, 포트, 의존성, 런북 링크
├── CLAUDE.md           # 선택 — 이 모듈에만 적용되는 규칙 (루트보다 우선)
├── src/
├── tests/
└── deploy/
```

`deploy/`의 내용은 종류마다 다르다.

- `services/<name>/deploy/` → k8s/helm 오버레이. 환경별 값은 `platform/`에 산다.
- `apps/<name>/deploy/` → 스토어 제출 설정(fastlane 레인, 서명/프로비저닝, 빌드 variant).
  **k8s 매니페스트가 아니다** — 모바일 앱은 클러스터가 아니라 App Store/Play Store로 간다.

규칙:

- 모듈은 다른 모듈의 소스 트리에 직접 손대지 않는다. 넘어가려면 `libs/`(컴파일 타임) 또는
  `contracts/`(런타임/wire)를 거친다.
- 모듈은 자기 `BUILD.bazel`을 소유한다. 파일을 추가하면 같은 커밋에서 `srcs`도 갱신한다.
- 모듈은 `just build //services/scene-api/...`처럼 **혼자서도 빌드**돼야 한다.

## 5. 스택 개요 — 모듈 종류별로 뭘로 짜는지

| 종류 | 언어/프레임워크 | 공유 라이브러리 위치 | Bazel 규칙 세트 |
| --- | --- | --- | --- |
| `services/` (백엔드) | Java + Spring Boot | `libs/java/` | `rules_java` + `rules_jvm_external` (+ Spring Boot 패키징용 규칙, 아직 미확정) |
| `apps/<name>-ios/` | Swift | `libs/swift/` | `rules_apple` + `rules_swift` + `rules_swift_package_manager` |
| `apps/<name>-android/` | Kotlin | `libs/kotlin/` | `rules_kotlin` + `rules_android` |
| `agents/` (AI) | Python | `libs/python/` | `rules_python` |
| `contracts/proto` | protobuf | `libs/proto/` | `protobuf` + `rules_proto` |

이 규칙 세트들은 **해당 언어의 첫 모듈이 실제로 들어올 때** `MODULE.bazel`에 주석 해제되어
추가된다 — Java 는 scene-api 와, Swift 는 scenetrip-ios 와 함께 활성화됐고 나머지는 아직
주석 상태다. 새 모듈을 만들기 전에 `MODULE.bazel`을 열어 필요한 블록이 이미 활성화돼
있는지 확인한다.

**알아둘 것 하나:**

- **BUILD 파일 자동 생성기는 아직 없다.** 모든 `BUILD.bazel` 은 **손으로 관리**한다 —
  소스 파일을 추가하면 그 자리에서 바로 `srcs` 에 넣는다.
  흔히 쓰는 Gazelle 은 Go·proto 만 내장이고 다른 언어는 확장을 따로 붙이는 구조다.
  Java·Swift·Python 은 쓸 만한 확장이 있지만, 모듈이 아직 하나도 없어서 붙일 이유가
  없다 — 첫 모듈이 생길 때 그 언어 것만 붙인다(후보는 `MODULE.bazel` 주석 참고).
  **Kotlin 만은 예외로, 당분간 방법이 없다**: 쓸 수 있는 플러그인이 실험 단계인 데다
  Android 규칙(`android_library` 등)을 만들어 주지 못한다.

## 6. 격리(hermeticity) 원칙과 딱 하나의 예외

- 컴파일러·인터프리터·SDK는 `MODULE.bazel`/`tools/bazel/toolchains/`에 선언된 것만 쓴다 —
  "내 컴퓨터에 깔려있는 버전"에 의존하지 않는다.
- 빌드 시점에 네트워크를 쓰지 않는다. 외부 의존성은 전부 lockfile에 핀 고정한다.
- 절대 경로(`/Users/...`, `$HOME`)를 BUILD 파일이나 스크립트에 넣지 않는다.
- 같은 입력이면 같은 산출물이 나와야 한다 — 타임스탬프·랜덤 없음.

**예외: Xcode.** Apple 라이선스 때문에 Bazel이 Xcode를 대신 받아올 수 없다. iOS를 빌드하는
모든 머신(로컬이든 CI든)에 `tools/bazel/toolchains/`에 고정한 버전의 Xcode가 실제로
설치돼 있어야 한다. 자연스러운 결과로 **iOS 빌드/테스트는 macOS 실행기에서만 돈다** —
Linux 원격 실행이 안 된다. Android SDK는 재배포가 허용돼서 상대적으로 더 격리 가능하다.

그 "macOS 에서만"을 코드에 적는 장치는 두 겹이다 — 첫 용례는
`apps/scenetrip-ios/BUILD.bazel`, 실측 근거는 그 파일 머리말과 계획서
(docs/project/plans/mobile-native-search-tab.md §5-3)에 있다.

1. **`tags = ["ios"]` + `.bazelrc` 의 `build:linux --build_tag_filters=-ios`** —
   리눅스 와일드카드 빌드에서 iOS 타깃을 실제로 걸러내는 장치. `target_compatible_with`
   만으로는 안 된다: `ios_application` 이 타깃 플랫폼을 iOS 로 전환한 뒤 제약이
   평가되므로 호스트 OS 를 구분하지 못하고, 리눅스에서는 툴체인 해석 실패로 죽는다.
2. **`target_compatible_with`(macos·ios 를 허용하는 select)** — "Apple 플랫폼에서만
   지어진다"는 의미 선언.

Android 타깃에는 둘 다 붙이지 않는다(리눅스에서도 지어지므로 붙이면 오히려 검사
범위에서 빠진다).

## 7. 작업 루프

모든 변경에 이 순서를 따른다. 싼 단계부터 먼저다.

```
1. LOCATE   §3 배치표로 위치를 정한다. 새 최상위 디렉터리를 만들지 않는다.
2. CONTRACT wire 포맷(API/이벤트/proto/도구 스키마)이 바뀌나?
            YES → contracts/ 먼저 고치고 `just gen`, 그다음 생성된 스텁에 맞춰 구현.
            NO  → 넘어간다.
3. TEST     맞는 레인(unit/integration/e2e)에 실패하는 테스트부터 쓴다.
            `just test <target>` — 의도한 이유로 실패하는지 확인.
4. BUILD    구현한다. 소스 파일을 추가하는 같은 편집에서 BUILD.bazel의 srcs도 갱신한다.
5. VERIFY   `just check`. 빨간 게이트를 성공으로 보고하지 않는다.
6. DOCUMENT 모듈 README와 영향받는 docs/ 페이지를 갱신한다. 아키텍처 결정이면 ADR을 쓴다.
```

**먼저 계획부터 쓸 때** (다음 중 하나라도 해당하면): 2개 이상 모듈에 걸침 / `contracts/`의
계약이 바뀜 / `MODULE.bazel`에 의존성 추가 / 100줄을 넘을 것으로 예상. 계획은
`docs/project/`(기능 계획) 또는 `docs/architecture/adr/`(지속되는 결정)에 문서로 남긴다.

## 8. 명령어 치트시트

| 하고 싶은 것 | 명령 |
| --- | --- |
| 전체 빌드 | `just build` |
| 모듈 하나 빌드 | `just build-module services/scene-api` |
| 빠른 테스트 | `just test` |
| 타깃 하나 테스트 | `just test-module services/scene-api` |
| 통합 테스트 | `just test-integration` |
| e2e 테스트 | `just test-e2e` |
| 계약 테스트 (contracts/ 합의 검증) | `just test-contract` |
| 바이너리 실행 | `just run //services/scene-api:bin -- --port=8080` |
| 전부 포맷 | `just fmt` |
| 전부 린트 | `just lint` |
| proto/클라이언트/mock 재생성 (BUILD 파일 아님) | `just gen` |
| VS Code 코드 탐색 복구 (F12·Ctrl+클릭) | `just ide` |
| API 명세를 브라우저로 훑어보기 | `just docs-api` |
| **PR 전 게이트 — 커밋 전 필수** | `just check` |
| CI 재현 | `just ci` |
| 새 백엔드 서비스 | `just new-service <name>` |
| 새 iOS 앱 | `just new-app-ios <name>` |
| 새 Android 앱 | `just new-app-android <name>` |
| 새 AI 에이전트 | `just new-agent <name>` |
| 새 공유 라이브러리 | `just new-lib <lang> <name>` |
| 새 계약 정의 | `just new-contract proto scene/v1` |
| 바꿨을 때 영향받는 범위 확인 | `just rdeps <target>` |

명령이 없으면 즉흥 실행하지 말고 `tools/just/<area>.just`에 레시피를 추가한다 —
레시피를 추가하는 것 자체가 작업의 일부다.

## 9. 테스트 레인과 태그

| 태그 | 대상 | 어디서 돎 |
| --- | --- | --- |
| `unit` | 빠르고 격리, 네트워크·외부 서비스 없음 | `just test` |
| `integration` | 컨테이너/픽스처 필요 | `just test-integration` |
| `e2e` | 전체 스택 | `just test-e2e` |
| `slow` | 30초 초과 | 빠른 레인 제외 |
| `manual` | 배포·푸시·파괴적 작업 | `//...` 와일드카드에서 절대 안 돎 |
| `requires-network` | 비격리 | 샌드박스/원격 실행 제외 |

태그를 잘못 달면(예: DB가 필요한 테스트를 `:unit_test`에 넣기) 눈에 안 보이는 버그가 아니라
리뷰에서 잡히는 결함이 된다 — 정확히 달아야 한다.

## 10. 로컬 인프라 (kind + SigNoz)

```bash
just stack-up            # 클러스터 + DB + 스키마 + 데이터 + API 까지 한 번에
just cluster-up          # 인프라만 — kind 클러스터 + SigNoz 설치. 멱등, 3~4분
just cluster-doctor       # 도구·클러스터·SigNoz·워크로드 상태 한눈에
just cluster-test-drive  # 클러스터가 실제로 도는지 end-to-end 확인
just signoz               # SigNoz UI 주소 안내
just cluster-down         # 전부 삭제 (확인 절차 있음)
```

**API 에 요청을 보내 볼 거라면 `just stack-up` 을 쓴다.** `just cluster-up` 은 그릇만
만든다 — 클러스터가 서고 배포도 성공하고 health 도 초록인데 `/v1/contents` 는 빈 배열을
주는 상태가 된다. 데이터 적재(`just seed`)와 검색 색인 갱신(`just db-refresh-search`)이
별도 단계이기 때문이고, `stack-up` 이 그 순서를 묶은 뒤 실제 요청으로 건수까지 확인한다.
모바일 앱을 서버에 붙여 볼 때도 이것부터다.

`localhost:8080`이 SigNoz UI, `localhost:8081`이 애플리케이션 API다 — `platform/kind/cluster.yaml`이
호스트 포트를 클러스터 생성 시점에 매핑해 둬서 `port-forward`가 따로 필요 없다. kind 클러스터는
한 머신에 하나만 — 두 개 띄우면 8080/8081 포트가 충돌한다.

앱을 클러스터 밖(로컬 `bootRun`/시뮬레이터)에서 돌리며 텔레메트리를 보고 싶으면
[SigNoz 설치 가이드 §5](../installs/signoz_install.md#5-애플리케이션-연결-opentelemetry)의
OTLP 연결 예시(JVM/Python)를 따른다.

## 11. 문서와 ADR

- 한국어가 기본 언어다. **`AGENTS.md`와 `CLAUDE.md`만 예외로 영문 유지** — AI 도구가 직접
  읽는 지침이라서다.
- 문서는 정확히 한 곳에 산다 — 표는 [AGENTS.md §8](../../AGENTS.md#8-documentation-layout)
  참고.
- 모든 디렉터리에 실제 내용을 반영하는 `README.md` 인덱스가 있어야 한다.
- ADR은 **append-only**다. 결정을 바꾸려면 새 ADR을 써서 이전 것을 supersede하고, 이전
  ADR의 status를 갱신한다. 있는 걸 고쳐 쓰지 않는다. (`just adr-new "제목"`)
- 다이어그램은 diff 가능하도록 Markdown 안 Mermaid로 그린다.

## 12. Git 브랜치·커밋·PR (GitHub Flow + JIRA)

- **`main`은 항상 배포 가능한 상태.** `main`에 직접 커밋하지 않는다.
- 작업은 전부 브랜치를 파서 하고, `main`으로는 **PR로만** 머지한다.
- `main`에 머지되면 배포가 자동으로 일어난다 *(목표 정책 — 실제 자동배포 파이프라인은
  아직 구현 전. 파이프라인이 생기기 전이라도 "머지 = 배포"라고 생각하고 절대 어중간한
  상태로 머지하지 않는다)*.

**브랜치 이름** = JIRA 티켓 키 (+선택적으로 짧은 설명):

```
MZ2AZ-91-브랜드-네이밍
```

**커밋 메시지** — JIRA 티켓 키를 반드시 포함한다 (JIRA 연동이 이 키로 커밋을 티켓에
자동으로 연결하므로, 대소문자·하이픈을 정확히 맞춘다):

```
<type>: <명령형 요약> (<JIRA-KEY>)
```

- type: `feat` `fix` `docs` `refactor` `test` `chore` — 인프라/CI/설정 변경(Terraform,
  k8s 매니페스트, `.env`, 워크플로 파일)도 `chore`로 커밋한다.
- 커밋 하나에 논리적 변경 하나. 리팩터링과 동작 변경을 섞지 않는다.

예:

```
feat: 지도 SDK 적용 (MZ2AZ-10)
```

**PR 제목**:

```
[<JIRA-KEY>] <type>: <명령형 요약>
```

예: `[MZ2AZ-10] feat: 지도 SDK 적용`

PR 설명에는 무엇이 바뀌었는지, 왜, 영향 범위, 어떻게 검증했는지, 롤백 방법을 쓴다.

## 13. 품질 기준

| 항목 | 기준 |
| --- | --- |
| 테스트 커버리지 | 새/변경 코드 80% 이상, 크리티컬 경로는 end-to-end까지 |
| 파일 크기 | 200~400줄이 보통, 800줄이 하드 리밋 |
| 함수 크기 | 50줄 미만 |
| 중첩 | 4단계 이하 — early return 우선 |
| 에러 처리 | 모든 레벨에서 명시적, 조용히 삼키지 않음 |
| 매직 값 | 이름 붙은 상수만 |

## 14. 절대 하지 말 것

- `./gradlew build` / `xcodebuild` / `pytest`를 정본 빌드·테스트 단계로 쓰기.
- `just` 레시피 없이 CI나 문서에 명령 추가하기.
- 소스 파일을 추가하면서 `BUILD.bazel`을 갱신하지 않기.
- 생성된 코드나 생성된 BUILD 섹션을 손으로 고치기.
- `libs/`나 `contracts/`를 거치지 않고 모듈 경계를 가로질러 import하기.
- wire 포맷이 바뀌는데 계약보다 구현을 먼저 쓰기.
- `just check`가 빨간 채로 작업 완료라고 보고하기.
- 시크릿·토큰·키·실제 인증정보를 커밋하기 (테스트·문서 포함).
- `bazel-*` 심링크, 빌드 산출물, `.env` 파일 커밋하기.
- 게이트를 초록으로 만들려고 실패하는 테스트를 약화·삭제하기.
- 호출 지점 하나 고치겠다고 저장소 전체의 lint 규칙을 끄기.

다음으로 파괴적인 작업(`terraform apply`, 이미지 푸시, 공유 환경 DB 마이그레이션, force
push, 브랜치 삭제)은 반드시 확인 절차를 거친다 — 다른 작업의 일부로 암묵적으로 실행하지
않는다.

## 15. 더 읽을 것

- [AGENTS.md](../../AGENTS.md) — 이 모든 규칙의 정본
- [CLAUDE.md](../../CLAUDE.md) — AI 에이전트가 이 저장소에서 일하는 절차
- [contracts/README.md](../../contracts/README.md) — 계약 우선 워크플로 상세
- [docs/installs/](../installs/README.md) — 로컬 환경(k8s/SigNoz) 설치
- [docs/architecture/adr/](../architecture/adr/) — 지금까지의 아키텍처 결정
- `docs/engineering/bazel-guide.md` · `just-guide.md` · `git-workflow.md` ·
  `coding-standards.md` · `troubleshooting.md` — 아직 작성 전. 필요해지는 대로 채운다.
