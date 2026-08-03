# tools/bazel/defs

Reusable Starlark macros. Each `.bzl` file documents its macros with docstrings.

Candidates that belong here as the repo grows: a service macro bundling
library + binary + image + tests with the standard names and tags, a proto bundle macro,
and a lint aspect.
