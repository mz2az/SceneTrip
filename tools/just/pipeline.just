# 데이터 파이프라인 — POI 수집·대조·적재와 Airflow (services/poi-pipeline, MZ2AZ-315)

POI_DATA := env_var_or_default("POI_DATA_DIR", "apps/navi_proto/local_data")
AIRFLOW_NS := "airflow"
AIRFLOW_CHART_VERSION := "1.16.0"

# TMAP 격자 수집을 맥에서 바로. 예: just poi-collect 숙박 전국 · just poi-collect 음식 "서울 제주"
[group('pipeline')]
poi-collect group areas="전국" quota="6000":
    {{BAZEL}} run //services/poi-pipeline:bin -- collect --group {{group}} --areas {{areas}} \
        --data "$(pwd)/{{POI_DATA}}" --quota {{quota}}

# 공공데이터(상가정보)로 생존 대조. 예: just poi-alive food
[group('pipeline')]
poi-alive lane="food":
    {{BAZEL}} run //services/poi-pipeline:bin -- alive --lane {{lane}} \
        --data "$(pwd)/{{POI_DATA}}" --public-csv "$(pwd)/{{POI_DATA}}/public_data/csv"

# 생존분을 poi 표에 적재 (kind 파드 또는 SCENETRIP_DB_HOST). 예: just poi-load "food stay"
[group('pipeline')]
poi-load lanes="food stay sight transit":
    {{BAZEL}} run //services/poi-pipeline:bin -- load --data "$(pwd)/{{POI_DATA}}" \
        --repo "$(pwd)" --lanes {{lanes}}

# 갈래별 원본·생존 건수 한 줄
[group('pipeline')]
poi-report:
    @{{BAZEL}} run //services/poi-pipeline:bin -- report --data "$(pwd)/{{POI_DATA}}"

# Airflow 이미지(공식 + psql + 이 패키지)를 만들어 kind 에 싣는다
[group('pipeline')]
airflow-image:
    ./tools/scripts/airflow.sh image

# kind 클러스터에 Airflow 를 올린다 (Helm, 차트 {{AIRFLOW_CHART_VERSION}}). 메모리 2~3 GB — 켤 때만
[group('pipeline')]
[confirm("kind 클러스터에 Airflow 를 설치합니다 (네임스페이스 airflow). 진행할까요?")]
airflow-up: airflow-image
    ./tools/scripts/airflow.sh up {{AIRFLOW_CHART_VERSION}}

# DAG 와 파이프라인 코드를 ConfigMap 으로 다시 올린다 (코드를 고친 뒤)
[group('pipeline')]
airflow-dags:
    ./tools/scripts/airflow.sh dags

# 데이터 디렉터리(원본 JSONL·공공데이터 CSV)를 Airflow 의 PVC 로 복사한다
[group('pipeline')]
airflow-data:
    ./tools/scripts/airflow.sh data "{{POI_DATA}}"

# Airflow UI 를 localhost:8082 로 포워딩 (Ctrl+C 로 중지). 계정은 airflow.sh 머리말
[group('pipeline')]
airflow-open:
    ./tools/scripts/airflow.sh open

# Helm 릴리스와 파드 상태
[group('pipeline')]
airflow-status:
    -@helm list -n {{AIRFLOW_NS}}
    @kubectl get pods -n {{AIRFLOW_NS}}

# DAG 를 한 번 수동 실행. 예: just airflow-trigger poi_daily
[group('pipeline')]
airflow-trigger dag="poi_daily":
    ./tools/scripts/airflow.sh trigger {{dag}}

# Airflow 를 0 으로 줄여 메모리 회수 (SigNoz 와 같은 방식)
[group('pipeline')]
airflow-pause:
    kubectl scale statefulset,deployment --all --replicas=0 -n {{AIRFLOW_NS}}

# 일시 중지한 Airflow 를 다시 깨운다
[group('pipeline')]
airflow-resume:
    kubectl scale statefulset,deployment --all --replicas=1 -n {{AIRFLOW_NS}}

# Airflow 를 제거한다 (PVC 의 데이터는 남는다)
[group('pipeline')]
[confirm("네임스페이스 airflow 의 Helm 릴리스를 제거합니다. 진행할까요?")]
airflow-down:
    helm uninstall airflow -n {{AIRFLOW_NS}}
