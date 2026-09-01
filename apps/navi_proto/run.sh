#!/usr/bin/env bash
# 프로토타입 서버를 띄운다.
#
# .env 는 서버가 요청마다 직접 읽는다. 그래서 키를 넣고 화면만 새로 고치면
# 바로 반영된다 — 서버를 껐다 켜지 않아도 된다.
set -euo pipefail
cd "$(dirname "$0")"
exec python3 server.py
