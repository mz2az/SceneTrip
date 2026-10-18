# tests/load

처리량·지연·장시간 테스트. 어떤 게이트에도 없고 의도적으로만 실행한다. 계획: [load-test.md](../../docs/project/plans/load-test.md) (MZ2AZ-502),
기록: [docs/qa/loadtest/](../../docs/qa/loadtest/README.md).

| 파일 | 내용 |
| --- | --- |
| `BUILD.bazel` | `:k6` — MODULE.bazel 에 버전·SHA-256 을 고정한 k6(맥 arm64·리눅스 amd64) |
| `smoke.js` | 연기 확인 — 1 명 10 초, 대상과 붙어 있는지만 |

실행: `just load-run smoke.js -e BASE_URL=https://api-dev.scenetrip.io/v1`

레인 규율과 태그 규칙은 [tests/README.md](../README.md) 참조.
