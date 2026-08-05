#!/usr/bin/env bash
# 모듈 컨테이너 이미지를 빌드해 kind 노드에 적재한다.
# 사용법: image-build.sh <모듈이름> [태그]
# 호출: just image
#
# kind load 는 선택이 아니다. kind 노드는 자기 이미지 저장소를 따로 쓰므로 호스트에만
# 있는 이미지는 클러스터에서 보이지 않는다. 파드가 ErrImageNeverPull 로 멈추거나,
# 더 나쁘게는 이전 이미지가 조용히 계속 돈다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

MODULE="${1:?모듈 이름이 필요합니다. 예: scene-api}"
TAG="${2:-dev}"

DIR=""
for base in services apps agents; do
  [ -d "$base/$MODULE" ] && DIR="$base/$MODULE" && break
done
[ -n "$DIR" ] || die "모듈 '$MODULE' 을 services/·apps/·agents/ 에서 찾을 수 없습니다"
[ -f "$DIR/Dockerfile" ] || die "$DIR/Dockerfile 이 없습니다 — 이미지를 빌드하려면 먼저 추가하세요"

# Bazel 이 산출물을 만들고 도커는 그것을 담기만 한다 (ADR 0003).
#
# 빌드 컨텍스트를 모듈 폴더로 쓸 수 없다. Bazel 산출물은 bazel-bin/ 아래에 있는데
# 그것은 워크스페이스 밖(/private/var/tmp/…)을 가리키는 심볼릭 링크라, 도커가
# 컨텍스트 밖의 파일을 COPY 하지 못한다. 그래서 임시 디렉터리에 Dockerfile 과
# 산출물을 모아 거기서 빌드한다.
ARTIFACT="bazel-bin/$DIR/bin.jar"

if grep -q '^springboot(' "$DIR/BUILD.bazel" 2>/dev/null; then
  log "$DIR:bin 산출물 빌드"
  "${BAZEL:-bazel}" build "//$DIR:bin"
  [ -f "$ARTIFACT" ] || die "$ARTIFACT 이 만들어지지 않았습니다"

  CONTEXT="$(mktemp -d)"
  trap 'rm -rf "$CONTEXT"' EXIT
  cp "$DIR/Dockerfile" "$CONTEXT/"
  cp "$ARTIFACT" "$CONTEXT/bin.jar"
else
  # 아직 Bazel 산출물을 담는 형태가 아닌 모듈(예: 파이썬 에이전트)은 모듈 폴더를
  # 그대로 컨텍스트로 쓴다. 그 언어의 첫 이미지가 생길 때 여기를 넓힌다.
  CONTEXT="$DIR"
fi

log "$MODULE:$TAG 빌드 (컨텍스트: $CONTEXT)"
docker build -t "$MODULE:$TAG" "$CONTEXT"

log "$MODULE:$TAG 를 kind 노드에 적재"
kind load docker-image "$MODULE:$TAG" --name "$CLUSTER_NAME"

log "완료 — $MODULE:$TAG 를 클러스터 안에서 쓸 수 있습니다"
