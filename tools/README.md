# tools/ — 빌드와 개발 도구

| 디렉터리 | 내용 |
| --- | --- |
| `bazel/defs/` | 재사용하는 Starlark 매크로와 규칙 |
| `bazel/toolchains/` | 격리된 툴체인 등록 |
| `just/` | 루트 `justfile` 이 import 하는 명령 모듈 |
| `scripts/` | just 레시피가 **호출하는** 셸 스크립트 |
| `templates/` | `just new-*` 가 렌더링하는 스캐폴딩 템플릿 |
| `ci/` | CI 보조 로직 |

## 규칙

- **justfile 은 명령을, 스크립트는 로직을 담는다.** 레시피 안의 셸이 5줄을 넘으면
  `scripts/` 로 옮긴다.
- **같은 Bazel 패턴이 세 번 나오면 매크로다.** `bazel/defs/` 에 둔다.
- 모든 스크립트는 `scripts/_lib.sh` 를 source 해 로그 헬퍼와 `REPO_ROOT` 를 얻는다.
- 모든 스크립트는 `set -euo pipefail` 이며 다시 실행해도 안전하다.
