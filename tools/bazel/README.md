# tools/bazel/ — Bazel extensions

| Directory | Contents |
| --- | --- |
| `defs/` | reusable Starlark macros and rules shared across modules |
| `toolchains/` | hermetic toolchain declarations and registrations |

Write a macro here rather than copy-pasting rule invocations into a third `BUILD.bazel`.
Macros keep target naming and tagging consistent, which is what makes target labels
predictable across the repo (AGENTS.md §4.1).
