# Airflow 이미지 — 공식 이미지에 둘을 얹는다.
#   psql           적재(tools/scripts/seed-poi.sh 의 직접 접속 경로)가 쓴다
#   poi_pipeline   services/poi-pipeline 의 패키지. 파이썬 표준 라이브러리만 쓰므로 pip 없음
# 태그·버전은 tools/scripts/airflow.sh 와 platform/kubernetes/airflow/values.yaml 이 같이 본다.
FROM apache/airflow:2.10.5-python3.12

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-client \
    && rm -rf /var/lib/apt/lists/*
USER airflow

# 적재 스크립트와 변환 SQL — 저장소 경로를 그대로 둔다(스크립트가 상대 경로로 SQL 을 찾는다).
COPY --chown=airflow tools/scripts/_lib.sh tools/scripts/seed-poi.sh /opt/airflow/scenetrip/tools/scripts/
COPY --chown=airflow services/scene-api/seed/poi.sql /opt/airflow/scenetrip/services/scene-api/seed/poi.sql
COPY --chown=airflow services/poi-pipeline/src/poi_pipeline /opt/airflow/poi_pipeline
ENV PYTHONPATH=/opt/airflow
