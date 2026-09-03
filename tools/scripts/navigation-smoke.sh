#!/usr/bin/env bash
# 배포된 서버의 길찾기가 실제로 답하는지 본다. 배포 직후 사람이 한 번 돌린다.
# 호출: just navigation-smoke
#
# ── 무엇을 보나 ───────────────────────────────────────────────────────────────
#
# 「맞나」가 아니라 「돌아가나」다. 규칙(900 m 컷·요금·기우기)은 단위 테스트가, SQL 은
# 통합 테스트가 본다. 여기서 보는 것은 그 둘이 못 보는 것 — 키가 파드에 들어갔나, 카카오에
# 닿나, 응답이 우리 모양으로 나오나. 전부 코드가 아니라 환경의 문제라 게이트 초록으로는
# 못 잡는다. 실제로 파드가 28일 전 이미지로 돌고 있던 적이 있다.
#
# ── 왜 tests/ 가 아니라 tools/ 인가 ───────────────────────────────────────────
#
# 클러스터·키·시드가 있어야 하고 카카오를 진짜로 부른다(최대 7번, 쿼터를 쓴다). 자동
# 게이트에 넣을 물건이 아니다. e2e 레인은 아직 비어 있고 첫 e2e 를 어떻게 돌릴지는 팀이
# ADR 0005 를 보정하며 정한다 — 그때 이 스크립트가 밑그림이 된다.
#
# ── 가입 처리를 SQL 로 한다 ───────────────────────────────────────────────────
#
# 길찾기는 가입자만 부를 수 있는데(401) 로그인이 아직 없다. 그래서 만든 계정의
# registered_at 을 직접 채운다. 로그인이 붙으면 이 줄을 그 API 호출로 바꾼다.
#
# ── 흔적을 남기지 않는다 ─────────────────────────────────────────────────────
#
# 끝나면(실패해도) 계정을 지운다. 코스·항목·핀은 ON DELETE CASCADE 로 함께 사라진다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

have curl || die "curl 이 없습니다"
have python3 || die "python3 이 없습니다"
require_kind_context

API="${API_URL:-http://localhost:8081}/v1"
# 출발점. 북촌 — 적재된 장소가 촘촘하고 대중교통이 있다. 다른 곳을 보려면 환경변수로.
LAT="${SMOKE_LAT:-37.5826}"
LNG="${SMOKE_LNG:-126.9831}"

DEV="$(uuidgen | tr '[:upper:]' '[:lower:]')"
CID=""
FAILS=0

cleanup() {
  [ -n "$CID" ] || return 0
  local uid
  uid="$("$REPO_ROOT/tools/scripts/db-psql.sh" \
    "SELECT user_id FROM course WHERE id = $CID;" 2>/dev/null |
    grep -oE '[0-9a-f-]{36}' | head -1 || true)"
  if [ -n "$uid" ]; then
    "$REPO_ROOT/tools/scripts/db-psql.sh" "DELETE FROM app_user WHERE id = '$uid';" >/dev/null 2>&1 || true
    log "검증 계정과 코스 $CID 를 지웠습니다"
  fi
}
trap cleanup EXIT

api() { # $1=method $2=path $3=body(optional) $4=lang(optional) → 본문, 마지막 줄에 HTTP 코드
  curl -s -X "$1" "$API$2" \
    -H "Content-Type: application/json" -H "X-Device-Id: $DEV" \
    -H "Accept-Language: ${4:-ko}" \
    ${3:+-d "$3"} -w '\n%{http_code}'
}
code() { tail -n1 <<<"$1"; }
body() { sed '$d' <<<"$1"; }
jget() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval('d'+sys.argv[1]))" "$1"; }

check() { # $1=설명 $2=기대코드 $3=실제코드
  if [ "$2" = "$3" ]; then
    printf '  \033[32m✓\033[0m %-44s %s\n' "$1" "$3"
  else
    printf '  \033[31m✗\033[0m %-44s %s (기대 %s)\n' "$1" "$3" "$2"
    FAILS=$((FAILS + 1))
  fi
}

# ── 0. 서버가 떠 있나 ─────────────────────────────────────────────────────────
curl -sf "$API/actuator/health" >/dev/null ||
  die "서버에 닿지 않습니다 ($API) — 'just deploy scene-api local' 뒤에 다시"

# ── 1. 목적지 둘 고르기 — 반경 3 km 에서 가까운 것(도보)과 먼 것(대중교통) ─────────
log "출발점 ($LAT, $LNG) 반경 3 km 의 장소를 고릅니다"
PICK="$(curl -s "$API/places?lat=$LAT&lng=$LNG&radiusMeters=3000&sort=distance&limit=30" \
  -H "X-Device-Id: $DEV" | python3 -c '
import sys, json, math
lat0, lng0 = float(sys.argv[1]), float(sys.argv[2])
def dist(p):
    dlat = math.radians(p["latitude"] - lat0); dlng = math.radians(p["longitude"] - lng0)
    a = math.sin(dlat/2)**2 + math.cos(math.radians(lat0))*math.cos(math.radians(p["latitude"]))*math.sin(dlng/2)**2
    return 2*6371000*math.asin(math.sqrt(a))
items = json.load(sys.stdin).get("items", [])
near = next((p for p in items if 100 < dist(p) < 900), None)
far  = next((p for p in items if 1200 < dist(p) < 3000), None)
if not near or not far: sys.exit(1)
print(near["id"], round(dist(near)), near["name"]); print(far["id"], round(dist(far)), far["name"])
' "$LAT" "$LNG")" || die "반경 3 km 에 도보용(100~900 m)·대중교통용(1.2~3 km) 장소가 부족합니다 — 'just seed' 를 했는지, 출발점이 맞는지"
NEAR_ID=$(sed -n 1p <<<"$PICK" | cut -d' ' -f1); NEAR_M=$(sed -n 1p <<<"$PICK" | cut -d' ' -f2); NEAR_NAME=$(sed -n 1p <<<"$PICK" | cut -d' ' -f3-)
FAR_ID=$(sed -n 2p <<<"$PICK" | cut -d' ' -f1);  FAR_M=$(sed -n 2p <<<"$PICK" | cut -d' ' -f2);  FAR_NAME=$(sed -n 2p <<<"$PICK" | cut -d' ' -f3-)
log "  도보용     ${NEAR_M} m  $NEAR_NAME (#$NEAR_ID)"
log "  대중교통용 ${FAR_M} m  $FAR_NAME (#$FAR_ID)"

# ── 2. 코스 만들고 장소 둘 넣기 ───────────────────────────────────────────────
R="$(api POST /courses '{"dayCount":1,"origin":"self","title":"길찾기 스모크"}')"
[ "$(code "$R")" = "201" ] || die "코스 생성 실패: $(body "$R")"
CID="$(body "$R" | jget "['id']")"
R="$(api PUT "/courses/$CID" "{\"title\":\"길찾기 스모크\",\"days\":[{\"items\":[{\"placeId\":$NEAR_ID},{\"placeId\":$FAR_ID}]}]}")"
[ "$(code "$R")" = "200" ] || die "장소 넣기 실패: $(body "$R")"
NEAR_ITEM="$(body "$R" | jget "['days'][0]['items'][0]['id']")"
FAR_ITEM="$(body "$R" | jget "['days'][0]['items'][1]['id']")"

# ── 3. 가입 처리 (로그인이 생기면 이 줄이 그 API 호출로 바뀐다) ──────────────────
"$REPO_ROOT/tools/scripts/db-psql.sh" \
  "UPDATE app_user SET registered_at = now() WHERE id = (SELECT user_id FROM course WHERE id = $CID);" >/dev/null

HERE="\"latitude\":$LAT,\"longitude\":$LNG"
echo
log "통제"
R="$(api POST /navigation/next-leg "{\"courseId\":$CID,\"itemId\":$FAR_ITEM,$HERE}")"
check "시작 전 코스 → 409 COURSE_NOT_ACTIVE" 409 "$(code "$R")"

R="$(api PUT "/courses/$CID/progress" '{"status":"active","currentDayNo":1}')"
check "코스 시작 → 200" 200 "$(code "$R")"

R="$(api POST /navigation/next-leg "{\"courseId\":$CID,\"itemId\":999999,$HERE}")"
check "없는 항목 → 404 COURSE_ITEM_NOT_FOUND" 404 "$(code "$R")"

summarize() { # $1=본문 → 한 줄 요약. 코드는 히어독으로, JSON 은 인자로 — 따옴표가 섞이지 않게.
  python3 - "$1" <<'PY'
import sys, json
d = json.loads(sys.argv[1])
modes = "".join("W" if l["mode"] == "walk" else "T" for l in d["legs"])
print(f'{d["totalMinutes"]}분 · 환승 {d["transfers"]} · 도보 {d.get("walkMeters")} m · '
      f'{d.get("fareWon")}원 · {d["guidanceLang"]} · legs {len(d["legs"])} [{modes}]')
PY
}

echo
log "길찾기 — 카카오를 실제로 부릅니다"
R="$(api POST /navigation/next-leg "{\"courseId\":$CID,\"itemId\":$NEAR_ITEM,$HERE}")"
check "${NEAR_M} m → 도보만 (200)" 200 "$(code "$R")"
[ "$(code "$R")" = "200" ] && { echo "      $(summarize "$(body "$R")")"; [ "$(body "$R" | jget "['transfers']")" = "0" ] || { echo "      ✗ 도보만이어야 하는데 환승이 있다"; FAILS=$((FAILS+1)); }; }

R="$(api POST /navigation/next-leg "{\"courseId\":$CID,\"itemId\":$FAR_ITEM,$HERE}")"
check "${FAR_M} m → 대중교통 (200)" 200 "$(code "$R")"
[ "$(code "$R")" = "200" ] && echo "      $(summarize "$(body "$R")")"

R="$(api POST /navigation/next-leg "{\"courseId\":$CID,\"itemId\":$FAR_ITEM,$HERE}" ja)"
check "같은 구간 Accept-Language: ja → 200" 200 "$(code "$R")"
if [ "$(code "$R")" = "200" ]; then
  GL="$(body "$R" | jget "['guidanceLang']")"
  if [ "$GL" = "en" ]; then
    echo "      guidanceLang=en · $(body "$R" | jget "['legs'][0]['guidance']" | cut -c1-60)"
  else
    echo "      ✗ guidanceLang 이 en 이어야 하는데 $GL"
    FAILS=$((FAILS + 1))
  fi
fi

echo
if [ "$FAILS" -eq 0 ]; then
  log "길찾기 스모크 통과 — 키·카카오·응답 모양 전부 살아 있습니다"
else
  die "길찾기 스모크 실패 $FAILS 건"
fi
