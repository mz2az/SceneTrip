# contracts/schemas

JSON Schema and Avro definitions, including **AI agent tool schemas**.

Agent tools are a wire interface like any other: the schema is defined here, the agent
validates its arguments against it, and the implementing service validates again on
receipt. Never trust a model to produce a well-formed payload.
