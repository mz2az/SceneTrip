#!/usr/bin/env bash
# contracts/ 로부터 API 레퍼런스를 렌더링해 브라우저로 연다.
# 호출: just docs-api
#
# 왜 명세 파일을 직접 읽지 않는가:
#
# scene-api-v1.yaml 은 900 줄이고 응답 모양이 $ref 로 흩어져 있다. "이 엔드포인트가
# 무엇을 받고 무엇을 돌려주는가" 를 알려면 파일 안을 서너 번 왕복해야 한다. 이
# 산출물은 그것을 엔드포인트별로 한자리에 펼친다.
#
# 왜 미리 만들어 커밋하지 않는가:
#
# 명세가 바뀌면 문서도 같이 낡는다. 커밋해 두면 둘이 어긋난 상태가 저장소에 남고,
# 어느 쪽이 맞는지 아무도 모르게 된다. 명세만 진실로 두고 문서는 그때그때 만든다
# (contracts/README.md).
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

BAZEL_BIN="${BAZEL:-bazel}"

have "$BAZEL_BIN" || die "bazel 을 찾을 수 없습니다 — 'just setup' 을 먼저 실행하세요"

if ! compgen -G "contracts/openapi/*.yaml" >/dev/null 2>&1; then
  die "contracts/openapi/ 에 명세가 없습니다"
fi

log "명세에서 API 레퍼런스 생성"
"$BAZEL_BIN" build //contracts/openapi:scene_api_html >/dev/null \
  || die "생성 실패 — 명세에 문법 오류가 있는지 'just build //contracts/openapi/...' 로 확인하세요"

# 절대 경로로 넘긴다. 상대 경로는 브라우저가 file:// 로 열지 못한다.
DOC="$REPO_ROOT/bazel-bin/contracts/openapi/scene_api_html/index.html"
[ -f "$DOC" ] || die "생성물을 찾을 수 없습니다: $DOC"

log "생성 완료: $DOC"

# 브라우저를 여는 방법은 플랫폼마다 다르다. 셋 다 실패하면 경로만 알려 준다 —
# 헤드리스 환경(CI, 원격 셸)에서 이 스크립트가 실패로 끝나면 안 된다.
open "$DOC" 2>/dev/null \
  || xdg-open "$DOC" 2>/dev/null \
  || echo "브라우저에서 여세요: $DOC"
