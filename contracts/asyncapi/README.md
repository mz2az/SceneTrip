# contracts/asyncapi

Event and stream specifications: topics, payload schemas, delivery semantics.

- Document ordering guarantees, retry behavior, and dead-letter handling per channel.
- Producers and consumers are both validated against these specs by `just test-contract`.
- Payload evolution follows the same compatibility rules as protobuf: additive only.
