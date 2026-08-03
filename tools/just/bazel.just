# 빌드 그래프 조작. 루트 justfile 이 import 한다.
# 이 저장소의 모든 Bazel 호출은 이런 레시피 안에서만 일어난다.

# 대상 빌드 (기본값: 워크스페이스 전체)
[group('build')]
build *targets=ALL:
    {{BAZEL}} build {{targets}}

# 모듈 하나만 빌드. 예: `just build-module services/scene-api`
[group('build')]
build-module module:
    {{BAZEL}} build //{{module}}/...

# 최적화된 릴리스 산출물 빌드
[group('build')]
build-release *targets=ALL:
    {{BAZEL}} build --config=release {{targets}}

# 바이너리 타깃 실행. 예: `just run //services/scene-api:bin -- --port=8080`
[group('build')]
run target *args:
    {{BAZEL}} run {{target}} {{args}}

# 빌드 그래프 질의. 예: `just query 'deps(//apps/web:web)'`
[group('build')]
query expr:
    {{BAZEL}} query "{{expr}}" --output=label

# 이 타깃에 의존하는 것들 — 바꿨을 때 영향받는 범위
[group('build')]
rdeps target:
    {{BAZEL}} query "rdeps({{ALL}}, {{target}})" --output=label

# 워크스페이스의 모든 바이너리·라이브러리 타깃 목록
[group('build')]
targets:
    {{BAZEL}} query "kind('.*_(binary|library)', {{ALL}})" --output=label

# 모든 테스트 타깃 목록
[group('build')]
test-targets:
    {{BAZEL}} query "kind('.*_test', {{ALL}})" --output=label

# 두 타깃 사이에 의존 관계가 왜 생겼는지 추적
[group('build')]
why from to:
    {{BAZEL}} query "somepath({{from}}, {{to}})" --output=graph

# 빌드 산출물 삭제
[group('build')]
clean:
    {{BAZEL}} clean

# 산출물과 분석 캐시까지 삭제. 느리다 — Bazel 이 이상하게 굴 때만 쓴다.
[group('build')]
[confirm("Bazel 출력 베이스를 통째로 지우고 전체 재빌드를 강제합니다. 진행할까요?")]
clean-all:
    {{BAZEL}} clean --expunge
