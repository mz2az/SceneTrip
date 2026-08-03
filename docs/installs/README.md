# 설치 가이드

로컬 개발 환경을 단계별로 설치한다. SigNoz 는 Kubernetes 설치가 끝났다고 전제하므로
순서대로 읽는다.

| 가이드 | 다루는 내용 |
| --- | --- |
| [k8s_install.md](k8s_install.md) | Homebrew, Git, Docker Desktop, **kind**, kubectl, Helm, k9s, AWS CLI, 클러스터 동작 테스트, 문제 해결 |
| [signoz_install.md](signoz_install.md) | foundryctl 로 SigNoz 설치, UI 접속, 헬스체크, OpenTelemetry 로 앱 연결, 로그 검색 |

## 로컬 환경의 모습

```
Docker Desktop
└── kind 클러스터 "scenetrip"        컨텍스트: kind-scenetrip
    ├── 네임스페이스 scenetrip        SceneTrip 서비스 / 앱 / 에이전트
    └── 네임스페이스 signoz           SigNoz (ClickHouse, Zookeeper, Postgres, 수집기)

호스트 8080 → NodePort 30080 → SigNoz UI
호스트 8081 → NodePort 30081 → 애플리케이션 API
```

호스트 포트는 클러스터를 만드는 시점에 `platform/kind/cluster.yaml` 이 고정하므로,
UI 와 API 모두 `port-forward` 가 필요 없다.

> **Kubernetes 는 kind 하나뿐입니다.** Docker Desktop 은 kind 노드를 컨테이너로 띄우는
> 런타임으로만 쓰고, 내장 Kubernetes 기능은 켜지 않습니다.

> **한 장비에서 kind 클러스터는 하나만.** 8080·8081 을 노드 컨테이너가 점유합니다.
> 다른 프로젝트의 클러스터가 떠 있으면 먼저 내리고 `just cluster-up` 을 실행하세요.

## 명령

가이드는 무슨 일이 일어나는지 이해하도록 밑단의 `kubectl`·`helm` 명령을 보여준다.
평소에는 레시피를 쓴다.

```bash
just cluster-up          # 클러스터 생성 + SigNoz 설치 (멱등)
just cluster-doctor      # 도구·클러스터·SigNoz·워크로드를 한 화면에
just cluster-test-drive  # 클러스터가 실제로 도는지 확인
just signoz              # UI 주소와 필터 안내
just signoz-status       # helm 릴리스 + 파드 상태
just cluster-down        # 전부 삭제 (확인 절차 있음)
```

같은 내용을 실습으로 따라가는 자료는 [../education/](../education/README.md) —
`just slides` 로 연다.
