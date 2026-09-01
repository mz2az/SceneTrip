#!/bin/bash
# 숙박·관광이 끝나면 음식 수집을 이어받는다.
# 이미 훑은 칸(서울 음식점 168칸 등)은 기록이 있어 다시 부르지 않는다.
cd "$(dirname "$0")"
while pgrep -f "collect_area.py" >/dev/null; do sleep 20; done
echo "[$(date '+%H:%M:%S')] ───── 음식 이어받기 시작 ─────" >> collect.log
python3 collect_area.py --group 음식 --areas 서울 부산 경주 강릉 --step 0.25 --out poi_food_cities >> collect.log 2>&1
echo "[$(date '+%H:%M:%S')] ───── 음식 끝 ─────" >> collect.log
