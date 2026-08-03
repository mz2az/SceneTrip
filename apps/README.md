# apps/ — frontend applications

One directory per independently deployable user-facing application (web, admin, mobile).

```
apps/<name>/
├── BUILD.bazel   required
├── README.md     required — purpose, target users, backends consumed
├── src/
└── tests/
```

## Rules

- An app **never hand-writes an API client**. Clients are generated from `contracts/`.
- Shared UI primitives and utilities live in `libs/ts/`, not copied between apps.
- Design tokens are defined once and imported; no per-app palette drift.
- Visual regression and accessibility checks are part of the test lanes, not optional.

## Commands

```bash
just new-app <name> [lang]
just build-module apps/<name>
just test-module  apps/<name>
just run //apps/<name>:dev
```
