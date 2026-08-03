# Documentation authoring and validation. See AGENTS.md §8 for the docs layout.

# Lint all Markdown: style, dead links, orphaned files.
[group('docs')]
docs-lint:
    ./tools/scripts/docs-lint.sh

# Fix auto-fixable Markdown issues.
[group('docs')]
docs-fmt:
    ./tools/scripts/docs-lint.sh --fix

# Verify every docs/ directory has a README.md index and no orphan pages.
[group('docs')]
docs-check-index:
    ./tools/scripts/docs-index-check.sh

# Serve the documentation site locally for review.
[group('docs')]
docs-serve port="8000":
    ./tools/scripts/docs-serve.sh {{port}}

# Create a new Architecture Decision Record, e.g. `just adr-new "adopt bazel remote cache"`.
[group('docs')]
adr-new title:
    ./tools/scripts/new-adr.sh "{{title}}"

# List ADRs with their current status.
[group('docs')]
adr-list:
    @grep -H '^status:' docs/architecture/adr/*.md 2>/dev/null || echo "no ADRs yet"

# Regenerate API reference docs from contracts/.
[group('docs')]
docs-api:
    ./tools/scripts/docs-api.sh
