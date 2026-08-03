# tools/templates/ — scaffolding templates

Rendered by `tools/scripts/new-module.sh` via the `just new-*` recipes.

| Template | Used by |
| --- | --- |
| `module/README.md.tmpl` | every new module |
| `module/BUILD.bazel.tmpl` | every new module |
| `module/AGENT_CLAUDE.md.tmpl` | `just new-agent` |

Placeholders: `{{NAME}}`, `{{KIND}}`, `{{LANG}}`, `{{PATH}}`.

Templates encode the conventions in AGENTS.md §3 and §4.1. Updating a convention means
updating the template in the same change, so new modules never start out non-compliant.
