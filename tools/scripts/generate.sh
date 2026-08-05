#!/usr/bin/env bash
# 계약(contracts/)에서 파생되는 코드 생성: protobuf 스텁, API 클라이언트, 목(mock).
# BUILD 파일은 여기서 만들지 않는다 — 손으로 쓴다(AGENTS.md §4.5).
# 호출: just gen
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

# 생성된 스텁은 Bazel 산출물이지 커밋 대상이 아니다. 이 스크립트는 Bazel 이 소유할 수
# 없는 생성(예: 외부 사용자용 OpenAPI 클라이언트)을 위해 존재한다.

if compgen -G "contracts/proto/**/*.proto" >/dev/null 2>&1; then
  log "proto 정의 발견 — 스텁은 빌드 시점에 Bazel proto 규칙이 만듭니다"
fi

if compgen -G "contracts/openapi/*.yaml" >/dev/null 2>&1; then
  log "OpenAPI 명세 발견 — contracts/openapi/ 의 생성 타깃을 빌드합니다"
  # 생성기는 코드를 뽑기 전에 명세를 파싱·검증한다. 그래서 이 빌드가 곧 명세
  # 검사이기도 하다. `just check` 의 `bazel build //...` 에도 이미 포함돼 있다.
  "${BAZEL:-bazel}" build //contracts/openapi/...
fi

log "생성 완료"
