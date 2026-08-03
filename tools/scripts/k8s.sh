#!/usr/bin/env bash
# Kubernetes manifest rendering and diffing.
# Invoked by: just k8s-render / k8s-diff
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

pending "kubernetes manifests not defined — see platform/kubernetes/README.md"
