"""편의시설(POI) 파이프라인 — TMAP 수집 → 생존 대조 → `poi` 표 적재.

프로토타입(`apps/navi_proto`, navi-proto 브랜치)의 `collect_area.py`·`match_public.py` 를
옮긴 것이다. 표준 라이브러리만 쓴다 — Airflow 는 이 코드를 **부르는 쪽**이고 여기서
import 하지 않는다. 그래서 Bazel 검사가 네트워크·pip 없이 돈다.
"""
