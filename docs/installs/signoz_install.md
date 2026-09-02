# SigNoz 설치 및 로그 검색 가이드

이 문서는 SceneTrip 로컬 개발환경의 **Kubernetes 클러스터에 SigNoz**(오픈소스 관측 플랫폼)를 설치하고, 수집된 로그를 검색하는 방법을 설명합니다.

SigNoz는 OpenTelemetry 기반으로 **로그·트레이스·메트릭을 한 곳에서** 다루는 도구입니다. SceneTrip 의 여러 백엔드 서비스(`services/`)와 AI 에이전트(`agents/`)에서 나오는 로그를 서비스 단위로 묶어 보고, 트레이스와 연결해 원인을 추적하는 것이 목적입니다.

> **선행 조건**: [k8s_install.md](k8s_install.md)의 설치가 끝나 있어야 합니다 — kind 클러스터 생성, `kubectl`, `helm`.
>
> **팀 표준은 Kubernetes 설치입니다.** SigNoz를 Docker 단독으로 띄우는 방법도 존재하지만, 실제 배포 환경(EKS)과 구성이 달라져 로컬에서 검증한 내용이 그대로 이어지지 않습니다. Docker 단독 방식은 [부록](#부록-docker-단독-설치-비표준)에 참고용으로만 남깁니다.

---

## 목차

- [SigNoz 설치 및 로그 검색 가이드](#signoz-설치-및-로그-검색-가이드)
  - [목차](#목차)
  - [빠른 사용법](#빠른-사용법)
  - [0. 시작 전 확인](#0-시작-전-확인)
  - [1. Foundry 이해하기](#1-foundry-이해하기)
  - [2. Kubernetes 설치](#2-kubernetes-설치)
  - [3. 접속](#3-접속)
  - [4. 설치 확인](#4-설치-확인)
  - [5. 애플리케이션 연결 (OpenTelemetry)](#5-애플리케이션-연결-opentelemetry)
  - [6. 로그 검색하기](#6-로그-검색하기)
  - [7. 자주 쓰는 로그 검색 예시](#7-자주-쓰는-로그-검색-예시)
  - [8. 로그를 잘 남기는 규칙](#8-로그를-잘-남기는-규칙)
  - [9. 중지·제거](#9-중지제거)
  - [10. 문제 해결](#10-문제-해결)
  - [11. 공식 문서](#11-공식-문서)
  - [부록: Docker 단독 설치 (비표준)](#부록-docker-단독-설치-비표준)

---

## 빠른 사용법

명령 묶음만 모은 요약입니다. 처음이라면 [§0 시작 전 확인](#0-시작-전-확인)부터 읽으세요.

### ① 최초 1회 — 설치

**저장소의 스크립트가 이 과정을 대신합니다.** kind 클러스터 생성부터 SigNoz 설치, UI NodePort 적용까지 한 번에 하고, 이미 있으면 건너뜁니다(멱등).

```bash
just cluster-up      # 또는: just cluster-up
```

<details>
<summary>스크립트가 하는 일을 직접 실행하기</summary>

```bash
# foundryctl 설치 + PATH 등록
curl -fsSL https://signoz.io/foundry.sh | bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc && source ~/.zshrc

# 컨텍스트 확인 (필수 — EKS에 설치되는 사고 방지)
kubectl config use-context kind-scenetrip

# casting 작성 후 배포
mkdir -p ~/signoz && cd ~/signoz
cat > casting-k8s.yaml <<'YAML'
apiVersion: v1alpha1
kind: Installation
metadata:
  name: signoz
spec:
  deployment:
    flavor: helm
    mode: kubernetes
YAML
foundryctl cast -f casting-k8s.yaml -p ./pours-k8s --format text

# UI 를 고정 NodePort 로 노출
kubectl apply -f platform/kubernetes/signoz/ui-nodeport.yaml
```

산출물(`pours-k8s/`)은 저장소 밖(`~/signoz`)에 둡니다 — 저장소를 오염시키지 않기 위해서입니다.
</details>

약 3~4분 걸립니다. 자세한 설명과 주의사항은 [§2](#2-kubernetes-설치).

> **설치 직후 관리자 계정을 반드시 만드세요.** 계정(=조직)이 없으면 collector가 파이프라인 설정을 받지 못해 **OTLP 수신기 자체가 열리지 않습니다.** 앱 쪽에는 `Connection refused`로 보입니다.
> 비밀번호는 **12자 이상 + 대문자 + 소문자 + 숫자 + 기호**여야 합니다. 로컬 전용이니 실제로 쓰는 비밀번호를 재사용하지 마세요.

### ② 매일 — 켜고 보기

```bash
# 전날 scale 0으로 내려뒀다면 다시 깨우기
kubectl scale statefulset,deployment --all --replicas=1 -n signoz
```

브라우저에서 `http://localhost:8080` → **Logs → Logs Explorer**

> **UI는 `port-forward`가 필요 없습니다.** `platform/kubernetes/signoz/ui-nodeport.yaml`의 NodePort 30080이 kind의 `extraPortMappings`로 호스트 8080에 연결돼 있습니다.
> 오히려 같은 포트로 `port-forward`를 겹쳐 열면 리스너가 둘 생겨 어느 쪽이 응답할지 불확실해집니다. 열지 마세요.

맥에서 직접 돌리는 앱의 로그를 보내려면 **별도 터미널**에서 수집기도 열어야 합니다.

```bash
kubectl port-forward -n signoz svc/signoz-ingester 4317:4317 4318:4318
```

자세히는 [§3 접속](#3-접속) · [§5 애플리케이션 연결](#5-애플리케이션-연결-opentelemetry).

### ③ 로그 검색 — 자주 쓰는 것

```text
service.name = scenetrip-scene-api AND severity_text = ERROR
body CONTAINS "timeout"
trace_id = <TRACE_ID>
```

연산자는 `=` `!=` `IN` `NOT IN` `CONTAINS` `EXISTS`이고 `AND`/`OR`로 묶습니다. 자세히는 [§6](#6-로그-검색하기) · [§7](#7-자주-쓰는-로그-검색-예시).

### ④ 종료

| 목적 | 명령 |
|---|---|
| **오늘 작업 끝** (데이터 유지·리소스 회수) | `kubectl scale statefulset,deployment --all --replicas=0 -n signoz` |
| 릴리스 제거 (PVC·데이터 유지) | `helm uninstall signoz -n signoz` |
| **완전 삭제** (데이터까지) | `helm uninstall signoz -n signoz && kubectl delete pvc --all -n signoz && kubectl delete namespace signoz` |

평소에는 **첫 번째(scale 0)**를 쓰세요. 재설치 없이 바로 되살아납니다. 자세히는 [§9](#9-중지제거).

### 상태 확인 한 줄

```bash
helm list -n signoz && kubectl get pods -n signoz
```

`STATUS`가 `deployed`이고 Pod가 모두 `Running`(migrator만 `Completed`)이면 정상입니다.

---

## 0. 시작 전 확인

### 검증 상태

| 항목 | 상태 |
|---|---|
| Kubernetes 설치 (§2) | **실제 설치로 검증** |
| 접속·헬스체크 (§3·§4) | **실측 확인** |
| Pod·서비스·PVC 구성 (§4) | **실측 확인** |
| 로그 검색 UI·연산자 (§6) | 공식 문서 확인 |
| 애플리케이션 OTel 연결 (§5) | **참조 프로젝트에서 실측 검증** — 백엔드를 kind에 배포해 로그·트레이스·메트릭 3신호가 ClickHouse에 적재되는 것을 확인. SceneTrip 첫 서비스 배포 시 재확인 필요 |

검증 환경: macOS 26.5 · Apple Silicon · Docker Desktop 29.6.2(10 CPU / 31.3GiB) · Kubernetes v1.36.1(kind 단일 노드) · Helm v4.2.3 · foundryctl v0.2.17 · SigNoz 차트 0.135.1

### 리소스 요구사항

SigNoz는 **ClickHouse + ClickHouse Operator + Zookeeper + PostgreSQL**을 포함해 가볍지 않습니다. Pod 6개가 상주하고 **PVC 38GiB**를 잡습니다.

| 항목 | 요구사항 |
|---|---|
| Docker Desktop 할당 메모리 | **최소 6GB** 권장 (SigNoz 몫 4GB + 클러스터 오버헤드) |
| 디스크 | PVC 38GiB (zookeeper 8Gi + postgres 10Gi + clickhouse 20Gi) |
| 사전 확인 | 클러스터 노드가 모두 `Ready` |

> **주의 — 8GB MacBook 사용자**
> [k8s_install.md](k8s_install.md) §6은 8GB 장비에 Docker 메모리 **4GB**를 권장합니다. 그 상태로 SigNoz를 올리면 **SigNoz 하나가 할당량을 전부 소진**해 프로젝트 애플리케이션을 함께 돌릴 수 없습니다.
>
> 8GB 장비라면 다음 중 하나를 택하세요.
>
> - SigNoz를 쓸 때만 Docker 메모리를 6GB로 올리고, 프로젝트 앱은 클러스터가 아닌 로컬에서 실행
> - SigNoz는 팀 공용 인스턴스를 사용하고 로컬에는 설치하지 않음

### 설치 전 컨텍스트 확인 (필수)

```bash
kubectl config current-context
kubectl get nodes
```

`kind-scenetrip`이 아니면 **EKS에 설치될 수 있습니다.** 반드시 확인하고 진행하세요 ([k8s_install.md](k8s_install.md) §20).

```bash
kubectl config use-context kind-scenetrip
```

---

## 1. Foundry 이해하기

SigNoz는 **Foundry**(`foundryctl`)라는 CLI로 설치합니다. `casting.yaml` 설정 파일 하나에 "무엇을 어디에 배포할지"를 선언하면 Foundry가 Helm values를 생성하고 배포까지 처리합니다.

> **중요**
> 예전 방식인 `install.sh` 스크립트와 저장소에 번들된 `deploy/` 하위 파일은 **SigNoz v0.130.0부터 폐기(deprecated)되어 더 이상 유지보수되지 않습니다.** 인터넷에 남아 있는 "git clone 후 설치" 안내는 모두 구버전입니다.

### 하위 명령

| 명령 | 역할 |
|---|---|
| `catalog` | 지원되는 배포 조합 출력 |
| `gen examples` | 지원 조합별 **정답 예시 파일** 생성 |
| `gauge` | 배포에 필요한 도구(`kubectl`·`helm`)가 있는지 검증 |
| `forge` | casting을 읽어 Helm values 생성 → `pours/`에 출력 (**배포하지 않음**) |
| `cast` | `gauge` + `forge` + 배포를 한 번에 실행 |

지원 조합은 CLI가 직접 알려줍니다.

```bash
foundryctl catalog --format text
```

```text
┌────────────┬───────────┬──────────┬──────────────────────┐
│    MODE    │  FLAVOR   │ PLATFORM │       EXAMPLE        │
├────────────┼───────────┼──────────┼──────────────────────┤
│ docker     │ compose   │          │ docker/compose       │
│ docker     │ swarm     │          │ docker/swarm         │
│ kubernetes │ helm      │          │ kubernetes/helm      │
│ kubernetes │ kustomize │          │ kubernetes/kustomize │
│ systemd    │ binary    │          │ systemd/binary       │
│ ec2        │ terraform │ ecs      │ ecs/ec2/terraform    │
└────────────┴───────────┴──────────┴──────────────────────┘
```

우리는 **`kubernetes` / `helm`** 조합을 사용합니다.

> **설정이 막히면 `foundryctl gen examples`를 먼저 돌리세요.** 웹 문서보다 정확합니다 — 실제로 공식 문서 페이지의 예시와 CLI가 요구하는 스키마가 달랐습니다(§2.3).

---

## 2. Kubernetes 설치

### 2.1 foundryctl 설치

```bash
curl -fsSL https://signoz.io/foundry.sh | bash
```

> 파이프로 바로 실행하는 것이 꺼려지면 내려받아 확인 후 실행하세요.
>
> ```bash
> curl -fsSL https://signoz.io/foundry.sh -o /tmp/foundry.sh
> less /tmp/foundry.sh
> bash /tmp/foundry.sh
> ```

바이너리는 **`~/.local/bin/foundryctl`**에 설치됩니다. 이 경로가 `PATH`에 없으면 명령을 찾지 못합니다.

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

설치 확인:

```bash
foundryctl version --format text
```

### 2.2 작업 디렉터리 준비

생성 파일은 **저장소 밖**에 두세요. `pours/` 산출물이 저장소를 오염시킵니다.

```bash
mkdir -p ~/signoz && cd ~/signoz
```

### 2.3 casting 작성

```bash
cat > casting-k8s.yaml <<'YAML'
apiVersion: v1alpha1
kind: Installation
metadata:
  name: signoz
spec:
  deployment:
    flavor: helm
    mode: kubernetes
YAML
```

> **⚠️ 흔한 실수 — `deployment:` 레벨 누락**
> `flavor`·`mode`는 반드시 **`spec.deployment` 아래**에 와야 합니다. 공식 문서 웹페이지에는 이 중간 레벨이 빠진 예시가 실려 있어, 그대로 복사하면 다음 오류가 납니다.
>
> ```json
> { "exception": { "type": "unsupported",
>   "message": "deployment '{Platform: Mode: Flavor: _:{}}' is not supported" } }
> ```
>
> `Platform`·`Mode`·`Flavor`가 **전부 빈 값**으로 찍히면 이 문제입니다. 값이 틀린 게 아니라 위치가 틀린 것입니다.

### 2.4 도구 검증

```bash
foundryctl gauge -f casting-k8s.yaml --format text
```

출력이 없으면 통과입니다.

### 2.5 배포 파일 먼저 생성해 확인

**바로 배포하지 말고 `forge`로 Helm values를 뽑아 확인하는 것을 권장합니다.**

```bash
foundryctl forge -f casting-k8s.yaml -p ./pours-k8s --format text
```

```text
pours-k8s/deployment/values.yaml
```

생성된 values를 열어 구성 요소를 확인합니다.

```bash
grep -E 'enabled:|fullnameOverride|replicaCount' pours-k8s/deployment/values.yaml
```

기본값으로 `clickhouse`, `zookeeper`, `postgresql`, `otelCollector`, `telemetryStoreMigrator`가 모두 활성화됩니다. 리소스가 빠듯하면 이 파일에서 조정한 뒤 배포하세요.

### 2.6 배포

```bash
foundryctl cast -f casting-k8s.yaml -p ./pours-k8s --format text
```

**약 3~4분** 소요됩니다. 다음 경고들이 출력되지만 **설치는 정상 진행됩니다.**

```text
warning: skipped value for zookeeper.initContainers: Not a table.
Warning: spec.template.spec.containers[0].env[20]: hides previous definition of "SIGNOZ_SQLSTORE_PROVIDER"
Warning: spec.template.spec.containers[0].env[21]: hides previous definition of "SIGNOZ_SQLSTORE_POSTGRES_DSN"
```

> 첫 번째는 Helm 4.x에서 나오는 값 파싱 경고, 나머지 둘은 환경변수 중복 정의 경고입니다. 셋 다 무시해도 됩니다.

네임스페이스 `signoz`는 **자동 생성**되며, 별도로 만들 필요가 없습니다.

---

## 3. 접속

Helm이 만드는 SigNoz UI 서비스는 `ClusterIP`라 그대로는 외부에 노출되지 않습니다. 그래서 저장소에 **NodePort 서비스를 따로** 두었습니다(`platform/kubernetes/signoz/ui-nodeport.yaml`, `cluster-up.sh`가 적용).

```text
http://localhost:8080/
```

터미널을 켜 둘 필요가 없습니다. NodePort 30080이 kind의 `extraPortMappings`로 호스트 8080에 연결돼 있기 때문입니다(`platform/kind/cluster.yaml`).

> **`port-forward`로 8080을 또 열지 마세요.** kind 노드 컨테이너가 이미 그 호스트 포트를 잡고 있어, 겹쳐 열면 리스너가 둘 생기고 어느 쪽이 응답할지 OS 바인딩 우선순위에 달리게 됩니다. 실제로 "설정을 고쳤는데 반영이 안 되는" 것처럼 보이는 혼란이 생깁니다.
>
> Helm이 만든 `svc/signoz`는 건드리지 않습니다 — 차트 업그레이드 때 되돌려지기 때문에, 같은 파드를 가리키는 우리 소유의 서비스를 따로 둡니다.

접속이 안 되면 NodePort와 매핑을 확인하세요.

```bash
kubectl get svc signoz-ui -n signoz -o jsonpath='{.spec.ports[0].nodePort}{"\n"}'   # 30080
docker ps --filter name=scenetrip-control-plane --format '{{.Ports}}'               # 8080->30080
```

최초 접속 시 관리자 계정을 생성합니다. 로컬 전용 계정이므로 **실제로 쓰는 비밀번호를 재사용하지 마세요.**
비밀번호 규칙은 **12자 이상 + 대문자 + 소문자 + 숫자 + 기호**입니다 — 만족하지 않으면 `invalid_password`로 거부됩니다.

---

## 4. 설치 확인

### Helm 릴리스

```bash
helm list -n signoz
```

```text
NAME    NAMESPACE  REVISION  STATUS    CHART           APP VERSION
signoz  signoz     1         deployed  signoz-0.135.1  v0.135.1
```

`STATUS`가 **`deployed`**여야 완료입니다. 마이그레이션이 끝나기 전에는 `pending-install`로 표시됩니다.

### Pod 상태

```bash
kubectl get pods -n signoz
```

정상 설치 시 구성은 다음과 같습니다.

| Pod | 역할 | 정상 상태 |
|---|---|---|
| `signoz-0` | UI + 쿼리 서비스 | `1/1 Running` |
| `signoz-ingester-*` | OTel Collector (수집 입구) | `1/1 Running` |
| `chi-signoz-telemetrystore-clickhouse-cluster-0-0-0` | ClickHouse — 로그·트레이스 저장 | `1/1 Running` |
| `signoz-telemetrystore-clickhouse-operator-*` | ClickHouse Operator | `2/2 Running` |
| `signoz-telemetrykeeper-zookeeper-0` | Zookeeper — 코디네이션 | `1/1 Running` |
| `signoz-metastore-postgres-0` | PostgreSQL — 대시보드·설정 메타데이터 | `1/1 Running` |
| `signoz-telemetrystore-migrator-*` | 스키마 마이그레이션 (Job) | **`0/1 Completed`** |

> **정상이지만 오해하기 쉬운 두 가지**
>
> - **migrator가 `Completed`인 것은 정상입니다.** 스키마를 올리고 끝나는 Job이라 종료됩니다.
> - **`signoz-0`의 RESTARTS가 2~3회인 것도 정상입니다.** PostgreSQL·ClickHouse가 준비되기 전에 먼저 떠서 생기는 backoff이며, 의존 서비스가 뜨면 안정화됩니다.

ClickHouse Pod 이름이 `chi-`로 시작하는 이유는 Operator가 `ClickHouseInstallation` CR을 통해 생성하기 때문입니다.

### 서비스

```bash
kubectl get svc -n signoz
```

| 서비스 | 주요 포트 | 용도 |
|---|---|---|
| `signoz` | `8080`, `8085`, `4320` | UI·API |
| `signoz-ingester` | `4317`(otlp), `4318`(otlp-http) | **텔레메트리 수집** |
| `signoz-metastore-postgres` | `5432` | 내부 |
| `signoz-telemetrykeeper-zookeeper` | `2181` | 내부 |

### 스토리지

```bash
kubectl get pvc -n signoz
```

```text
NAME                                                                          STATUS  CAPACITY  STORAGECLASS
data-signoz-telemetrykeeper-zookeeper-0                                       Bound   8Gi       standard
data-volumeclaim-template-chi-signoz-telemetrystore-clickhouse-cluster-0-0-0  Bound   20Gi      standard
pgdata-signoz-metastore-postgres-0                                            Bound   10Gi      standard
```

**PVC 3개, 합계 38GiB** 입니다. ClickHouse 볼륨은 차트가 아니라 ClickHouse Operator 가
만들기 때문에 다른 둘보다 늦게(30초~1분) 나타납니다 — 바로 안 보여도 정상입니다.

kind 의 기본 `standard` 스토리지클래스(rancher.io/local-path)로 **자동 Bound**됩니다. 별도 설정이 필요 없습니다.

### 헬스체크

호스트에서 바로 확인합니다(UI NodePort 경유):

```bash
curl -s http://localhost:8080/api/v1/health
```

```json
{"status":"ok"}
```

클러스터 안에서 직접 확인할 수도 있습니다.

```bash
kubectl exec -n signoz signoz-0 -- wget -qO- http://localhost:8080/api/v1/health
```

---

## 5. 애플리케이션 연결 (OpenTelemetry)

SigNoz는 스스로 로그를 만들지 않습니다. **애플리케이션이 OTLP로 보내야** 화면에 나타납니다.

수집 엔드포인트는 **애플리케이션이 어디서 도는지에 따라 달라집니다.**

| 애플리케이션 위치 | OTLP 엔드포인트 |
|---|---|
| **클러스터 안** (Pod) | `http://signoz-ingester.signoz.svc.cluster.local:4317` |
| **맥 로컬** (`bootRun` 등) | `http://localhost:4317` — **아래 port-forward 필요** |

로컬에서 앱을 돌린다면 수집기로도 port-forward를 열어야 합니다. UI용과 별개의 터미널이 필요합니다.

```bash
kubectl port-forward -n signoz svc/signoz-ingester 4317:4317 4318:4318
```

### 5.1 백엔드 서비스 (JVM 예시)

**클러스터에 배포하는 경우, 배선은 모듈이 소유합니다.** 모듈의 `Dockerfile`이 에이전트를 이미지에 넣고 `ENTRYPOINT`에 붙이며, 필요한 환경변수는 `platform/kubernetes/<모듈>/configmap.yaml`에 둡니다. 아래는 JVM 서비스 기준 예시입니다 — 다른 런타임은 §5.2 를 참고하세요.

아래는 **맥에서 직접(`bootRun`) 돌릴 때**의 방법입니다.

```bash
mkdir -p ~/otel && curl -L -o ~/otel/opentelemetry-javaagent.jar \
  https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.30.0/opentelemetry-javaagent.jar
```

> **`latest`로 받지 마세요. 버전을 고정해야 합니다.**
> 에이전트 **2.14.0**은 Spring Boot 4에서 모든 HTTP 응답 본문을 0바이트로 만들고(상태 코드·헤더는 정상), logback 계측이 `NoSuchFieldError`를 일으켜 기동 자체를 실패시킵니다. 둘 다 실측으로 확인했습니다.
> 컨테이너가 쓰는 버전은 **`MODULE.bazel`의 `http_file` 하나가 정본**입니다 — 그 값과 맞추세요. Bazel이 sha256까지 확인해 받고, `just image <모듈>`이 그것을 이미지에 담습니다([ADR 0004](../architecture/adr/0004-opentelemetry-javaagent.md)). 컨테이너 쪽은 `-javaagent`가 `ENTRYPOINT`에 이미 들어 있어 따로 줄 것이 없습니다 — 보낼 곳만 `platform/kubernetes/<모듈>/configmap.yaml`의 `OTEL_*`이 정합니다.

```bash
JAVA_TOOL_OPTIONS="-javaagent:$HOME/otel/opentelemetry-javaagent.jar" \
OTEL_SERVICE_NAME=scenetrip-scene-api \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
OTEL_LOGS_EXPORTER=otlp \
OTEL_TRACES_EXPORTER=otlp \
OTEL_METRICS_EXPORTER=otlp \
<앱 실행 명령>
```

> `OTEL_SERVICE_NAME`은 **로그 검색의 1차 필터 키**가 됩니다. 모듈별로 다르게 주면 화면에서 섞이지 않습니다.

### 5.2 AI 에이전트 (Python)

```bash
pip install opentelemetry-distro opentelemetry-exporter-otlp
opentelemetry-bootstrap --action=install
```

```bash
OTEL_SERVICE_NAME=scenetrip-trip-planner \
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317 \
OTEL_LOGS_EXPORTER=otlp \
opentelemetry-instrument python main.py
```

### 5.3 서비스 이름 규칙 (제안)

로그가 쌓이기 시작하면 이름 규칙이 없을 때 검색이 급격히 힘들어집니다. 팀 합의 전이라면 아래를 기본으로 쓰세요.

규칙은 **`scenetrip-<모듈 디렉터리 이름>`** 입니다. 저장소의 디렉터리 이름과 1:1로 맞추면
로그 화면의 서비스 이름만 보고도 어느 코드인지 바로 찾을 수 있습니다.

| 모듈 | `service.name` |
|---|---|
| `services/scene-api` | `scenetrip-scene-api` |
| `apps/web` | `scenetrip-web` |
| `agents/trip-planner` | `scenetrip-trip-planner` |
| `agents/scene-matcher` | `scenetrip-scene-matcher` |

---

## 6. 로그 검색하기

좌측 메뉴에서 **Logs → Logs Explorer**로 들어갑니다.

### 6.1 화면 구성

| 영역 | 설명 |
|---|---|
| 좌측 **Quick Filters** | 자주 쓰는 속성(서비스·심각도 등)을 클릭만으로 필터링 |
| 상단 **필터 표현식 바** | 검색 조건을 직접 입력 |
| 중앙 **결과 영역** | List / Time Series / Table 3가지 보기 모드 |

### 6.2 보기 모드

| 모드 | 용도 |
|---|---|
| **List** | 개별 로그를 시간순으로 확인. 상단에 심각도별 빈도 차트 표시 |
| **Time Series** | 조건에 맞는 로그 **개수의 시간 추이**를 그래프로 확인 |
| **Table** | 그룹별 집계 결과를 표로 확인 |

장애를 볼 때는 **Time Series로 급증 시점을 찾고 → List로 그 구간의 실제 로그를 읽는** 순서가 빠릅니다.

### 6.3 검색 연산자

| 분류 | 연산자 |
|---|---|
| 비교 | `=`, `!=` |
| 목록 | `IN`, `NOT IN` |
| 텍스트 | `CONTAINS` |
| 존재 | `EXISTS` |

여러 조건은 `AND` / `OR`로 조합합니다.

### 6.4 로그 상세 보기

로그 한 줄을 클릭하면 상세 패널이 열리고 4개 탭이 제공됩니다.

| 탭 | 내용 |
|---|---|
| **Overview** | 로그 본문과 속성(attributes) |
| **JSON** | 원본 JSON 전체 |
| **Context** | **그 로그의 앞뒤 로그** — 원인 추적에 가장 유용 |
| **Metrics** | 해당 시점의 인프라 지표와 연결 |

에러 하나를 붙잡았다면 **Context 탭**부터 여세요. 직전에 무슨 일이 있었는지가 대부분 거기 있습니다.

### 6.5 Query Builder — 집계

단순 검색을 넘어 집계가 필요할 때 사용합니다.

```text
count()  avg()  sum()  p50()  p90()  p95()  p99()
```

`group by`와 조합해 "서비스별 에러 건수", "엔드포인트별 p95" 같은 질문에 답할 수 있습니다.

### 6.6 Saved Views

자주 쓰는 필터 조합은 이름을 붙여 저장해 두면 클릭 한 번으로 불러올 수 있습니다. 팀에서 반복 조회하는 조건(예: `AI 폴백 발생`)은 저장해 공유하세요.

---

## 7. 자주 쓰는 로그 검색 예시

속성 이름은 실제로 애플리케이션이 보내는 키에 맞춰 조정하세요.

**특정 서비스의 에러만**

```text
service.name = scenetrip-scene-api AND severity_text = ERROR
```

**여러 서비스를 한 번에**

```text
service.name IN (scenetrip-scene-api, scenetrip-trip-planner)
```

**본문에 특정 문구가 포함된 로그**

```text
body CONTAINS "CandidatePool"
```

**특정 속성이 존재하는 로그만**

```text
trace_id EXISTS
```

**에러이면서 특정 문구를 포함**

```text
severity_text = ERROR AND body CONTAINS "timeout"
```

**특정 요청 하나를 추적** — 트레이스와 로그를 잇는 가장 강력한 방법입니다.

```text
trace_id = <TRACE_ID>
```

> 트레이스 화면에서 느린 요청을 찾은 뒤 그 `trace_id`로 로그를 걸면, **그 요청 하나가 남긴 로그만** 시간순으로 모입니다. 로그를 grep으로 뒤지는 것과 비교가 되지 않습니다.

### 이 프로젝트에서 유용한 조회

AI 에이전트의 폴백(fallback)은 반드시 관측 대상입니다. 모델 호출이 실패했을 때
조용히 기본값으로 넘어가면, 사용자는 품질 저하를 겪는데 로그에는 아무것도 남지 않습니다.

```text
service.name = scenetrip-trip-planner AND body CONTAINS "fallback"
```

```text
service.name = scenetrip-scene-matcher AND severity_text = ERROR
```

> 위 조회에서 **아무것도 안 나오는데 사용자 불만은 있는 상황**이 가장 위험합니다 — 폴백이 로그를 남기지 않고 있다는 뜻이기 때문입니다. 무음 실패(silent failure)는 금지입니다.

---

## 8. 로그를 잘 남기는 규칙

검색은 남긴 만큼만 됩니다.

- **구조화 로그를 쓸 것** — 문자열을 이어 붙이지 말고 키·값 속성으로 남깁니다. `CONTAINS` 전문 검색보다 `=` 속성 필터가 훨씬 빠르고 정확합니다.
- **`trace_id`가 함께 나가게 할 것** — OTel 에이전트를 붙이면 대체로 자동 주입됩니다. 이것이 없으면 로그와 트레이스가 끊깁니다.
- **심각도를 정확히 쓸 것** — 전부 INFO로 남기면 `severity_text` 필터가 무력화됩니다.
- **민감 정보를 남기지 말 것** — SigNoz에 들어간 로그도 [k8s_install.md](k8s_install.md) §22의 민감 정보 규칙을 그대로 따릅니다. 토큰·비밀번호·좌표 원본·개인정보를 로그 본문에 넣지 마세요. 한번 수집되면 ClickHouse에 그대로 남습니다.

---

## 9. 중지·제거

### 일시 중지 (데이터 유지)

Pod만 0으로 줄여 리소스를 회수합니다. PVC와 설정은 남습니다.

```bash
kubectl scale statefulset,deployment --all --replicas=0 -n signoz
```

되돌릴 때:

```bash
kubectl scale statefulset,deployment --all --replicas=1 -n signoz
```

### 릴리스 제거 (PVC 유지)

```bash
helm uninstall signoz -n signoz
```

> `helm uninstall`은 **PVC를 지우지 않습니다.** 재설치하면 기존 데이터가 그대로 붙습니다.

### 완전 삭제

```bash
helm uninstall signoz -n signoz
kubectl delete pvc --all -n signoz
kubectl delete namespace signoz
```

> **주의**
> PVC 삭제는 **수집한 로그·트레이스·대시보드 설정을 모두 지웁니다.** 되돌릴 수 없습니다.

ClickHouse Operator가 설치한 CRD는 네임스페이스 삭제로 사라지지 않습니다. 완전히 정리하려면 별도로 확인하세요.

```bash
kubectl get crd | grep -i clickhouse
```

---

## 10. 문제 해결

### `foundryctl: command not found`

`~/.local/bin`이 `PATH`에 없는 경우입니다.

```bash
echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.zshrc
source ~/.zshrc
```

---

### `deployment '{Platform: Mode: Flavor: _:{}}' is not supported`

casting에서 **`spec.deployment` 레벨이 빠진** 경우입니다. §2.3의 형식과 대조하세요.

정답 예시를 직접 뽑아 비교할 수도 있습니다.

```bash
foundryctl gen examples
cat docs/examples/kubernetes/helm/casting.yaml
```

---

### helm STATUS가 계속 `pending-install`

마이그레이션 Job이 끝나지 않은 상태입니다. **정상 진행 중일 수 있으니 3~4분은 기다려 보세요.**

진행 상황은 migrator 로그로 확인합니다.

```bash
kubectl logs -n signoz job/signoz-telemetrystore-migrator --tail=20
```

`Operation completed`가 계속 찍히면 정상 진행 중입니다. ClickHouse가 뜨지 않아 멈춘 것이라면 아래를 확인하세요.

```bash
kubectl get pods -n signoz
kubectl describe pod -n signoz <PENDING_POD>
```

---

### Pod가 `Pending`에서 멈춤

리소스 부족 또는 PVC 바인딩 실패입니다.

```bash
kubectl describe pod -n signoz <POD_NAME> | tail -20
kubectl get pvc -n signoz
kubectl get events -n signoz --sort-by=.lastTimestamp | tail -10
```

`Insufficient memory`가 보이면 Docker Desktop 할당 메모리를 늘리세요 ([k8s_install.md](k8s_install.md) §6).

---

### `signoz-0`이 여러 번 재시작됨

**초기 기동 중 2~3회는 정상입니다.** PostgreSQL·ClickHouse 준비 전에 먼저 떠서 생기는 backoff입니다.

재시작이 계속 늘어난다면 로그를 확인하세요.

```bash
kubectl logs -n signoz signoz-0 --tail=50
kubectl logs -n signoz signoz-0 --previous --tail=50
```

---

### port-forward가 끊김

`port-forward`는 Pod가 재시작되면 끊깁니다. 명령을 다시 실행하세요. 터미널을 계속 붙잡고 있어야 한다는 점도 기억하세요.

---

### UI는 뜨는데 로그·트레이스가 하나도 없음

수집 경로가 끊긴 것입니다. **판별이 빠른 것부터** 순서대로 확인하세요.
2026-08-25 에 이 순서로 두 관문(계정 없음 → ClickHouse 메모리)을 통과했습니다.

#### 0단계 — 관리자 계정이 있는가 (5초)

```bash
curl -s http://localhost:8080/api/v1/version
```

```json
{"version":"v0.135.1","ee":"Y","setupCompleted":false}
```

`setupCompleted` 가 `false` 면 **여기서 끝입니다.** 계정(=조직)이 없으면 collector 가 OpAMP 로 파이프라인 설정을 받지 못해 **OTLP 수신기(4317·4318)가 아예 열리지 않습니다.** 앱에는 `Connection refused` 로 보입니다.

브라우저에서 `http://localhost:8080` 에 접속해 계정을 만드세요. 비밀번호는 **12자 이상 + 대문자 + 소문자 + 숫자 + 기호**여야 하고, 만족하지 않으면 `invalid_password` 로 거부됩니다.

> **거부된 것을 성공으로 착각하기 쉽습니다.** 폼을 채우고 넘어갔는데 실제로는 만들어지지 않은 상태가 흔합니다. 반드시 위 `curl` 로 `true` 를 확인하세요.
>
> **분명히 만들었는데 또 `false` 라면** 계정이 지워진 것입니다. 아래 [재시작할 때마다 계정이 사라진다](#재시작할-때마다-signoz-계정이-사라진다) 를 보세요 — 차트의 결함이고 고치는 방법이 있습니다.

계정을 만들면 **앱은 재시작할 필요가 없습니다.** 익스포터가 주기적으로 재시도하므로 알아서 붙습니다.

#### 1단계 — 앱이 내보내고 있는가

```bash
kubectl logs -n scenetrip deployment/scene-api --since=5m | grep -c "Failed to export"
```

`0` 이면 앱은 정상이니 3단계로 갑니다. 0 이 아니면 전문을 봅니다.

```text
ERROR io.opentelemetry.exporter.otlp.internal.GrpcExporter - Failed to export spans.
Caused by: java.net.ConnectException: Connection refused
```

`Connection refused` 는 **수신기가 안 열린 것**이므로 0단계로 돌아가세요. 그 밖에 확인할 것:

1. 애플리케이션에 OTel 에이전트가 실제로 붙었는지 (로그에 `[otel.javaagent …]` 가 뜨는지)
2. **로컬 앱이라면 ingester port-forward 를 열었는지** (§5)
3. 익스포터 셋이 다 켜져 있는지 (`OTEL_TRACES_EXPORTER`·`OTEL_METRICS_EXPORTER`·`OTEL_LOGS_EXPORTER`) — **트레이스만 켜고 로그를 빼먹는 실수가 잦습니다**
4. 클러스터 안 앱이라면 엔드포인트가 `signoz-ingester.signoz.svc.cluster.local:4317` 인지

#### 2단계 — 수집기가 무엇을 겪는가

```bash
kubectl logs -n signoz deployment/signoz-ingester --tail=30
```

에러 서명 두 가지를 구분합니다.

| 로그에 보이는 것 | 뜻 | 조치 |
| --- | --- | --- |
| `"component":"opamp-server-client"` + `Server returned an error response` | 설정을 못 받았다 | 0단계 (계정 없음) |
| `clickhousetracesexporter` + `Could not write a batch of spans` | 받긴 받았는데 저장을 못 한다 | 3단계 |

> 라벨 셀렉터로 조회한다면 `-l app.kubernetes.io/component=ingester` 를 쓰세요.
> `app.kubernetes.io/name=signoz-otel-collector` 는 더 이상 맞지 않아 **`No resources found` 가 나오고, 그것을 「에러 없음」으로 오독하기 쉽습니다.**

#### 3단계 — ClickHouse 메모리 (두 번째 관문)

계정을 만들었는데도 안 들어온다면 여기입니다.

```text
error: code: 241, message: (total) memory limit exceeded:
  would use 6.99 GiB (attempt to allocate chunk of 4.34 MiB),
  current RSS: 6.34 GiB, maximum: 6.98 GiB
```

**ClickHouse 파드에 메모리 `limits` 가 없어서 VM 전체를 자기 몫으로 계산합니다**(`requests: 200Mi` 만 있음). 같은 VM 에서 postgres·서비스·ZooKeeper 가 함께 도는데도 혼자 90% 를 잡으니, 캐시가 며칠 쌓이면 4 MiB 조차 할당하지 못합니다.

즉효 처방은 재시작입니다. **데이터는 PVC 에 있어 날아가지 않습니다.**

```bash
kubectl delete pod -n signoz chi-signoz-telemetrystore-clickhouse-cluster-0-0-0
```

재발을 막으려면 **Docker Desktop 메모리를 늘리세요**(Settings → Resources → Memory). 7.8 GiB 로는 부족했고 12~16 GiB 를 권합니다.

#### 4단계 — 적재 확인

```bash
just signoz-verify                          # 최근 10분
just signoz-verify scenetrip-scene-api 60   # 창을 넓혀서
```

> **트레이스·메트릭은 들어오는데 로그만 `0` 이면 정상일 수 있습니다.** 수집이 끊겨 있던 동안 발생한 로그는 버려지고, 그 뒤로 앱이 조용했다면 셀 것이 없습니다. 파드를 한 번 재시작해 기동 로그를 새로 만든 뒤 다시 확인하세요.

```bash
kubectl rollout restart deployment/scene-api -n scenetrip
```

---

### 재시작할 때마다 SigNoz 계정이 사라진다

**증상.** 관리자 계정을 만들어 잘 쓰다가, 파드나 Docker Desktop 을 재시작하면 `setupCompleted` 가 다시 `false` 가 됩니다. 계정이 없으니 collector 가 설정을 못 받아 OTLP 수신기도 닫힙니다(0단계와 같은 상태).

**원인은 차트의 마운트 경로입니다.** 2026-08-25 에 실측으로 확인했습니다.

`postgres:16` 이미지는 Dockerfile 에 `VOLUME /var/lib/postgresql/data` 를 선언합니다. containerd 는 그 선언을 존중해 **그 자리에 임시(ephemeral) 마운트를 만듭니다.** 그런데 차트는 PVC 를 그 **부모**인 `/var/lib/postgresql` 에 붙이므로, 임시 마운트가 PVC 의 `data` 하위 폴더를 **가려 버립니다.** postgres 는 임시 공간에 데이터를 쓰고, 컨테이너가 재시작되면 통째로 사라집니다. 빈 자리를 본 postgres 는 `initdb` 로 DB 를 새로 만들고 계정도 함께 없어집니다.

```bash
# 마운트가 둘이면 걸린 것입니다 — 아래쪽이 PVC 를 가리는 임시 마운트입니다
kubectl exec -n signoz signoz-metastore-postgres-0 -- grep postgresql /proc/mounts
#   /dev/vda1 /var/lib/postgresql      ext4 …   ← PVC
#   /dev/vda1 /var/lib/postgresql/data ext4 …   ← 이미지 VOLUME (임시)

# postgres 가 매번 DB 를 새로 만들었는지
kubectl logs -n signoz signoz-metastore-postgres-0 | grep -c "running bootstrap script"
```

**고치는 법 — PVC 를 `data` 에 직접 붙입니다.** 명시적 마운트가 이미지 VOLUME 을 이깁니다.

```bash
IDX=$(kubectl get statefulset -n signoz signoz-metastore-postgres -o json \
  | python3 -c "import sys,json;e=json.load(sys.stdin)['spec']['template']['spec']['containers'][0]['env'];print([i for i,x in enumerate(e) if x['name']=='PGDATA'][0])")

kubectl patch statefulset -n signoz signoz-metastore-postgres --type=json -p "[
  {\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/volumeMounts/3/mountPath\",\"value\":\"/var/lib/postgresql/data\"},
  {\"op\":\"replace\",\"path\":\"/spec/template/spec/containers/0/env/$IDX/value\",\"value\":\"/var/lib/postgresql/data/pgdata\"}
]"

kubectl delete pod -n signoz signoz-metastore-postgres-0   # 새 설정으로 재기동
kubectl delete pod -n signoz signoz-0                      # 빈 DB 에 마이그레이션 재적용
```

`PGDATA` 를 하위 폴더로 내리는 것은 공식 postgres 이미지가 권하는 방식입니다. 마운트 지점 바로 위에 데이터를 두면 파일시스템에 따라 `lost+found` 때문에 초기화가 거부됩니다.

패치 뒤에는 **계정을 한 번 더 만들어야 합니다.** 그 뒤로는 유지됩니다.

> **`helm upgrade` 를 하면 이 패치가 되돌아갑니다.** 차트가 값으로 노출하지 않는 부분이라 지금은 수동 패치뿐입니다. 업그레이드한 뒤 계정이 또 사라지면 여기를 다시 보세요.

**우리 서비스 DB(`scenetrip`)는 왜 멀쩡한가.** 같은 `VOLUME` 을 선언한 이미지를 쓰지만, `platform/kubernetes/postgres/` 는 PVC 를 `/var/lib/postgresql/data` 에 **직접** 붙이고 `PGDATA` 를 그 하위 `pgdata` 로 둡니다. 위 처방과 같은 모양입니다.

---

### 오래된 설치 방법을 안내하는 블로그를 따라 했다면

`git clone` 후 번들 파일로 설치하는 방식은 **v0.130.0부터 폐기**됐습니다. 그렇게 띄운 스택은 정리하고 §2부터 다시 진행하세요.

---

## 11. 공식 문서

- [SigNoz 문서 홈](https://signoz.io/docs/)
- [Kubernetes 설치](https://signoz.io/docs/install/kubernetes/)
- [Helm 직접 배포](https://signoz.io/docs/install/kubernetes/others/)
- [로그 사용 가이드](https://signoz.io/docs/userguide/logs/)
- [Foundry 저장소](https://github.com/SigNoz/foundry) · [Foundry CLI 레퍼런스](https://github.com/SigNoz/foundry/blob/main/docs/reference/cli.md)
- [Foundry 소개 글](https://signoz.io/blog/introducing-signoz-foundry/)

> 웹 문서와 CLI가 어긋나는 경우가 실제로 있었습니다(§2.3). **`foundryctl catalog`·`foundryctl gen examples`가 가장 정확한 기준**이며, 확인한 내용은 이 문서에 반영해 주세요.

---

## 부록: Docker 단독 설치 (비표준)

> **팀 표준이 아닙니다.** 배포 환경(EKS)과 구성이 달라 로컬 검증 결과가 그대로 이어지지 않습니다. Kubernetes를 쓸 수 없는 상황에서만 참고하세요.

casting의 `deployment`만 바꾸면 됩니다.

```yaml
apiVersion: v1alpha1
kind: Installation
metadata:
  name: signoz
spec:
  deployment:
    flavor: compose
    mode: docker
```

```bash
foundryctl cast -f casting.yaml --format text
```

접속은 port-forward 없이 `http://localhost:8080/`으로 바로 됩니다.

### Kubernetes 방식과의 차이

| 항목 | Kubernetes(helm) | Docker(compose) |
|---|---|---|
| 코디네이션 | **Zookeeper** | **ClickHouse Keeper** |
| ClickHouse 관리 | ClickHouse **Operator**가 CR로 생성 | 컨테이너 직접 실행 |
| UI 접속 | NodePort 30080 → `localhost:8080` 직접 | `localhost:8080` 직접 |
| 생성 파일 | `pours/deployment/values.yaml` | `pours/deployment/compose.yaml` |

제거는 생성된 compose 파일 위치에서 수행합니다.

```bash
cd ~/signoz/pours/deployment
docker compose -f compose.yaml down -v   # -v 는 데이터까지 삭제
```
