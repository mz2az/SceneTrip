# 새 모듈 생성.
#
# 모듈은 항상 이 레시피로 만든다. tools/templates/ 의 템플릿을 렌더링하므로
# 모든 모듈이 BUILD.bazel, README.md, 테스트 타깃, 올바른 태그를 갖고 시작한다.
# 그래야 그래프가 균일하게 유지되고, 사람도 AI 도 모듈 구조를 예측할 수 있다.
#
# 허용 언어는 확정된 스택뿐이다: java(Spring Boot 백엔드) · python(AI 에이전트) ·
# swift(iOS) · kotlin(Android). new-module.sh 가 그 밖의 값을 거부한다.

# 새 백엔드 서비스 생성 (Spring Boot). 예: `just new-service scene-api`
[group('scaffold')]
new-service name lang="java":
    ./tools/scripts/new-module.sh service {{name}} {{lang}}
    @echo "services/{{name}} 생성됨 — 다음: just build-module services/{{name}}"

# 새 iOS 앱 생성 (Swift). 예: `just new-app-ios trip-ios`
[group('scaffold')]
new-app-ios name:
    ./tools/scripts/new-module.sh app {{name}} swift
    @echo "apps/{{name}} 생성됨 — 다음: just build-module apps/{{name}}"

# 새 Android 앱 생성 (Kotlin). 예: `just new-app-android trip-android`
[group('scaffold')]
new-app-android name:
    ./tools/scripts/new-module.sh app {{name}} kotlin
    @echo "apps/{{name}} 생성됨 — 다음: just build-module apps/{{name}}"

# 새 AI 에이전트 모듈 생성 (Python). 예: `just new-agent trip-planner`
[group('scaffold')]
new-agent name lang="python":
    ./tools/scripts/new-module.sh agent {{name}} {{lang}}
    @echo "agents/{{name}} 생성됨 — 다음: just build-module agents/{{name}}"

# 새 공유 라이브러리 생성. 예: `just new-lib java telemetry`
[group('scaffold')]
new-lib lang name:
    ./tools/scripts/new-module.sh lib {{name}} {{lang}}
    @echo "libs/{{lang}}/{{name}} 생성됨"

# 새 계약 정의 생성. 예: `just new-contract proto scene/v1`
[group('scaffold')]
new-contract kind name:
    ./tools/scripts/new-contract.sh {{kind}} {{name}}

# 워크스페이스의 모든 모듈을 분류별로 나열
[group('scaffold')]
modules:
    @echo "services:"; ls -1 services 2>/dev/null | grep -v README || true
    @echo "apps:";     ls -1 apps     2>/dev/null | grep -v README || true
    @echo "agents:";   ls -1 agents   2>/dev/null | grep -v README || true
