# SceneTrip documentation

Every document has exactly one home. Before creating a page, find its directory here —
do not add Markdown to the repository root.

| Directory | Contents | Typical readers |
| --- | --- | --- |
| [product/](product/) | vision, PRDs, requirements, personas, roadmap | everyone |
| [architecture/](architecture/) | system design, diagrams, data models | engineers |
| [architecture/adr/](architecture/adr/) | Architecture Decision Records | engineers |
| [api/](api/) | API usage guides and versioning policy | consumers |
| [engineering/](engineering/) | onboarding, Bazel, just, conventions | contributors |
| [installs/](installs/) | local environment setup: Kubernetes (kind), SigNoz | contributors |
| [education/](education/) | class material and slide decks | instructors, new joiners |
| [qa/](qa/) | test strategy, plans, coverage policy | engineers, QA |
| [ops/](ops/) | runbooks, SLOs, incidents, deployment | on-call |
| [ai/](ai/) | agent designs, prompts, evaluation results | agent engineers |
| [project/](project/) | plans, status, decision log, retrospectives | everyone |

Specifications are **not** documentation: protobuf, OpenAPI, AsyncAPI, and JSON Schema
live in [`contracts/`](../contracts/README.md) because they are build inputs.

## Conventions

- `kebab-case.md` filenames; numeric prefixes only where reading order matters.
- Every directory keeps a `README.md` index reflecting its real contents.
- Diagrams are Mermaid inside Markdown, so they diff.
- Write in the present tense about what exists. Move plans to `project/`.
- When code and docs disagree, fix both in the same commit.

```bash
just docs-lint      # style and link check
just adr-new "<title>"
just docs-serve
```
