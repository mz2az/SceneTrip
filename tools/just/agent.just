# AI 에이전트 모듈(agents/*) 관련 명령.
#
# 에이전트 코드도 애플리케이션 코드다 — Bazel 로 빌드하고 Bazel 로 테스트하며
# 나머지와 똑같이 just 로 실행한다. 평가(eval)는 테스트로 취급한다.

# 에이전트를 로컬 실행. 예: `just agent-run trip-planner -- --input=fixtures/a.json`
[group('agent')]
agent-run name *args:
    {{BAZEL}} run //agents/{{name}}:bin {{args}}

# 에이전트의 오프라인 평가 실행 (실제 모델 호출 없음)
[group('agent')]
agent-eval name:
    {{BAZEL}} test //agents/{{name}}:eval_test --test_output=streamed

# 실제 모델로 평가 실행. 비용이 발생하며 어떤 게이트에도 포함되지 않는다.
[group('agent')]
[confirm("실제 모델 제공자를 호출하며 비용이 발생합니다. 진행할까요?")]
agent-eval-live name:
    {{BAZEL}} test //agents/{{name}}:eval_live_test \
        --test_tag_filters=requires-network \
        --nocache_test_results --test_output=streamed

# 모든 에이전트 모듈을 오프라인으로 평가
[group('agent')]
agent-eval-all:
    {{BAZEL}} test //agents/... --test_tag_filters=eval

# 프롬프트 파일 검증 — 프런트매터, 필수 변수, 길이 제한
[group('agent')]
agent-lint-prompts:
    ./tools/scripts/prompt-lint.sh

# contracts/schemas/ 의 에이전트 도구 스키마와 구현이 일치하는지 검증
[group('agent')]
agent-check-tools:
    {{BAZEL}} test //tests/contract/... --test_tag_filters=agent-tools

# 두 평가 실행 결과를 비교 — 프롬프트·모델 변경이 실제로 나아졌는지 확인
[group('agent')]
agent-eval-diff name baseline:
    ./tools/scripts/eval-diff.sh {{name}} {{baseline}}

# 실제 모델로부터 픽스처를 녹화해 테스트를 결정적으로 만든다
[group('agent')]
[confirm("픽스처 녹화를 위해 실제 모델 제공자를 호출합니다. 진행할까요?")]
agent-record name:
    {{BAZEL}} run //agents/{{name}}:record_fixtures
