"""POI 파이프라인 DAG — 매일 03:00 KST. 얇다: 태스크 넷이 `poi-pipeline` 명령을 부른다.

이 파일만 Airflow 를 import 한다. 태스크 본체(`poi_pipeline` 패키지)는 Airflow 를 모르고
Bazel 이 검사한다. 그래서 DAG 는 「무엇을 어떤 순서로, 어떤 한도 안에서」만 적는다.

Pool `tmap` = 하루 20,000 호출. 셀 단위 재개는 `poi_pipeline.grid.Ledger` 가 하므로 재시도가
같은 칸을 다시 부르지 않는다. 네이버 매칭은 여기 없다 — 카드는 scene-api 가 누를 때 채운다
(ADR 0011). 계획서: docs/project/plans/poi-pipeline.md
"""

from __future__ import annotations

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator

DATA = "{{ var.value.get('poi_data_dir', '/opt/airflow/data') }}"
REPO = "{{ var.value.get('scenetrip_repo', '/opt/airflow/scenetrip') }}"
PUBLIC_CSV = DATA + "/public_data/csv"
CLI = "python -m poi_pipeline.cli"

with DAG(
    dag_id="poi_daily",
    schedule="0 3 * * *",
    start_date=pendulum.datetime(2026, 9, 1, tz="Asia/Seoul"),
    catchup=False,
    max_active_runs=1,
    default_args={"retries": 2, "retry_delay": pendulum.duration(minutes=10)},
    tags=["poi", "tmap"],
) as dag:
    collect = [
        BashOperator(
            task_id=f"collect_{lane}",
            bash_command=f"{CLI} collect --group {group} --areas {areas} --data {DATA} --quota 6000",
            pool="tmap",
            env={"TMAP_APP_KEY": "{{ conn.tmap.password }}"},
            append_env=True,
        )
        for lane, group, areas in (
            ("stay", "숙박", "전국"),
            ("sight", "관광", "전국"),
            ("food", "음식", "서울 부산 경주 강릉 제주"),
        )
    ]
    alive = [
        BashOperator(
            task_id=f"alive_{lane}",
            bash_command=f"{CLI} alive --lane {lane} --data {DATA} --public-csv {PUBLIC_CSV}",
        )
        for lane in ("food", "stay")
    ]
    load = BashOperator(
        task_id="load_poi",
        # 대조가 건너뛰어져도(CSV 없음) 명소·교통은 적재한다 — 실패만 아니면 간다.
        trigger_rule="none_failed",
        bash_command=f"cd {REPO} && {CLI} load --data {DATA} --repo {REPO}",
        env={"SCENETRIP_DB_HOST": "{{ conn.scenetrip_db.host }}"},
        append_env=True,
    )
    report = BashOperator(task_id="report", bash_command=f"{CLI} report --data {DATA}")

    # 수집은 순서대로 — 동시에 부르면 TMAP 이 429 를 준다.
    collect[0] >> collect[1] >> collect[2] >> alive >> load >> report
