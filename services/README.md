# services/ — backend servers

One directory per independently deployable backend. Multiple services are expected;
they are peers, never nested.

```
services/<name>/
├── BUILD.bazel   required
├── README.md     required — purpose, port, contracts, dependencies
├── src/
├── tests/
└── deploy/
```

## Rules

- A service exposes its interface through `contracts/` (proto / OpenAPI / AsyncAPI).
- A service **never** imports another service's source. Cross-service calls go over
  the wire, against a contract.
- Shared code moves to `libs/<lang>/`, it is not copied.
- A service owns its data. No shared database tables across service boundaries.

## Commands

```bash
just new-service <name> [lang]     # scaffold
just build-module services/<name>
just test-module  services/<name>
just run //services/<name>:bin
just image services/<name>
```
