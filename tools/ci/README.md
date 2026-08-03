# tools/ci/ — CI helper logic

Logic that supports the pipeline but is too specific for `tools/scripts/`.

GitHub Actions workflows stay thin: check out, install `just` and `bazelisk`, call a
recipe. Pipeline behavior lives in `tools/just/ci.just` and here, so the whole pipeline
remains runnable on a laptop via `just ci`.
