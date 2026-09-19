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

# 명세(contracts/openapi/)를 엔드포인트별로 펼친 단일 HTML 을 만들어 브라우저로 연다.
# 생성물은 커밋하지 않는다 — 명세가 진실이고 문서는 그때그때 만든다.
#
# contracts/ 로부터 API 레퍼런스를 만들어 브라우저로 열기
[group('docs')]
docs-api:
    ./tools/scripts/docs-api.sh

# SceneTrip 로컬·AWS 강의, 발표 자료, 소스 기반 구성도와 출처 해시 생성
[group('docs')]
education-generate:
    {{BAZEL}} run //tools/education:education

# 체크인된 교육 자료와 구성도의 소스 변경 누락 검사 (파일을 수정하지 않음)
[group('docs')]
education-check:
    {{BAZEL}} run //tools/education:education -- --check

# 임시 복사본과 고정 도구로 실제 Terraform 엔진 DOT 그래프 생성 (AWS 접근 없음)
[group('docs')]
education-graph:
    {{BAZEL}} run //tools/education:terraform_graph

# 실제 Terraform 엔진 그래프와 도구·소스 출처가 현재 소스와 일치하는지 검사
[group('docs')]
education-graph-check:
    {{BAZEL}} run //tools/education:terraform_graph -- --check

# AWS 상세 강의 열기 (오프라인 단일 HTML)
[group('docs')]
education-course:
    @open docs/education/aws-eks-course.html 2>/dev/null || xdg-open docs/education/aws-eks-course.html 2>/dev/null || echo "브라우저에서 docs/education/aws-eks-course.html 을 여세요"

# AWS 발표 자료 열기 (키보드 탐색·목차·강사 노트·인쇄 지원)
[group('docs')]
education-slides:
    @open docs/education/aws-eks-presentation.html 2>/dev/null || xdg-open docs/education/aws-eks-presentation.html 2>/dev/null || echo "브라우저에서 docs/education/aws-eks-presentation.html 을 여세요"
