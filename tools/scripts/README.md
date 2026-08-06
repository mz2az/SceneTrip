# tools/scripts/ — 스크립트 모음

just 레시피가 호출하는 스크립트. 기여자가 직접 실행하지 않는다.

## 관례

- `source _lib.sh` 로 `log`·`warn`·`die`·`have`·`pending`·`REPO_ROOT` 와
  Kubernetes 컨텍스트 가드를 얻는다.
- `set -euo pipefail` (`_lib.sh` 에서 상속).
- 멱등 — 두 번 실행해도 안전하다.
- 절대 경로를 쓰지 않는다. 전부 `REPO_ROOT` 기준으로 해석한다.
  - 예외는 `ide-vscode.sh` 하나다. VS Code 자바 확장이 워크스페이스 바깥의 jar 를
    상대 경로로 받지 못해 Bazel `output_base` 의 절대 경로를 써야 한다. 그래서 그
    결과물(`.vscode/settings.json`)은 커밋하지 않고 각자 `just ide` 로 만든다.
- CI 를 막는 스크립트는 실패 시 0 이 아닌 코드로 끝나고 이유를 출력한다.

`pending: …` 을 출력하는 스크립트는 아직 도구를 정하지 않은 영역의 **의도적 자리표시자**다.
게이트를 초록으로 유지하기 위해 0 으로 끝나며, 각자 무엇을 연결해야 하는지 밝힌다.
