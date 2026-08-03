# third_party/ — vendored code

Prefer a pinned dependency in `MODULE.bazel`. Vendor only when there is no alternative.

Every vendored directory carries a `PROVENANCE.md` recording:

- upstream URL and exact commit or version
- licence and its location in the tree
- every local modification, and why
- who to contact before upgrading

Unmodified vendored code is never edited in place — patch it in a build rule so upgrades
stay mechanical.
