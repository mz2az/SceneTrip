# 로컬 Kubernetes(kind)와 관측성(SigNoz).
#
# 가이드: docs/installs/k8s_install.md · docs/installs/signoz_install.md
# 강의:   docs/education/k8s-observability-class.html  (`just slides`)
#
# 여기 레시피는 전부 로컬 kind 클러스터만 대상으로 한다. 스크립트가 다른 컨텍스트에서는
# 실행을 거부한다 — 사람이 매번 지켜야 하는 규칙은 규칙이 아니므로, 문서로 적지 않고 막는다.

CLUSTER    := "scenetrip"
K8S_NS     := "scenetrip"
SIGNOZ_NS  := "signoz"
API_URL    := "http://localhost:8081"
SIGNOZ_URL := "http://localhost:8080"

# ── 클러스터 ──────────────────────────────────────────────────────────────────

# kind 클러스터를 만들고 SigNoz 를 설치한다. 멱등 — 여러 번 실행해도 안전하다.
[group('k8s')]
cluster-up:
    ./tools/scripts/cluster-up.sh

# 인프라만 기동 — 클러스터와 SigNoz. 애플리케이션과 데이터는 'just stack-up'.
[group('k8s')]
up: cluster-up
    @echo "준비 완료 — UI 는 'just signoz', 상태는 'just cluster-doctor'"
    @echo "API 에 데이터까지 필요하면: just stack-up"

# 단계를 하나 빠뜨려도 아무것도 깨지지 않고 화면만 빈다 — seed 를 빼면 API 가 빈
# 배열을 주고, 색인 갱신을 빼면 검색만 0 건이 된다. 둘 다 health 는 초록이라 알아채기
# 어렵다. 그래서 순서를 사람의 기억이 아니라 이 레시피가 들고 있고, 마지막에 실제
# 요청으로 건수까지 확인한다. 인자를 주면 볼트의 전량 CSV 를 적재한다.
#
# 클러스터부터 데이터까지 한 번에 — 앱을 붙여 화면을 보려면 이것을 쓴다
[group('k8s')]
stack-up *csv:
    ./tools/scripts/stack-up.sh {{ csv }}

# 클러스터 삭제. 수집한 텔레메트리와 DB 데이터도 함께 사라진다.
[group('k8s')]
[confirm("kind 클러스터를 수집된 텔레메트리와 함께 삭제합니다. 진행할까요?")]
cluster-down:
    kind delete cluster --name {{CLUSTER}}

# kubectl 컨텍스트를 로컬 클러스터로 전환
[group('k8s')]
cluster-context:
    kubectl config use-context kind-{{CLUSTER}}
    @kubectl config current-context

# 도구·클러스터·SigNoz·워크로드를 한 화면에
[group('k8s')]
cluster-doctor:
    ./tools/scripts/cluster-doctor.sh

# 애플리케이션 네임스페이스에서 돌고 있는 것 전부
[group('k8s')]
cluster-status:
    kubectl get all -n {{K8S_NS}}

# 클러스터가 실제로 도는지 증명 — nginx 를 띄우고 호출한 뒤 정리한다
[group('k8s')]
cluster-test-drive:
    ./tools/scripts/cluster-test-drive.sh

# ── 애플리케이션 배포 ─────────────────────────────────────────────────────────

# kind load 단계가 이미지를 클러스터에 보이게 만든다. 이걸 빠뜨리는 것이 로컬
# Kubernetes 에서 가장 흔한 실수다 — 오류가 나지 않고 옛 이미지가 조용히 계속 돈다.
#
# 모듈 이미지를 빌드해 kind 노드에 적재. 예: `just image scene-api`
[group('k8s')]
image module tag="dev":
    ./tools/scripts/image-build.sh {{module}} {{tag}}

# 모듈 매니페스트를 적용하고 롤아웃을 기다린다. 예: `just deploy scene-api local`
[group('k8s')]
deploy module env="local":
    ./tools/scripts/deploy.sh {{module}} {{env}}

# Secret 만은 매니페스트 파일이 없다 — deploy 가 폴더째 apply 하므로 파일에 두면
# 빈 값으로 덮어쓰거나 키가 저장소에 올라간다. 이유는 secrets-apply.sh 머리말에.
# 이미 떠 있는 파드는 새 값을 모른다 — 뒤에 `just restart scene-api`.

# .env 의 비밀값을 클러스터 Secret 으로 넣는다. 처음 한 번, 키를 바꿀 때마다
[group('k8s')]
secrets-apply:
    ./tools/scripts/secrets-apply.sh

# 환경변수는 컨테이너가 뜰 때 한 번 읽힌다. Secret·ConfigMap 을 바꾼 뒤에 쓴다.
# 코드가 바뀌었으면 이게 아니라 `just update` 다.

# 이미지는 그대로 두고 파드만 다시 띄운다. 예: `just restart scene-api`
[group('k8s')]
restart module:
    kubectl rollout restart deployment/{{module}} -n {{K8S_NS}}
    kubectl rollout status  deployment/{{module}} -n {{K8S_NS}} --timeout=180s

# 「맞나」가 아니라 「돌아가나」 — 키가 파드에 들어갔나, 카카오에 닿나, 응답이 우리
# 모양인가. 게이트 초록으로는 못 잡는 환경 문제를 본다. 카카오를 진짜로 부르므로
# (최대 7번) 자동 게이트에 넣지 않는다. 코스를 만들고 끝나면 지운다.

# 배포된 길찾기가 실제로 답하는지 본다. 배포 직후 한 번
[group('k8s')]
navigation-smoke:
    API_URL={{API_URL}} ./tools/scripts/navigation-smoke.sh

# 코드 변경 후 재빌드 → 적재 → 롤링 재시작
[group('k8s')]
update module:
    ./tools/scripts/image-build.sh {{module}}
    kubectl rollout restart deployment/{{module}} -n {{K8S_NS}}
    kubectl rollout status  deployment/{{module}} -n {{K8S_NS}} --timeout=180s

# 모듈을 이전 리비전으로 되돌린다
[group('k8s')]
[confirm("실행 중인 배포를 롤백합니다. 진행할까요?")]
rollback module env="local":
    ./tools/scripts/deploy.sh {{module}} {{env}} --rollback

# 클러스터와 SigNoz 는 그대로 두고 모듈만 제거
[group('k8s')]
undeploy module:
    kubectl delete -f platform/kubernetes/{{module}} -n {{K8S_NS}} --ignore-not-found

# 모듈 로그 따라가기. 예: `just logs scene-api`
[group('k8s')]
logs module:
    kubectl logs -f deployment/{{module}} -n {{K8S_NS}}

# 모듈 파드에 셸 접속
[group('k8s')]
pod-shell module:
    kubectl exec -it deployment/{{module}} -n {{K8S_NS}} -- /bin/sh

# 배포된 API 의 liveness·readiness 확인
[group('k8s')]
health:
    @curl -sS {{API_URL}}/v1/actuator/health/liveness || echo "{{API_URL}} 응답 없음"
    @echo
    @curl -sS {{API_URL}}/v1/actuator/health/readiness || true
    @echo

# ── 데이터베이스 ──────────────────────────────────────────────────────────────
#
# Service 가 ClusterIP 라 노트북에서 5432 로 직접 닿지 않는다 (의도한 것이다 —
# platform/kubernetes/postgres/service.yaml). 그래서 psql 은 전부 파드 안에서 돈다.

# DB 에 psql 로 붙는다. 인자를 주면 그 SQL 만 실행하고 끝난다.
# 예: `just db-psql` · `just db-psql '\dt'` · `just db-psql 'SELECT count(*) FROM place;'`
[group('db')]
db-psql *sql:
    ./tools/scripts/db-psql.sh "{{ sql }}"

# 터미널을 붙잡고 있는다. Ctrl+C 로 중지.
#
# `just db-psql` 은 파드 안에서 돌아 포워딩이 필요 없다. 이 레시피가 필요한 자리는
# **저장소 밖 도구**다 — TablePlus·DBeaver 같은 GUI 나 노트북에 직접 깔린 psql.
# 접속 정보를 함께 찍어 준다.

# DB 를 노트북 포트로 잇는다 (GUI 클라이언트용)
[group('db')]
db-forward:
    ./tools/scripts/db-forward.sh

# 접속 대상은 SCENETRIP_DB_HOST 가 있으면 그것, 없으면 kind 클러스터다 (ADR 0005).
#
# 앱을 띄우지 않고 스키마만 올린다 — CI 와 "배포 전 마이그레이션" 에 쓴다
[group('db')]
db-migrate:
    ./tools/scripts/db-migrate.sh

# initdb 가 볼륨을 처음 만들 때만 읽는 설정(POSTGRES_INITDB_ARGS — 로케일·인코딩)을
# 바꾸려면 볼륨을 지우는 수밖에 없다. 파드만 다시 띄우면 아무 일도 일어나지 않는다.
# `just cluster-down` 과 달리 postgres 만 건드리므로 SigNoz 의 텔레메트리는 남는다.

# 적재분은 `just seed` 와 같은 인자다. 비워 두면 저장소 표본이 들어가므로, 볼트 전량으로
# 적재해 둔 DB 라면 경로를 반드시 함께 넘긴다.

# DB 볼륨을 지우고 처음부터 다시 만든다 — 스키마·적재·색인까지 이어서
[group('db')]
[confirm("postgres 볼륨을 삭제하고 처음부터 다시 만듭니다. 적재된 데이터가 사라집니다. 진행할까요?")]
db-recreate *csv:
    ./tools/scripts/db-recreate.sh {{ csv }}

# 적용된 마이그레이션 이력. 실패한 것이 있으면 success 가 f 로 남는다.
[group('db')]
db-migrations:
    @just db-psql 'SELECT installed_rank, version, description, success, execution_time FROM flyway_schema_history ORDER BY installed_rank;'

# 스키마가 실제로 섰는지 한눈에 — 테이블·뷰·확장
[group('db')]
db-schema:
    @just db-psql '\dt public.*'
    @just db-psql '\dm public.*'
    @just db-psql 'SELECT extname, extversion FROM pg_extension ORDER BY extname;'

# V6 수집 데이터를 적재한다. 인자가 없으면 저장소의 표본 12 행.
#
# 전량은 볼트의 CSV 를 가리킨다 — 저장소에는 두지 않는다. 데이터가 정제 전이라
# 정본은 볼트에 있고, 저장소에는 볼트 없이도 동작을 확인할 만큼만 둔다.
# 예: `just seed` · `just seed ~/mz2az/.../촬영지_TOP_v6_정예4작품.csv`
#
# **적재된 기존 데이터를 지우고 다시 넣는다.** content·person 에 자연키가 없어
# ON CONFLICT 로는 멱등을 만들 수 없다. 로컬 kind 에서만 동작한다.
[group('db')]
seed *csv:
    ./tools/scripts/seed.sh "{{ csv }}"

# POI(편의시설)를 적재한다. 인자가 없으면 저장소의 표본 23 행.
#
# 전량(50만 행·190 MB)은 TMAP 약관상 저장소에 두지 않는다 — 승길이 준 파일을 경로로
# 넘긴다. 여러 파일은 이어 붙여 **한 번에** 넣는다 — 파일을 넘나드는 중복을 한 번에 접는다.
# `just poi-filter` 로 걸러 둔 파일을 준다. 예:
#   just seed-poi "~/Downloads/압축 poi 2/허용목록만/"poi_{food,stay,sight,transit}.jsonl.gz
#
# 지우지 않는다 — source_id 로 UPSERT 라 다시 돌려도 안전하고, 좌표가 바뀐 판은 갱신된다.
#
# POI(편의시설) 적재 — 인자 없으면 저장소 표본 23 행, 있으면 그 .jsonl(.gz) 파일들
[group('db')]
[positional-arguments]
seed-poi *files:
    ./tools/scripts/seed-poi.sh "$@"

#   just poi-filter "~/Downloads/압축 poi 2/poi_food.jsonl.gz" "~/Downloads/압축 poi 2/허용목록만/poi_food.jsonl.gz"
#
# POI 원본에서 허용목록 갈래만 남긴 파일을 만든다. 원본은 손대지 않는다
[group('db')]
poi-filter src dst:
    ./tools/scripts/poi-filter.sh "{{ src }}" "{{ dst }}"

# 적재 후 검색 색인 갱신. CONCURRENTLY 라 갱신 중에도 검색이 계속 응답한다.
[group('db')]
db-refresh-search:
    @just db-psql 'REFRESH MATERIALIZED VIEW CONCURRENTLY search_term;'
    @just db-psql 'SELECT entity_type, count(*) FROM search_term GROUP BY entity_type ORDER BY entity_type;'

# ── 관측성 ────────────────────────────────────────────────────────────────────

# SigNoz UI 주소와 필터 안내
[group('observability')]
signoz:
    @echo "SigNoz UI : {{SIGNOZ_URL}}"
    @echo "필터      : service.name = scenetrip-<모듈>"
    @echo "port-forward 불필요 — NodePort 30080 이 호스트 8080 에 매핑돼 있습니다"

# 브라우저에서 SigNoz UI 열기
[group('observability')]
signoz-open:
    @open {{SIGNOZ_URL}} 2>/dev/null || xdg-open {{SIGNOZ_URL}} 2>/dev/null || echo "{{SIGNOZ_URL}}"

# Helm 릴리스와 파드 상태. 파드가 Running, migrator 가 Completed 면 정상이다.
[group('observability')]
signoz-status:
    -@helm list -n {{SIGNOZ_NS}}
    @kubectl get pods -n {{SIGNOZ_NS}}

# SigNoz 를 0 으로 줄여 메모리 회수. 데이터와 설정은 유지된다.
[group('observability')]
signoz-pause:
    kubectl scale statefulset,deployment --all --replicas=0 -n {{SIGNOZ_NS}}

# 일시 중지한 SigNoz 를 다시 깨운다
[group('observability')]
signoz-resume:
    kubectl scale statefulset,deployment --all --replicas=1 -n {{SIGNOZ_NS}}

# 터미널을 붙잡고 있는다. Ctrl+C 로 중지.
#
# 맥에서 직접 도는 앱이 텔레메트리를 보낼 수 있도록 OTLP 수집기를 포워딩
[group('observability')]
signoz-forward:
    @echo "OTLP 엔드포인트 -> localhost:4317 (grpc) / localhost:4318 (http)"
    kubectl port-forward -n {{SIGNOZ_NS}} svc/signoz-ingester 4317:4317 4318:4318

# ClickHouse 를 직접 조회해 텔레메트리가 실제로 적재됐는지 확인
[group('observability')]
signoz-verify service="scenetrip-scene-api" minutes="10":
    ./tools/scripts/signoz-verify.sh {{service}} {{minutes}}

# 로그 적재량을 주기적으로 출력. Ctrl+C 로 중지.
[group('observability')]
signoz-watch service="scenetrip-scene-api" interval="5":
    ./tools/scripts/signoz-watch.sh {{service}} {{interval}}

# SigNoz 를 수집된 텔레메트리까지 통째로 제거
[group('observability')]
[confirm("SigNoz 와 저장된 모든 로그·트레이스·대시보드를 삭제합니다. 진행할까요?")]
signoz-uninstall:
    -helm uninstall signoz -n {{SIGNOZ_NS}}
    -kubectl delete pvc --all -n {{SIGNOZ_NS}}
    -kubectl delete namespace {{SIGNOZ_NS}}

# ── 학습 자료 ─────────────────────────────────────────────────────────────────

# Kubernetes·관측성 강의 슬라이드 열기 (오프라인 단일 파일)
[group('docs')]
slides:
    @open docs/education/k8s-observability-class.html 2>/dev/null \
      || xdg-open docs/education/k8s-observability-class.html 2>/dev/null \
      || echo "브라우저에서 여세요: docs/education/k8s-observability-class.html"

# 설치 가이드 목록
[group('docs')]
guides:
    @echo "docs/installs/k8s_install.md      로컬 Kubernetes 설치 (kind, kubectl, helm, k9s)"
    @echo "docs/installs/signoz_install.md   SigNoz 설치와 로그 검색"
    @echo "docs/education/                   강의 슬라이드 — 'just slides'"
