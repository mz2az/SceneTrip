# 문서 작성과 검증. 문서 배치 규칙은 AGENTS.md §8 참조.

# 모든 마크다운 린트 — 스타일, 죽은 링크, 인덱스에 없는 문서
[group('docs')]
docs-lint:
    ./tools/scripts/docs-lint.sh

# 자동으로 고칠 수 있는 마크다운 문제 수정
[group('docs')]
docs-fmt:
    ./tools/scripts/docs-lint.sh --fix

# docs/ 의 모든 디렉터리에 README.md 인덱스가 있는지, 고아 문서가 없는지 확인
[group('docs')]
docs-check-index:
    ./tools/scripts/docs-index-check.sh

# 문서 사이트를 로컬에서 띄워 확인
[group('docs')]
docs-serve port="8000":
    ./tools/scripts/docs-serve.sh {{port}}

# 새 아키텍처 결정 기록(ADR) 생성. 예: `just adr-new "Bazel 원격 캐시 도입"`
[group('docs')]
adr-new title:
    ./tools/scripts/new-adr.sh "{{title}}"

# ADR 목록과 각각의 상태 출력
[group('docs')]
adr-list:
    @grep -H '^status:' docs/architecture/adr/*.md 2>/dev/null || echo "아직 ADR 이 없습니다"

# contracts/ 로부터 API 레퍼런스 문서 재생성
[group('docs')]
docs-api:
    ./tools/scripts/docs-api.sh
