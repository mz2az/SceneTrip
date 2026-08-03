# 워크스테이션 세팅, 포맷, 린트, 코드 생성.

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

# BUILD 파일, protobuf 스텁, API 클라이언트, 목(mock) 재생성
[group('dev')]
gen:
    {{BAZEL}} run //:gazelle
    ./tools/scripts/generate.sh

# 생성물이 오래됐으면 실패. CI 가 쓴다.
[group('dev')]
gen-check: gen
    @git diff --exit-code -- . || { \
        echo "생성물이 최신이 아닙니다 — 'just gen' 을 실행하고 결과를 커밋하세요"; \
        exit 1; \
    }

# MODULE.bazel 을 고친 뒤 외부 의존성 잠금 파일 갱신
[group('dev')]
deps-update:
    {{BAZEL}} mod tidy

# 해석된 외부 의존성 그래프 출력
[group('dev')]
deps-graph:
    {{BAZEL}} mod graph
