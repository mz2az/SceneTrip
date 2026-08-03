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

log "$DIR 에서 $MODULE:$TAG 빌드"
docker build -t "$MODULE:$TAG" "$DIR"

log "$MODULE:$TAG 를 kind 노드에 적재"
kind load docker-image "$MODULE:$TAG" --name "$CLUSTER_NAME"

log "완료 — $MODULE:$TAG 를 클러스터 안에서 쓸 수 있습니다"
