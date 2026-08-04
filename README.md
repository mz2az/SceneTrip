# SceneTrip

SceneTrip 제품의 전체 수명주기를 담는 모노레포입니다 — 기획, 설계, 구현, 테스트,
인프라, CI/CD, 운영까지. 여러 개의 백엔드 서비스와 프론트엔드 앱, AI 에이전트 모듈이
한 저장소 안에 나란히 존재합니다.

## 전체를 규정하는 두 가지 규칙

1. **빌드와 테스트는 Bazel이 전부 담당합니다.** 언어별 빌드 명령(`go build`, `npm run build`,
   `pytest`)을 정본으로 삼지 않습니다.
2. **모든 명령의 입구는 `just` 하나입니다.** 모든 명령은 레시피이며, 문서·스크립트·CI 어디에도
   날것의 `bazel` 호출을 적지 않습니다.

그 결과 **로컬과 CI가 완전히 같은 명령을 실행합니다.** 로컬에서 `just ci`가 통과하면
파이프라인도 통과합니다.

## 여기서 시작하세요

```bash
just setup      # 워크스테이션 최초 1회 세팅
just --list     # 사용 가능한 모든 명령을 그룹별로 확인
just build      # 워크스페이스 빌드
just test       # 빠른 단위 테스트 레인
just check      # PR 전 게이트 — 커밋 전에 반드시 초록이어야 합니다
```

## 무엇이 어디에 있는가

| 디렉터리 | 내용 |
| --- | --- |
| `services/` | 백엔드 서버 (배포 단위 하나당 디렉터리 하나) |
| `apps/` | 프론트엔드 애플리케이션 |
| `agents/` | AI 에이전트 모듈 |
| `libs/` | 언어별 공유 라이브러리 |
| `contracts/` | proto / OpenAPI / AsyncAPI / JSON Schema — 인터페이스의 정본 |
| `platform/` | Terraform, Kubernetes, Helm, 환경별 설정 |
| `tests/` | 모듈을 가로지르는 e2e·통합·계약·부하 테스트 |
| `tools/` | Bazel 매크로, justfile 모듈, 스크립트, 템플릿 |
| `docs/` | 제품·아키텍처·엔지니어링·QA·운영·AI 문서 전체 |

전체 배치 규칙: [AGENTS.md §2](./AGENTS.md#2-repository-map)

## 설치 가이드

새 장비에서는 여기서 시작하세요. SigNoz 가이드는 Kubernetes 설치가 끝났다고 전제하므로
**순서대로** 읽으세요.

| 가이드 | 다루는 내용 |
| --- | --- |
| **[docs/installs/k8s_install.md](./docs/installs/k8s_install.md)** | Homebrew, Git, Docker Desktop, **kind**, kubectl, Helm, k9s, AWS CLI, 클러스터 동작 테스트, 문제 해결, 완료 체크리스트 |
| **[docs/installs/signoz_install.md](./docs/installs/signoz_install.md)** | foundryctl로 SigNoz 설치, UI 접속, 헬스체크, OpenTelemetry로 앱 연결, 로그 검색 |
| [docs/installs/](./docs/installs/README.md) | 인덱스와 로컬 환경 구성도 |

같은 내용을 실습과 함께 따라가는 강의자료 — 35슬라이드, 오프라인 단일 파일, 약 3시간:

| 자료 | 여는 방법 |
| --- | --- |
| **[docs/education/k8s-observability-class.html](./docs/education/k8s-observability-class.html)** | `just slides` |
| [docs/education/](./docs/education/README.md) | 강의가 다루는 범위와 자료를 최신으로 유지하는 방법 |

## 로컬 환경

```bash
just cluster-up          # kind 클러스터 생성 + SigNoz 설치 (멱등, 3~4분)
just cluster-doctor      # 도구·클러스터·SigNoz·워크로드 상태를 한 화면에
just cluster-test-drive  # 클러스터가 실제로 도는지 end-to-end 확인
just signoz              # SigNoz UI 주소와 필터 안내
just cluster-down        # 전부 삭제 (확인 절차 있음)
```

| 주소 | 제공하는 것 |
| --- | --- |
| `http://localhost:8080` | SigNoz UI |
| `http://localhost:8081` | 애플리케이션 API |

`port-forward`가 필요 없습니다 — 클러스터 생성 시점에
[`platform/kind/cluster.yaml`](./platform/kind/README.md)이 호스트 포트를 매핑하기 때문입니다.

> 이 프로젝트의 Kubernetes는 **kind 하나뿐**입니다. Docker Desktop은 kind 노드를 컨테이너로
> 띄우는 **런타임으로만** 쓰고, 내장 Kubernetes 기능은 켜지 않습니다.

> 한 장비에서 kind 클러스터는 **하나만** 띄우세요. 8080·8081 포트를 노드 컨테이너가 점유합니다.

## 문서

| 읽을 것 | 용도 |
| --- | --- |
| [AGENTS.md](./AGENTS.md) | 저장소 계약 — 구조, Bazel, just, 품질 기준 (영문) |
| [CLAUDE.md](./CLAUDE.md) | AI 에이전트가 이 저장소에서 일하는 절차 (영문) |
| [docs/](./docs/README.md) | 전체 문서 인덱스 |
| [docs/engineering/](./docs/engineering/README.md) | 온보딩, Bazel 가이드, just 가이드, 컨벤션 |
| [docs/installs/](./docs/installs/README.md) | 로컬 환경 설치 |
| [docs/education/](./docs/education/README.md) | 강의 자료 |
| [platform/](./platform/README.md) | 코드로 관리하는 인프라 |

> `AGENTS.md`와 `CLAUDE.md`는 AI 도구가 직접 읽어 따르는 지침이라 **영문으로 유지**합니다.

## 필요한 도구

빌드와 테스트에 필요한 것:

| 도구 | 용도 |
| --- | --- |
| [bazelisk](https://github.com/bazelbuild/bazelisk) (`bazel` 이름으로 설치) | `.bazelversion`에 고정된 Bazel 버전을 자동으로 맞춤 |
| [just](https://github.com/casey/just) 1.34 이상 | 명령 실행기 |
| git | 버전 관리 |
| **Xcode** (iOS 앱을 빌드할 경우) | Apple 라이선스상 Bazel이 대신 받아올 수 없는 유일한 예외 — `apps/<name>-ios/` 를 빌드하려면 머신에 실제로 설치돼 있어야 합니다. 버전은 `tools/bazel/toolchains/`에 고정 |
| **Android Studio / Android SDK** (Android 앱을 빌드할 경우) | Bazel이 `rules_android`로 상당 부분 자동 설치하지만, 최초 라이선스 동의와 로컬 SDK 경로 설정은 사람이 한 번 해야 합니다 |

그 외 JVM(Java) 백엔드와 Python AI 에이전트의 컴파일러·인터프리터는 이 목록에 **없습니다** —
Bazel이 격리된 형태로 직접 제공합니다. `just doctor`로 확인하세요.

로컬 클러스터를 띄우려면 추가로 필요한 것
([설치 가이드](./docs/installs/k8s_install.md) 참고):

| 도구 | 용도 |
| --- | --- |
| Docker Desktop | kind 노드를 컨테이너로 실행 (컨테이너 런타임 전용) |
| kind | 로컬 Kubernetes 클러스터 |
| kubectl · Helm | 클러스터 제어와 패키지 설치 |
| k9s | 터미널에서 클러스터를 살펴보는 UI |

`just cluster-doctor`로 확인하세요.
