#!/usr/bin/env bash
# tools/templates/contract/ 의 템플릿으로 새 계약 정의를 만든다.
# 사용법: new-contract.sh <openapi|proto|asyncapi|schemas> <이름>
# 호출: just new-contract
#
# 템플릿이 있는 종류만 만들 수 있다. 없는 종류를 요청하면 조용히 빈 파일을 두지 않고
# 실패한다 — 계약은 골격이 틀린 채로 시작하면 안 되기 때문이다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

KIND="${1:?종류가 필요합니다: openapi|proto|asyncapi|schemas}"
NAME="${2:?계약 이름이 필요합니다}"

case "$KIND" in
  openapi|proto|asyncapi|schemas) ;;
  *) die "알 수 없는 종류: $KIND (openapi|proto|asyncapi|schemas 중 하나)" ;;
esac

TEMPLATE="$REPO_ROOT/tools/templates/contract/$KIND.yaml.tmpl"
[ -f "$TEMPLATE" ] || die "$KIND 템플릿이 아직 없습니다: tools/templates/contract/$KIND.yaml.tmpl
       템플릿을 먼저 추가하세요. 이 종류의 첫 계약을 만드는 것이 그 작업의 일부입니다."

# openapi 는 서비스당 파일 하나이고 파일명에 메이저 버전이 들어간다
# (contracts/openapi/README.md). 나머지 종류는 이름을 그대로 쓴다.
case "$KIND" in
  openapi) DEST="contracts/openapi/$NAME-v1.yaml" ;;
  *)       DEST="contracts/$KIND/$NAME.yaml" ;;
esac

ABS="$REPO_ROOT/$DEST"
[ -e "$ABS" ] && die "$DEST 가 이미 존재합니다 — 파괴적 변경이라면 메이저 버전을 올려 새 파일로 내세요"

[[ "$NAME" =~ ^[a-z][a-z0-9/-]*$ ]] || die "계약 이름은 kebab-case 여야 합니다: $NAME"

mkdir -p "$(dirname "$ABS")"
sed -e "s|{{NAME}}|$NAME|g" -e "s|{{KIND}}|$KIND|g" "$TEMPLATE" > "$ABS"

log "$DEST 생성 완료"
echo "다음 할 일:"
echo "  1. $DEST 의 TODO 를 실제 정의로 채우기"
echo "  2. just gen"
echo "  3. just test-contract"
