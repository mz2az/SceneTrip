# Engineering documentation

How to work in this repository.

| Document | Purpose |
| --- | --- |
| `onboarding.md` | day one: setup, first build, first change |
| `bazel-guide.md` | build graph, target naming, tags, hermeticity, troubleshooting |
| `just-guide.md` | the command surface and how to extend it |
| `git-workflow.md` | branching, commits, review, merge |
| `coding-standards.md` | language conventions and quality bars |
| `troubleshooting.md` | common build and test failures with fixes |

The authoritative rules are in [AGENTS.md](../../AGENTS.md); pages here explain and
demonstrate them. When the two disagree, AGENTS.md wins and the page is fixed.

## Quick start

```bash
just setup     # bootstrap
just doctor    # verify tooling
just --list    # discover commands
just check     # the gate every change must pass
```
