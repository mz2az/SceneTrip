#!/usr/bin/env bash
# 최초 1회 워크스테이션 세팅. 멱등.
# 호출: just setup
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

# 언어 도구를 여기서 깔지 않는다.
#
# Java 는 Bazel 이 받아온다 — //:google_java_format · //:checkstyle (buildifier 와 같은
# 방식). 그래서 워크스테이션에 아무것도 깔 필요가 없고, CI 러너도 별도 설치 없이 같은
# 버전을 쓴다. 호스트 설치에 의존하면 사람마다 도구 버전이 달라 포맷 결과가 갈린다.
#
# 다른 언어의 첫 모듈이 들어올 때도 같은 방식으로 붙인다 — 루트 BUILD.bazel 에 타깃을
# 만들고 tools/scripts/{format,lint}.sh 가 그것을 부르게 한다.

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
