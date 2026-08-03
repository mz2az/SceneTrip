# tools/bazel/toolchains

Hermetic toolchain declarations. Compilers, interpreters, and SDKs are downloaded and
pinned by Bazel — never taken from the host.

If a build succeeds only because something happens to be installed on the machine, the
toolchain is missing from this directory.
