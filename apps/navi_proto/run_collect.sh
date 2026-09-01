#!/bin/bash
# 숙박 → 관광 → 음식(이어받기) 순으로 돈다. 동시에 돌리면 TMAP 이 429 를 준다.
# 격자는 0.4도 — 반지름 29 km 로 TMAP 상한(33 km) 안에 든다.
cd "$(dirname "$0")"
L=collect.log
say(){ echo "[$(date '+%H:%M:%S')] $*" >> "$L"; }
say "───── 숙박 (전국) ─────"
python3 collect_area.py --group 숙박 --areas 전국 --step 0.4 --out poi_stay >>"$L" 2>&1
say "───── 관광 (전국) ─────"
python3 collect_area.py --group 관광 --areas 전국 --step 0.4 --out poi_sight >>"$L" 2>&1
say "───── 음식 이어받기 ─────"
python3 collect_area.py --group 음식 --areas 서울 부산 경주 강릉 --step 0.25 --out poi_food_cities >>"$L" 2>&1
say "───── 전부 끝 ─────"
