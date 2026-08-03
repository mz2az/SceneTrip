# libs/ — shared libraries

Code imported by two or more modules. Grouped by language so Bazel toolchains and
Gazelle rules stay simple.

| Directory | Contents |
| --- | --- |
| `libs/go/` | Go packages shared by services |
| `libs/python/` | Python packages shared by services and agents |
| `libs/ts/` | TypeScript packages shared by apps |
| `libs/proto/` | shared Bazel proto libraries built from `contracts/proto` |

## Rules

- **Promotion, not duplication.** The moment a utility is needed by a second module, it
  moves here — copying it is a defect.
- A library has no knowledge of any specific service, app, or agent.
- A library never reaches back into `services/`, `apps/`, or `agents/`.
- Every library has tests. A library with no tests does not get imported.

```bash
just new-lib <lang> <name>
```
