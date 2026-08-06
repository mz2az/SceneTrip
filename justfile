# SceneTrip 모노레포의 단일 명령 창구.
#
# 원칙: 사람이든 AI든 실행하는 모든 명령은 여기 레시피로 존재한다.
#       날것의 `bazel` 호출은 문서·스크립트·CI 어디에도 적지 않는다. (AGENTS.md §5)
#
# 요구사항: just 1.34 이상, bazelisk 를 `bazel` 이름으로 설치.
# 인자 없이 `just` 를 실행하면 모든 레시피를 그룹별로 보여준다.

set shell := ["bash", "-euo", "pipefail", "-c"]
set dotenv-load := true
set positional-arguments := true

# --- 공용 변수 ----------------------------------------------------------------

BAZEL       := env_var_or_default("BAZEL", "bazel")
ALL         := "//..."
REPO_ROOT   := justfile_directory()

# --- 명령 모듈 ----------------------------------------------------------------

import 'tools/just/bazel.just'
import 'tools/just/dev.just'
import 'tools/just/test.just'
import 'tools/just/docs.just'
import 'tools/just/infra.just'
import 'tools/just/k8s.just'
import 'tools/just/ci.just'
import 'tools/just/agent.just'
import 'tools/just/scaffold.just'

# --- 진입점 -------------------------------------------------------------------

# 사용 가능한 모든 명령 보기 (기본값)
default:
    @just --list --list-heading $'SceneTrip 명령 목록\n'

# PR 전 게이트. 작업을 넘기기 전에 반드시 실행하고 초록이어야 한다.
[group('gate')]
check: fmt-check lint build test
    @echo "check 통과 — 커밋해도 좋습니다"

# CI 순서 그대로에 **통합 레인을 더한** 것. 로컬에서만 가능한 검증이다.
#
# GitHub 러너에는 DB 가 없어 `just ci-full` 이 integration·e2e 를 제외한다
# (tools/just/ci.just). 그래서 이쪽이 CI 보다 넓다 — 노트북에는 `just cluster-up` 으로
# 띄운 실제 PostgreSQL 이 있으므로 SQL 까지 태워 본다. 클러스터가 없으면 실패한다.
[group('gate')]
ci: gen-check fmt-check lint build test test-integration
    @echo "ci 통과 — 통합 레인까지 돌았습니다 (CI 는 이 레인을 덮지 않습니다)"

# 이 워크스페이스가 실제로 쓰는 도구 버전 출력
[group('gate')]
versions:
    @echo "just   : $(just --version)"
    @echo "bazel  : $({{BAZEL}} --version 2>/dev/null || echo '없음')"
    @echo "고정값 : $(cat .bazelversion 2>/dev/null || echo '.bazelversion 없음')"
    @echo "git    : $(git --version)"
