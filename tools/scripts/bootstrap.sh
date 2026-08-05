#!/usr/bin/env bash
# 최초 1회 워크스테이션 세팅. 멱등.
# 호출: just setup
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

# 저장소에 그 언어의 소스가 있으면 포매터·린터는 선택이 아니라 필수다 — 없으면
# `just check` 가 실패한다(format.sh · lint.sh). 그래서 doctor 로 알리기 전에 먼저 깐다.
#
# 소스가 없는 언어의 도구는 깔지 않는다. 아무도 쓰지 않는 도구를 필수로 만들면
# 세팅만 무거워진다.
log "언어 도구 설치"
install_lang_tool() {
  local pattern="$1" tool="$2" formula="$3"
  find "$REPO_ROOT" -type d \( -name 'bazel-*' -o -name '.git' \) -prune \
    -o -type f -name "$pattern" -print 2>/dev/null | grep -q . || return 0
  have "$tool" && return 0
  if have brew; then
    log "  $tool 설치 ($pattern 소스가 있음)"
    brew install "$formula"
  else
    warn "$pattern 소스가 있는데 $tool 이(가) 없고 brew 도 없습니다 — 직접 설치하세요"
  fi
}

install_lang_tool '*.java' google-java-format google-java-format
install_lang_tool '*.java' checkstyle checkstyle
install_lang_tool '*.py' ruff ruff
install_lang_tool '*.kt' ktlint ktlint
install_lang_tool '*.swift' swiftformat swiftformat

log "도구 확인"
"$REPO_ROOT/tools/scripts/doctor.sh"

log "git 훅 설치"
hooks_dir="$(git -C "$REPO_ROOT" rev-parse --git-path hooks)"
cat > "$hooks_dir/pre-commit" <<'HOOK'
#!/usr/bin/env bash
# 포맷이 어긋나 CI 가 깨질 커밋을 막는다. 건너뛰려면 --no-verify.
exec just fmt-check
HOOK
chmod +x "$hooks_dir/pre-commit"

log "로컬 env 파일 생성"
find "$REPO_ROOT" -name '.env.example' -not -path '*/bazel-*/*' | while read -r example; do
  target="${example%.example}"
  [ -f "$target" ] || { cp "$example" "$target"; echo "  생성: $target"; }
done

log "빌드 그래프 예열"
(cd "$REPO_ROOT" && bazel info >/dev/null) || warn "bazel info 실패 — 'just doctor' 를 실행하세요"

log "세팅 완료 — 'just --list' 로 사용 가능한 명령을 확인하세요"
