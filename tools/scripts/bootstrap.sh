#!/usr/bin/env bash
# 최초 1회 워크스테이션 세팅. 멱등.
# 호출: just setup
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

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
