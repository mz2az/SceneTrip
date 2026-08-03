# Infrastructure: registry images, Terraform, and manifest rendering.
#
# The LOCAL kubernetes workflow (kind cluster, per-module deploy, logs, SigNoz)
# lives in tools/just/k8s.just. This file covers everything beyond the laptop.
#
# Every recipe that mutates shared or cloud state is [confirm]-gated and prints the
# target environment before acting. Valid ENV values: dev | staging | prod.

# Build every container image in the workspace via Bazel.
[group('infra')]
images:
    {{BAZEL}} build --config=release $({{BAZEL}} query "kind('oci_image', {{ALL}})" --output=label | tr '\n' ' ')

# Push a module's image to the shared registry.
[group('infra')]
[confirm("Push this image to the shared registry?")]
image-push module:
    {{BAZEL}} run //{{module}}:push

# Terraform: format and validate. Read-only, safe.
[group('infra')]
tf-check env="dev":
    ./tools/scripts/terraform.sh {{env}} fmt-check
    ./tools/scripts/terraform.sh {{env}} validate

# Terraform: show the plan for an environment. Read-only, safe.
[group('infra')]
tf-plan env="dev":
    ./tools/scripts/terraform.sh {{env}} plan

# Terraform: apply changes. MUTATES CLOUD STATE.
[group('infra')]
[confirm("This applies Terraform changes to real infrastructure. Continue?")]
tf-apply env:
    @echo ">>> TERRAFORM APPLY -> environment: {{env}}"
    ./tools/scripts/terraform.sh {{env}} apply

# Render Kubernetes manifests for an environment without applying them.
[group('infra')]
k8s-render env="dev":
    ./tools/scripts/k8s.sh {{env}} render

# Diff rendered manifests against the live cluster. Read-only.
[group('infra')]
k8s-diff env="dev":
    ./tools/scripts/k8s.sh {{env}} diff
