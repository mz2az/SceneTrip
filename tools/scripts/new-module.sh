#!/usr/bin/env bash
# tools/templates/ 의 템플릿으로 새 모듈을 만든다.
# 사용법: new-module.sh <service|app|agent|lib> <이름> <언어>
# 호출: just new-service | new-app-ios | new-app-android | new-agent | new-lib
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

KIND="${1:?종류가 필요합니다: service|app|agent|lib}"
NAME="${2:?모듈 이름이 필요합니다}"
# 주의: 셸의 LANG 은 로케일 환경변수다. 여기에 언어 이름을 넣으면 이후 실행되는
# sed·sort 등의 동작이 바뀔 수 있으므로 별도 이름을 쓴다.
MODULE_LANG="${3:?언어가 필요합니다}"

[[ "$NAME" =~ ^[a-z][a-z0-9-]*$ ]] || die "모듈 이름은 kebab-case 여야 합니다: $NAME"

# 확정된 스택 밖의 언어를 막는다. 언어를 늘리려면 먼저 ADR 을 쓰고,
# MODULE.bazel 에 규칙을 추가한 뒤 이 목록을 고친다.
case "$MODULE_LANG" in
  java|python|swift|kotlin) ;;
  *) die "지원하지 않는 언어: $MODULE_LANG (java|python|swift|kotlin 중 하나)" ;;
esac

# 분류와 언어가 어긋나는 조합을 막는다.
case "$KIND:$MODULE_LANG" in
  service:java|service:python) ;;
  app:swift|app:kotlin) ;;
  agent:python) ;;
  lib:java|lib:python|lib:swift|lib:kotlin) ;;
  *) die "$KIND 에 맞지 않는 언어입니다: $MODULE_LANG
  service -> java | python      (Spring 백엔드)
  app     -> swift | kotlin     (iOS | Android)
  agent   -> python             (AI 에이전트)
  lib     -> java | python | swift | kotlin" ;;
esac

case "$KIND" in
  service) DEST="services/$NAME" ;;
  app)     DEST="apps/$NAME" ;;
  agent)   DEST="agents/$NAME" ;;
  lib)     DEST="libs/$MODULE_LANG/$NAME" ;;
  *)       die "알 수 없는 종류: $KIND (service|app|agent|lib 중 하나)" ;;
esac

ABS="$REPO_ROOT/$DEST"
[ -e "$ABS" ] && die "$DEST 가 이미 존재합니다"

log "$DEST 생성"
mkdir -p "$ABS/src" "$ABS/tests"
[ "$KIND" = "lib" ] || mkdir -p "$ABS/deploy"

render() {
  sed -e "s|{{NAME}}|$NAME|g" \
      -e "s|{{KIND}}|$KIND|g" \
      -e "s|{{LANG}}|$MODULE_LANG|g" \
      -e "s|{{PATH}}|$DEST|g" "$1"
}

render "$REPO_ROOT/tools/templates/module/README.md.tmpl"   > "$ABS/README.md"
render "$REPO_ROOT/tools/templates/module/BUILD.bazel.tmpl" > "$ABS/BUILD.bazel"

if [ "$KIND" = "agent" ]; then
  mkdir -p "$ABS/prompts" "$ABS/evals"
  render "$REPO_ROOT/tools/templates/module/AGENT_CLAUDE.md.tmpl" > "$ABS/CLAUDE.md"
fi

# git 은 빈 디렉터리를 추적하지 않는다. 만들어 둔 구조가 유지되도록 표시 파일을 둔다.
find "$ABS" -type d -empty -exec touch {}/.gitkeep \;

log "$DEST 생성 완료"
echo "다음 할 일:"
echo "  1. $DEST/BUILD.bazel 에 실제 타깃 작성"
echo "  2. $DEST/README.md 에 목적·포트·의존성 기록"
echo "  3. just build-module $DEST"
