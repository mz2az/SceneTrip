#!/usr/bin/env bash
# 언어별 린터와 정적 분석. BUILD 파일 린트는 레시피 안의 buildifier 가 담당한다.
# 대상 언어는 확정 스택뿐이다: Java(Spring Boot) · Kotlin(Android) · Swift(iOS) · Python(AI).
# 호출: just lint
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

# 소스 파일 탐색(find_sources · has_files)은 _lib.sh 에 있다 — format.sh · doctor.sh 와 공용.

ran=0

# 그 언어의 소스는 있는데 린터가 없을 때.
#
# format.sh 와 달리 여기에는 "편의 모드"가 없다. lint 는 그 자체가 게이트다
# (just check → just lint → CI). 도구가 없다고 조용히 넘어가면 게이트가 거짓말을 한다 —
# **검사해서 통과한 것과 아예 검사하지 않은 것은 다르다.**
#
# 소스가 아직 없는 언어는 건드리지 않는다. 아무도 쓰지 않는 도구를 필수로 만들면
# 게이트가 상시 빨간불이 되고, 그러면 사람이 게이트를 무시하기 시작한다.
missing_tool() {
  die "$1 소스가 있는데 $2 이(가) 설치돼 있지 않아 린트를 실행할 수 없습니다.
       빠진 도구는 'just doctor' 로 확인하세요."
}

# --- Java (services/, libs/java/) ---------------------------------------------
if has_files '*.java'; then
  if have checkstyle; then
    ran=1
    # mapfile 은 bash 4 이상 전용이고 macOS 기본 bash 는 3.2 다 — format.sh 의 같은 주석 참조.
    files=()
    while IFS= read -r f; do files+=("$f"); done < <(find_sources '*.java')
    checkstyle -c /google_checks.xml "${files[@]}"
  else
    missing_tool Java checkstyle
  fi
fi

# --- Kotlin (apps/ Android, libs/kotlin/) -------------------------------------
if has_files '*.kt'; then
  if have ktlint; then
    ran=1
    ktlint
  else
    missing_tool Kotlin ktlint
  fi
fi

# --- Swift (apps/ iOS, libs/swift/) -------------------------------------------
if has_files '*.swift'; then
  if have swiftlint; then
    ran=1
    swiftlint
  else
    missing_tool Swift swiftlint
  fi
fi

# --- Python (agents/, libs/python/) -------------------------------------------
if has_files '*.py'; then
  if have ruff; then
    ran=1
    ruff check .
  else
    missing_tool Python ruff
  fi
fi

# --- 셸 (tools/scripts/) ------------------------------------------------------
# 다른 언어와 달리 이 검사에는 항상 대상이 있다 — 이 저장소는 처음부터 셸 스크립트로
# 굴러가기 때문이다. 그래서 shellcheck 은 사실상 필수 도구다.
if has_files '*.sh'; then
  if have shellcheck; then
    ran=1
    shellcheck -x tools/scripts/*.sh
  else
    missing_tool 셸 shellcheck
  fi
fi

[ "$ran" -eq 1 ] || pending "아직 적용할 린터가 없습니다 (소스 모듈 없음)"
