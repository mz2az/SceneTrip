# tools/ci/ — CI 보조 로직

파이프라인을 돕지만 `tools/scripts/` 에 두기엔 CI 에 특화된 로직.

GitHub Actions 워크플로는 얇게 유지한다 — 체크아웃, `just` 와 `bazelisk` 설치,
레시피 호출. 파이프라인 동작은 `tools/just/ci.just` 와 여기에 있으므로
`just ci` 로 파이프라인 전체를 노트북에서 그대로 돌릴 수 있다.
