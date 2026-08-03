#!/usr/bin/env bash
# Build a module's container image and load it into the kind node.
# Usage: image-build.sh <module-name> [tag]
# Invoked by: just image
#
# The `kind load` step is not optional. kind nodes keep their own image store, so
# an image that exists on the host is invisible to the cluster; the pod fails with
# ErrImageNeverPull, or worse, silently keeps running the previous image.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

MODULE="${1:?module name required, e.g. scene-api}"
TAG="${2:-dev}"

DIR=""
for base in services apps agents; do
  [ -d "$base/$MODULE" ] && DIR="$base/$MODULE" && break
done
[ -n "$DIR" ] || die "module '$MODULE' not found under services/, apps/, or agents/"
[ -f "$DIR/Dockerfile" ] || die "$DIR/Dockerfile does not exist — add one before building an image"

log "building $MODULE:$TAG from $DIR"
docker build -t "$MODULE:$TAG" "$DIR"

log "loading $MODULE:$TAG into kind node"
kind load docker-image "$MODULE:$TAG" --name "$CLUSTER_NAME"

log "done — $MODULE:$TAG is available inside the cluster"
