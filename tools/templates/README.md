# tools/templates/ — 스캐폴딩 템플릿

`just new-*` 레시피를 통해 `tools/scripts/new-module.sh` 가 렌더링한다.

| 템플릿 | 사용처 |
| --- | --- |
| `module/README.md.tmpl` | 모든 새 모듈 |
| `module/BUILD.bazel.tmpl` | 모든 새 모듈 |
| `module/AGENT_CLAUDE.md.tmpl` | `just new-agent` |

자리표시자: `{{NAME}}`, `{{KIND}}`, `{{LANG}}`, `{{PATH}}`

템플릿은 AGENTS.md §3·§4.1 의 관례를 코드로 굳혀 둔 것이다. 관례를 바꾸면 같은 변경에서
템플릿도 고친다 — 그래야 새 모듈이 처음부터 규칙에 어긋난 채로 시작하지 않는다.
