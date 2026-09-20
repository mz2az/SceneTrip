# 워크스테이션 세팅, 포맷, 린트, 코드 생성.

# 작업 브랜치와 변경 파일 확인
[group('dev')]
repo-status:
    git status --short --branch

# 저장소 파일 목록 조회 (비밀값 파일은 제외)
[group('dev')]
repo-files *paths:
    rg --files --hidden -g '!.git' -g '!bazel-*' -g '!.env' -g '!*.tfstate*' "$@"

# 지정한 소스·문서 읽기
[group('dev')]
repo-read +paths:
    cat -- "$@"

# 저장소 소스·문서 검색
[group('dev')]
repo-search pattern +paths:
    rg -n -- "$@"

# macOS 검증에 필요한 Swift·Python·셸 검사 도구 설치
[group('dev')]
setup-linters:
    brew install swiftformat swiftlint ruff shellcheck

# 변경 내용의 공백 오류와 diff 확인
[group('dev')]
repo-diff:
    git diff --check
    git diff --stat

# 원격 저장소·브랜치·최근 커밋 확인
[group('dev')]
repo-info:
    git remote -v
    git branch -vv
    git log -8 --oneline

# origin/main의 최신 커밋 가져오기 (작업 파일은 변경하지 않음)
[group('dev')]
repo-fetch:
    git fetch origin main

# 최신 origin/main에서 새 작업 브랜치 시작
[group('dev')]
repo-start branch:
    git switch -c "$1" origin/main

# Git 이력·차이·객체 읽기 (조회 명령만 허용)
[group('dev')]
repo-inspect command +args:
    #!/usr/bin/env bash
    set -euo pipefail
    case "$1" in diff|log|show|rev-parse|merge-base|ls-files) git "$@" ;; *) echo '조회 명령만 사용할 수 있습니다' >&2; exit 1 ;; esac

# 검토한 파일을 다음 커밋에 포함
[group('dev')]
repo-stage +paths:
    git add -- "$@"

# 스테이징한 변경의 공백을 확인한 뒤 커밋
[group('dev')]
repo-commit message:
    git diff --cached --check
    git commit -m "$1"

# 현재 작업 브랜치를 origin에 게시하고 upstream 연결
[group('dev')]
[confirm("현재 브랜치를 origin에 게시합니다. 진행할까요?")]
repo-push:
    @git branch --show-current
    git push -u origin HEAD

# 최초 1회 워크스테이션 세팅. 멱등 — 여러 번 실행해도 안전하다.
[group('dev')]
setup:
    ./tools/scripts/bootstrap.sh

# 필요한 도구가 전부 있고 버전이 맞는지 확인
[group('dev')]
doctor:
    ./tools/scripts/doctor.sh

# 전부 포맷 — 소스 코드, BUILD 파일, 문서
[group('dev')]
fmt:
    {{BAZEL}} run //:buildifier
    ./tools/scripts/format.sh

# 포맷이 어긋난 게 있으면 실패. `just check` 와 CI 가 쓴다.
[group('dev')]
fmt-check:
    {{BAZEL}} run //:buildifier_check
    ./tools/scripts/format.sh --check

# 모든 린터와 정적 분석 실행
[group('dev')]
lint:
    {{BAZEL}} run //:buildifier_check
    ./tools/scripts/lint.sh

# 지정한 Python 경로의 자동 수정 가능한 린트 문제 정리
[group('dev')]
lint-python-fix +paths:
    ruff check --fix "$@"

# 지정한 Python 소스만 포맷
[group('dev')]
fmt-python +paths:
    ruff format "$@"

# macOS 유휴 절전을 억제한 동안 같은 전체 검증 실행 (완료하면 자동 해제)
[group('dev')]
check-awake:
    @if [ "$(uname -s)" = "Darwin" ]; then caffeinate -i just check; else just check; fi

# 이 저장소에는 pom.xml·build.gradle 이 없어(Bazel 로만 빌드한다) 자바 확장이 프로젝트를
# 인식하지 못하고, 정의로 가기(F12·Ctrl+클릭)가 동작하지 않는다. 소스 루트와 jar 목록을
# .vscode/settings.json 에 직접 써 준다.
#
# 경로에 기계마다 다른 Bazel output_base 가 들어가므로 커밋된 값을 쓰지 않고 매번
# 생성한다. 의존성이나 명세를 고친 뒤 다시 실행한다.
#
# VS Code 자바 확장이 읽을 클래스패스 생성 (정의로 가기가 안 될 때)
[group('dev')]
ide:
    ./tools/scripts/ide-vscode.sh

# protobuf 스텁, API 클라이언트, 목(mock) 재생성.
# BUILD 파일은 여기서 만들지 않는다 — 손으로 쓴다. 생성기를 아직 붙이지 않은 이유와
# 언어별 확장 후보는 MODULE.bazel 의 "BUILD 파일 생성" 절 참고.
[group('dev')]
gen:
    ./tools/scripts/generate.sh

# 생성물이 오래됐으면 실패. CI 가 쓴다.
[group('dev')]
gen-check: gen
    @git diff --exit-code -- . || { \
        echo "생성물이 최신이 아닙니다 — 'just gen' 을 실행하고 결과를 커밋하세요"; \
        exit 1; \
    }

# MODULE.bazel 을 고친 뒤 외부 의존성 잠금 파일 갱신.
#
# 잠금 파일이 셋이다. bzlmod 자체의 MODULE.bazel.lock 과, Maven 아티팩트의
# maven_install.json · maven_android_install.json 이다. 빠뜨리면 새 의존성이
# 해석되지 않아 빌드가 "찾을 수 없다" 로 죽는다 — 다 갱신하고 같은 커밋에 넣는다
# (AGENTS.md §4.4).
#
# **maven 설치본마다 pin 타깃이 따로 있다.** `@maven//:pin` 하나만 돌리면 서비스 쪽
# 잠금만 갱신되고 안드로이드 쪽은 낡은 채로 남는다. 저장소를 둘로 나눈 대가이며
# (MODULE.bazel 의 maven_android 절), 나눈 이유가 있으므로 합치지 말고 여기서 둘 다
# 부른다.
[group('dev')]
deps-update:
    {{BAZEL}} mod tidy
    @if grep -q 'name = "maven"' MODULE.bazel || grep -q 'maven.install(' MODULE.bazel; then \
        echo "==> Maven 아티팩트 재고정 (서비스)"; \
        REPIN=1 {{BAZEL}} run @maven//:pin; \
    fi
    @if grep -q 'name = "maven_android"' MODULE.bazel; then \
        echo "==> Maven 아티팩트 재고정 (Android)"; \
        REPIN=1 {{BAZEL}} run @maven_android//:pin; \
    fi

# 해석된 외부 의존성 그래프 출력
[group('dev')]
deps-graph:
    {{BAZEL}} mod graph
