# Local Kubernetes (kind) and observability (SigNoz).
#
# Guides:  docs/installs/k8s_install.md · docs/installs/signoz_install.md
# Class:   docs/education/k8s-observability-class.html  (`just slides`)
#
# Every recipe here targets the local kind cluster only. The scripts refuse to run
# against any other context — a rule a person has to remember every time is not a
# rule, so it is enforced instead of documented.

CLUSTER    := "scenetrip"
K8S_NS     := "scenetrip"
SIGNOZ_NS  := "signoz"
API_URL    := "http://localhost:8081"
SIGNOZ_URL := "http://localhost:8080"

# ── cluster ───────────────────────────────────────────────────────────────────

# Create the kind cluster and install SigNoz. Idempotent — safe to re-run.
[group('k8s')]
cluster-up:
    ./tools/scripts/cluster-up.sh

# Bring up the whole local environment. Today that is the cluster and SigNoz.
[group('k8s')]
up: cluster-up
    @echo "ready — 'just signoz' for the UI, 'just cluster-doctor' for status"

# Delete the cluster. Collected telemetry and any database data go with it.
[group('k8s')]
[confirm("This deletes the kind cluster along with all collected telemetry. Continue?")]
cluster-down:
    kind delete cluster --name {{CLUSTER}}

# Switch kubectl to the local cluster.
[group('k8s')]
cluster-context:
    kubectl config use-context kind-{{CLUSTER}}
    @kubectl config current-context

# Tools, cluster, SigNoz, and workloads in one screen.
[group('k8s')]
cluster-doctor:
    ./tools/scripts/cluster-doctor.sh

# Everything running in the application namespace.
[group('k8s')]
cluster-status:
    kubectl get all -n {{K8S_NS}}

# Prove the cluster works: deploy nginx, call it, clean up.
[group('k8s')]
cluster-test-drive:
    ./tools/scripts/cluster-test-drive.sh

# ── application deployment ────────────────────────────────────────────────────

# The kind load step is what makes the image visible to the cluster. Skipping it is
# the most common local-Kubernetes mistake: nothing errors, the old image just
# keeps running.
#
# Build a module image and load it into the kind node, e.g. `just image scene-api`.
[group('k8s')]
image module tag="dev":
    ./tools/scripts/image-build.sh {{module}} {{tag}}

# Apply a module's manifests and wait for the rollout, e.g. `just deploy scene-api local`.
[group('k8s')]
deploy module env="local":
    ./tools/scripts/deploy.sh {{module}} {{env}}

# Rebuild, reload, and restart a module after a code change.
[group('k8s')]
update module:
    ./tools/scripts/image-build.sh {{module}}
    kubectl rollout restart deployment/{{module}} -n {{K8S_NS}}
    kubectl rollout status  deployment/{{module}} -n {{K8S_NS}} --timeout=180s

# Roll a module back to its previous revision.
[group('k8s')]
[confirm("This rolls back a running deployment. Continue?")]
rollback module env="local":
    ./tools/scripts/deploy.sh {{module}} {{env}} --rollback

# Remove a module from the cluster, leaving the cluster and SigNoz in place.
[group('k8s')]
undeploy module:
    kubectl delete -f platform/kubernetes/{{module}} -n {{K8S_NS}} --ignore-not-found

# Follow a module's logs, e.g. `just logs scene-api`.
[group('k8s')]
logs module:
    kubectl logs -f deployment/{{module}} -n {{K8S_NS}}

# Open a shell in a module's pod.
[group('k8s')]
pod-shell module:
    kubectl exec -it deployment/{{module}} -n {{K8S_NS}} -- /bin/sh

# Liveness and readiness of the deployed API.
[group('k8s')]
health:
    @curl -sS {{API_URL}}/actuator/health/liveness || echo "no response from {{API_URL}}"
    @echo
    @curl -sS {{API_URL}}/actuator/health/readiness || true
    @echo

# ── observability ─────────────────────────────────────────────────────────────

# Where the SigNoz UI is and what to filter on.
[group('observability')]
signoz:
    @echo "SigNoz UI : {{SIGNOZ_URL}}"
    @echo "filter    : service.name = scenetrip-<module>"
    @echo "no port-forward needed — NodePort 30080 is mapped to host 8080"

# Open the SigNoz UI in a browser.
[group('observability')]
signoz-open:
    @open {{SIGNOZ_URL}} 2>/dev/null || xdg-open {{SIGNOZ_URL}} 2>/dev/null || echo "{{SIGNOZ_URL}}"

# Helm release and pod status. Pods Running with migrator Completed is healthy.
[group('observability')]
signoz-status:
    -@helm list -n {{SIGNOZ_NS}}
    @kubectl get pods -n {{SIGNOZ_NS}}

# Scale SigNoz to zero to reclaim memory. Data and settings are kept.
[group('observability')]
signoz-pause:
    kubectl scale statefulset,deployment --all --replicas=0 -n {{SIGNOZ_NS}}

# Bring SigNoz back up after a pause.
[group('observability')]
signoz-resume:
    kubectl scale statefulset,deployment --all --replicas=1 -n {{SIGNOZ_NS}}

# Occupies the terminal; Ctrl+C to stop.
#
# Forward the OTLP collector so an app on the host can send telemetry.
[group('observability')]
signoz-forward:
    @echo "OTLP endpoint -> localhost:4317 (grpc) / localhost:4318 (http)"
    kubectl port-forward -n {{SIGNOZ_NS}} svc/signoz-ingester 4317:4317 4318:4318

# Query ClickHouse directly to confirm telemetry landed.
[group('observability')]
signoz-verify service="scenetrip-scene-api" minutes="10":
    ./tools/scripts/signoz-verify.sh {{service}} {{minutes}}

# Watch log ingestion counts on an interval. Ctrl+C to stop.
[group('observability')]
signoz-watch service="scenetrip-scene-api" interval="5":
    ./tools/scripts/signoz-watch.sh {{service}} {{interval}}

# Remove SigNoz completely, including collected telemetry.
[group('observability')]
[confirm("This deletes SigNoz and every log, trace, and dashboard it stored. Continue?")]
signoz-uninstall:
    -helm uninstall signoz -n {{SIGNOZ_NS}}
    -kubectl delete pvc --all -n {{SIGNOZ_NS}}
    -kubectl delete namespace {{SIGNOZ_NS}}

# ── learning material ─────────────────────────────────────────────────────────

# Open the Kubernetes + observability class slides (offline, single file).
[group('docs')]
slides:
    @open docs/education/k8s-observability-class.html 2>/dev/null \
      || xdg-open docs/education/k8s-observability-class.html 2>/dev/null \
      || echo "open in a browser: docs/education/k8s-observability-class.html"

# List the setup guides.
[group('docs')]
guides:
    @echo "docs/installs/k8s_install.md      local Kubernetes setup (kind, kubectl, helm, k9s)"
    @echo "docs/installs/signoz_install.md   SigNoz install and log search"
    @echo "docs/education/                   class slides — 'just slides'"
