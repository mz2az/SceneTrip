# contracts/ — interface source of truth

Every wire interface in SceneTrip is defined here, by hand, before it is implemented.
Generated stubs and clients are Bazel build outputs — they are never committed.

| Directory | Contents |
| --- | --- |
| `proto/` | gRPC services and protobuf messages |
| `openapi/` | REST API specifications |
| `asyncapi/` | event and stream specifications |
| `schemas/` | JSON Schema / Avro, including AI agent tool schemas |

## The contract-first rule

When a wire format changes:

```
1. edit contracts/          2. just gen          3. implement against the stubs
```

Never the reverse. An implementation that drifts from its contract is a defect in the
implementation.

## Compatibility

- Breaking changes require a new version directory (`scene/v1` → `scene/v2`), never an
  edit in place.
- Removing or renumbering a protobuf field is breaking. Reserve, do not reuse.
- Every contract change is validated by `just test-contract`.

```bash
just new-contract <kind> <name>
just gen
just test-contract
```
