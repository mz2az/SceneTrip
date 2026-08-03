# Scaffolding for new modules.
#
# Always create modules through these recipes. They render the templates in
# tools/templates/, so every module starts with a BUILD.bazel, a README.md, a
# test target, and the correct tags — which keeps the graph uniform and makes
# module layout predictable for both humans and agents.

# Create a new backend service, e.g. `just new-service scene-api go`.
[group('scaffold')]
new-service name lang="go":
    ./tools/scripts/new-module.sh service {{name}} {{lang}}
    @echo "created services/{{name}} — now run: just build-module services/{{name}}"

# Create a new frontend app, e.g. `just new-app web ts`.
[group('scaffold')]
new-app name lang="ts":
    ./tools/scripts/new-module.sh app {{name}} {{lang}}
    @echo "created apps/{{name}} — now run: just build-module apps/{{name}}"

# Create a new AI agent module, e.g. `just new-agent trip-planner python`.
[group('scaffold')]
new-agent name lang="python":
    ./tools/scripts/new-module.sh agent {{name}} {{lang}}
    @echo "created agents/{{name}} — now run: just build-module agents/{{name}}"

# Create a new shared library, e.g. `just new-lib go telemetry`.
[group('scaffold')]
new-lib lang name:
    ./tools/scripts/new-module.sh lib {{name}} {{lang}}
    @echo "created libs/{{lang}}/{{name}}"

# Create a new contract definition, e.g. `just new-contract proto scene/v1`.
[group('scaffold')]
new-contract kind name:
    ./tools/scripts/new-contract.sh {{kind}} {{name}}

# List every module in the workspace by category.
[group('scaffold')]
modules:
    @echo "services:"; ls -1 services 2>/dev/null | grep -v README || true
    @echo "apps:";     ls -1 apps     2>/dev/null | grep -v README || true
    @echo "agents:";   ls -1 agents   2>/dev/null | grep -v README || true
