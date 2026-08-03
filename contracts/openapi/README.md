# contracts/openapi

REST API specifications, one file per service: `<service>-v<major>.yaml`.

- The spec is authored first and is authoritative; handlers implement it.
- Frontend clients are generated from these files — apps never hand-write clients.
- Breaking changes bump the major version and ship a new file.
