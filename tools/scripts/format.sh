#!/usr/bin/env bash
# 언어별 포매터. BUILD 파일은 레시피 안의 buildifier 가 담당한다.
# 대상 언어는 확정 스택뿐이다: Java(Spring Boot) · Kotlin(Android) · Swift(iOS) · Python(AI).
# 호출: just fmt [--check]
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

# 소스 파일 탐색(find_sources · has_files)은 _lib.sh 에 있다 — lint.sh · doctor.sh 와 공용.

ran=0
missing=0

# 그 언어의 소스는 있는데 포매터가 없을 때.
#
#   --check 모드 (just fmt-check · just check · CI) → 실패시킨다.
#       검사하지 않은 것을 "통과"로 보고하면 게이트가 거짓말을 한다. 포매터를 가진
#       사람과 아닌 사람이 같은 명령으로 서로 다른 결과를 내는 것도 여기서 시작된다.
#
#   포맷 모드 (just fmt) → 경고만 하고 넘어간다.
#       fmt 는 고쳐주는 편의 명령이라 여기서 멈출 이유가 없다. 실제로 막아야 하는
#       지점은 커밋 직전의 fmt-check 다.
#
# 소스가 아직 없는 언어는 어느 모드에서도 건드리지 않는다 — 아무도 쓰지 않는 도구를
# 필수로 만들면 게이트가 상시 빨간불이 되고, 그러면 사람이 게이트를 무시하기 시작한다.
missing_tool() {
  local lang="$1" tool="$2"
  missing=1
  if [ "$CHECK" -eq 1 ]; then
    die "$lang 소스가 있는데 $tool 이(가) 설치돼 있지 않아 포맷을 검사할 수 없습니다.
       빠진 도구는 'just doctor' 로 확인하세요."
  fi
  warn "$lang 소스가 있는데 $tool 이(가) 없어 건너뜁니다 — 'just doctor' 로 확인하세요."
}

# --- Java (services/, libs/java/) ---------------------------------------------
#
# 포매터를 호스트에서 찾지 않는다. Bazel 이 //:google_java_format 으로 버전을 고정해
# 받아오므로 "깔려 있나" 를 물을 필요가 없고, 사람마다 버전이 달라 결과가 갈리는 일도
# 없다 (AGENTS.md §4.3). buildifier 와 같은 방식이다.
if has_files '*.java'; then
  ran=1
  # mapfile 을 쓰지 않는 이유: bash 4 이상에만 있는데 macOS 기본 bash 는 3.2 다.
  # 팀 전원이 macOS 라 그대로 두면 "command not found" 로 게이트가 죽는다.
  files=()
  while IFS= read -r f; do files+=("$f"); done < <(find_sources '*.java')
  if [ "$CHECK" -eq 1 ]; then
    out="$("${BAZEL:-bazel}" run --ui_event_filters=-info,-stdout --noshow_progress \
      //:google_java_format -- --dry-run --set-exit-if-changed "${files[@]}" 2>&1 || true)"
    # 도구가 고칠 파일 이름만 뱉는다. 빌드 로그 줄은 걸러낸다.
    out="$(printf '%s\n' "$out" | grep -vE '^(INFO|WARNING|Target|  bazel-bin|$)' || true)"
    [ -z "$out" ] || die "포맷이 어긋난 Java 파일:\n$out"
  else
    "${BAZEL:-bazel}" run --ui_event_filters=-info,-stdout --noshow_progress \
      //:google_java_format -- -i "${files[@]}"
  fi
fi

# --- Kotlin (apps/ Android, libs/kotlin/) -------------------------------------
if has_files '*.kt'; then
  if have ktlint; then
    ran=1
    if [ "$CHECK" -eq 1 ]; then ktlint; else ktlint -F; fi
  else
    missing_tool Kotlin ktlint
  fi
fi

# --- Swift (apps/ iOS, libs/swift/) -------------------------------------------
if has_files '*.swift'; then
  if have swiftformat; then
    ran=1
    if [ "$CHECK" -eq 1 ]; then swiftformat --lint .; else swiftformat .; fi
  elif have swift-format; then
    ran=1
    if [ "$CHECK" -eq 1 ]; then swift-format lint -r .; else swift-format format -i -r .; fi
  else
    missing_tool Swift "swiftformat 또는 swift-format"
  fi
fi

# --- Python (agents/, libs/python/) -------------------------------------------
if has_files '*.py'; then
  if have ruff; then
    ran=1
    if [ "$CHECK" -eq 1 ]; then ruff format --check .; else ruff format .; fi
  else
    missing_tool Python ruff
  fi
fi

if [ "$ran" -eq 0 ] && [ "$missing" -eq 0 ]; then
  pending "아직 적용할 언어 포매터가 없습니다 (소스 모듈 없음)"
fi
