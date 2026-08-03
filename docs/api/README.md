# API documentation

Guides for consuming SceneTrip APIs: authentication, pagination, error semantics, rate
limits, and versioning policy.

**Specifications live in [`contracts/`](../../contracts/README.md), not here.** This
directory explains how to use the APIs; `contracts/` defines them and is the build input.

| Document | Purpose |
| --- | --- |
| `versioning.md` | compatibility guarantees and deprecation timeline |
| `auth.md` | authentication and authorization model |
| `errors.md` | error envelope and status code semantics |
| `<service>-guide.md` | per-service usage guide with examples |

```bash
just docs-api    # render reference docs from contracts/
```
