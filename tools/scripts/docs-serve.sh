#!/usr/bin/env bash
# docs/만 127.0.0.1에서 제공한다. 저장소 루트와 비밀값은 제공하지 않는다.
# 호출: just docs-serve [port]
set -euo pipefail
just run //tools/education:serve -- --port "${1:-8000}"
