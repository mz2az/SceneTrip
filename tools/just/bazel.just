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

# iOS 앱을 시뮬레이터에 띄운다. `.env` 의 네이버 클라이언트 ID 를 빌드에 주입한다
# (dotenv-load 가 루트 justfile 에 켜져 있다). ID 가 없으면 빈 값으로 지어지고
# 지도만 인증 실패 화면이 된다 — .bazelrc 의 같은 이름 절 참고.
#
# 러너가 앱 로그를 붙잡고 있으므로 프롬프트가 바로 돌아오지 않는다. Ctrl+C 로
# 빠져나와도 시뮬레이터의 앱은 별도 프로세스라 그대로 살아 있다.

# iOS 앱을 시뮬레이터에 띄운다 (실기기는 just ios-xcode)
[group('build')]
ios-run:
    #!/usr/bin/env bash
    set -euo pipefail
    # LLM 설정의 단일 출처는 apps/navi_proto/.env 다 — server.py·llm.sh 가 읽는 그 파일.
    # 셸 변수가 있으면 그것이 우선(server.py 와 같은 규칙), 없으면 .env, 그것도 없으면 빈 값.
    llm() { printenv "$1" 2>/dev/null || { sed -n "s/^$1=//p" apps/navi_proto/.env 2>/dev/null | tail -1; } || true; }
    {{BAZEL}} run //apps/scenetrip-ios:bin \
        --define=naver_client_id="${NAVER_MAP_CLIENT_ID:-}" \
        --define=kakao_rest_key="${KAKAO_REST_KEY:-}" \
        --define=llm_url="$(llm LLM_URL)" \
        --define=llm_model="$(llm LLM_MODEL)" \
        --define=llm_api_key="$(llm LLM_API_KEY)"

# 중간발표 영상용 **데모 주행**. 설치된 앱을 데모 코스(1일차 1→N번)로 열고, 앱 안의 가상
# GPS 가 경로선을 따라 천천히 움직인다 — 성지 반경에서 머무르면 발바닥 스탬프가 핀에 찍히고
# 다음 성지로 넘어간다. 진짜 위치 대신 가상 위치를 쓰는 이유는 시뮬레이터·실기기 모두
# 「걸어서 이동하는 GPS」를 화면에 담을 방법이 그것뿐이기 때문이다(계획 trip-mode.md §7).
#   just ios-demo             ← 1번부터 3번까지, 각 성지 5초 머무름, 12 m/s (1→3 이 4~5분)
#   just ios-demo 5 8 25      ← 5번까지, 8초 머무름, 25 m/s (영상을 짧게)
# 먼저 `just ios-run` 으로 앱이 설치돼 있어야 한다.
[group('build')]
ios-demo until="3" dwell="5" speed="12" device="booted":
    xcrun simctl terminate {{device}} com.mz2az.scenetrip 2>/dev/null || true
    xcrun simctl launch {{device}} com.mz2az.scenetrip \
        -initialTab route -demoCourse 1 -navStop 1 \
        -demoDrive {{until}} -tripDwellSeconds {{dwell}} -demoSpeed {{speed}} -footprintOn 1

# 껐다 켜는 것과 사용법을 다시 보는 것은 **다른 일**이다.
#
# 앱을 껐다 켜면 스플래시는 다시 나오지만 사용법 넉 장은 안 나온다 — 본 적 있다는
# 표시가 `UserDefaults` 의 `scenetrip.onboarding.seenVersion` 에 남기 때문이다
# (그렇게 두지 않으면 켤 때마다 튜토리얼이 나온다).
#
# **그 표시는 밖에서 지울 수 없다.** 시뮬레이터의 `cfprefsd` 가 값을 메모리에 들고
# 있어서, 컨테이너의 plist 를 PlistBuddy 로 고쳐도 앱은 자기 캐시를 읽는다 —
# `killall cfprefsd` 로도 안 내려온다(2026-08-23 실측: 파일에는 키가 아예 없는데
# 앱은 「본 적 있다」로 동작했다). 그래서 지우는 레시피를 두지 않는다.
#
# 넉 장을 다시 보는 길은 둘이다.
#
#   앱 안에서   마이페이지 → 사용법 다시 보기   (가장 쉽다. 데이터가 안 날아간다)
#   깨끗하게    just ios-reinstall             (설치 직후 상태)

# 시뮬레이터의 앱을 껐다 켠다 (스플래시부터 다시 본다)
[group('build')]
ios-restart:
    xcrun simctl terminate booted com.mz2az.scenetrip 2>/dev/null || true
    xcrun simctl launch booted com.mz2az.scenetrip

# **저장된 것이 다 날아간다.** 설치본 UUID(`scenetrip.deviceId`)가 새로 생기므로
# 서버에 있는 코스와 장바구니가 남의 것이 되어 보이지 않는다. 화면만 확인할 때 쓴다.

# 앱을 지웠다 다시 깐다 (설치 직후 — 사용법 넉 장부터. 저장된 것은 날아간다)
[group('build')]
ios-reinstall:
    #!/usr/bin/env bash
    set -euo pipefail
    {{BAZEL}} build //apps/scenetrip-ios:bin \
        --define=naver_client_id="${NAVER_MAP_CLIENT_ID:-}"
    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT
    unzip -q bazel-bin/apps/scenetrip-ios/bin.ipa -d "$work"
    xcrun simctl terminate booted com.mz2az.scenetrip 2>/dev/null || true
    xcrun simctl uninstall booted com.mz2az.scenetrip 2>/dev/null || true
    xcrun simctl install booted "$work/Payload/bin.app"
    xcrun simctl launch booted com.mz2az.scenetrip

# `bazel run` 한 줄로 끝나는 ios-run 과 달리 스크립트를 거친다 — `android_binary` 는
# APK 만 내놓을 뿐 설치·실행을 하지 않는다. 그 몫(AVD 생성·부팅 대기·설치·실행)을
# tools/scripts/android-run.sh 가 대신하며, 이유는 그 파일 머리말에 적혀 있다.

# Android 앱을 에뮬레이터에 띄운다 (없으면 AVD 생성·부팅까지)
[group('build')]
android-run:
    NAVER_MAP_CLIENT_ID="${NAVER_MAP_CLIENT_ID:-}" ./tools/scripts/android-run.sh

# `just ios-run` 은 시뮬레이터까지다. **진짜 아이폰에 올리려면 애플 서명이 필요한데,
# 그 서명 파일은 사람마다·기기마다 다르다.** BUILD 파일에 박으면 그것을 가진 한 사람만
# 빌드할 수 있다. 그래서 서명은 Xcode 에게 맡기고 우리는 프로젝트 파일만 만들어 준다.
#
# 실제 빌드는 생성된 프로젝트 안에서 Bazel 이 한다 — 빌드 경로가 갈라지지 않는다.
#
# 아이폰에 올리는 절차는 apps/scenetrip-ios/README.md 의 "실기기" 절 참고.
#
# **네이버 지도 키 주의** — Xcode 가 부르는 빌드에는 이 레시피의 --define 이 닿지 않는다.
# `.bazelrc.user` 에 아래 한 줄을 넣어 두어야 지도가 뜬다(그 파일은 gitignore 대상이다).
#
#     build --define=naver_client_id=<발급받은 키>
#     build --define=kakao_rest_key=<카카오 REST 키>
#     build --define=llm_url=http://127.0.0.1:8900
#     build --define=llm_model=<apps/navi_proto/.env 의 LLM_MODEL>
#     build --define=llm_api_key=

# Bazel 프로젝트를 Xcode 로 연다 (실기기 설치용)
[group('build')]
ios-xcode:
    {{BAZEL}} run //apps/scenetrip-ios:xcodeproj
    open apps/scenetrip-ios/SceneTrip.xcodeproj

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
