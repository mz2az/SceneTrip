#!/usr/bin/env bash
# v2 시험 넷을 차례로 — 사이 2 분 쉼. 시각은 ~/results/v2-<이름>.start/.end
cd ~/loadtest/SceneTrip
ulimit -n 65535
for prof in baseline stress spike soak; do
  date -u +%Y-%m-%dT%H:%M:%SZ > ~/results/v2-$prof.start
  just load-run scenario.js --summary-export=/home/ubuntu/results/v2-$prof.json -e BASE_URL=https://api-dev.scenetrip.io/v1 -e TOKENS=/home/ubuntu/loadtest-tokens.json -e PROFILE=$prof > ~/results/v2-$prof.log 2>&1
  date -u +%Y-%m-%dT%H:%M:%SZ > ~/results/v2-$prof.end
  sleep 120
done
date -u +%Y-%m-%dT%H:%M:%SZ > ~/results/v2-all.end
