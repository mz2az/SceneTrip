#!/usr/bin/env bash
# 배포된 scene-api 에 편의시설 카드(GET /pois/{id}/card · GET /pois/cards)를 실제로 흘려 본다.
# 사용법: poi-card-smoke.sh [API 주소]     기본 http://localhost:8081/v1
# 호출: just poi-card-smoke
#
# **실제 네이버를 부른다** (비공식, ADR 0011). 그래서 호출 수를 10 번 안쪽으로 묶는다 —
# 스모크가 데모 전에 출처를 두드려 막히게 하면 안 된다. 보는 것 — 응답 형식이 프로토타입
# 시절과 같은가(단건이 found 로 오는가), 표에 남는가(두 번째는 즉시), 여럿 경로의 pending 이
# 일꾼에 의해 채워지는가.
#
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"

API="${1:-http://localhost:8081/v1}"
have curl || die "curl 이 없습니다"
have python3 || die "python3 이 없습니다"

BBOX="127.017,37.489,127.037,37.507"
LAT=37.498
LNG=127.027
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

call() {
  local name="$1" url="$2"
  shift 2
  curl -s -o "$TMP/$name.body" -w '%{http_code} %{time_total}\n' "$@" "$url" >"$TMP/$name.meta" \
    || die "$name 요청 실패 — 서버가 떠 있습니까? ($API)"
}
field() { python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(eval(sys.argv[2]))" "$TMP/$1.body" "$2"; }

log "대상: $API"
curl -sf -o /dev/null "$API/actuator/health" || die "서버에 닿지 않습니다: $API/actuator/health"
warn "실제 네이버를 부릅니다 — 10 번 안쪽"

call pins "$API/pois?bbox=$BBOX&lat=$LAT&lng=$LNG&limit=5"
IDS=$(field pins "','.join(str(i['id']) for i in d['items'])" | tr "'" ",")
FIRST=${IDS%%,*}
[ -n "$FIRST" ] || die "핀이 없습니다 — poi 가 적재돼 있습니까?"

# 두 번째 실행부터는 다섯 곳이 표에 다 있어 pending 이 안 생긴다. 로컬 kind 라면 그 다섯 행을
# 지우고 시작한다 — 매번 진짜로 「없음 → 묻기 → 채움」을 밟게. 다른 주소면 건너뛴다.
if [ "$API" = "http://localhost:8081/v1" ]; then
  ./tools/scripts/db-psql.sh "DELETE FROM poi_naver WHERE poi_id IN ($IDS);" >/dev/null 2>&1 \
    && log "표에서 $IDS 를 지우고 시작한다 (매번 처음처럼)"
fi

call card1 "$API/pois/$FIRST/card"
call card2 "$API/pois/$FIRST/card"
call batch1 "$API/pois/cards?ids=$IDS"
WAIT=$(field batch1 "d.get('retryAfterSeconds') or 3")
log "여럿: retryAfterSeconds=$WAIT — 그만큼 기다렸다 다시 묻는다"
sleep "$WAIT"
call batch2 "$API/pois/cards?ids=$IDS"
call missing "$API/pois/0/card"
call badids "$API/pois/cards"

python3 - "$TMP" "$IDS" <<'PY'
import json, sys, pathlib
T = pathlib.Path(sys.argv[1]); ids = [int(x) for x in sys.argv[2].split(",")]; fails = []
def meta(n):
    code, secs = (T / f"{n}.meta").read_text().split(); return int(code), float(secs) * 1000
def body(n):
    try: return json.loads((T / f"{n}.body").read_text())
    except Exception: return {}
def check(ok, msg):
    print(("  ✓ " if ok else "  ✗ ") + msg)
    if not ok: fails.append(msg)

code, ms = meta("card1"); c = body("card1")
print(f"① 단건 처음  /pois/{ids[0]}/card       {code} · {ms:.0f} ms")
check(code == 200, "200")
check("found" in c, f"found 가 있다 (why={c.get('why')})")
if c.get("found"):
    check(bool(c.get("name")) and bool(c.get("naverUrl")), f"name·naverUrl 실림 ({c.get('name')})")
    check(isinstance(c.get("images"), list), f"images 배열 ({len(c.get('images') or [])}장)")
    check(ms < 2000, f"첫 조회 {ms:.0f} ms < 2000 (검색 0.22 + 상세 0.10 + …)")
else:
    print(f"    ⚠ 못 찾음: {c.get('why')} — 형식이 바뀌었다면 여기서 드러난다")
check(bool(c.get("checkedAt")), "checkedAt 있음")

code, ms = meta("card2"); c2 = body("card2")
print(f"② 단건 다시                          {code} · {ms:.0f} ms")
check(code == 200 and c2.get("checkedAt") == c.get("checkedAt"), f"checkedAt 이 같다 — 표에서 왔다")
check(ms < 100, f"왕복 {ms:.0f} ms < 100")

code, ms = meta("batch1"); b = body("batch1"); items = b.get("items", [])
print(f"③ 여럿 처음  ids={len(ids)}개               {code} · {ms:.0f} ms")
check(code == 200 and [i.get("poiId") for i in items] == ids, "요청한 순서·개수 그대로")
check(items and items[0].get("pending") is not True, "첫 것은 ①에서 채워져 카드다")
pending1 = [i["poiId"] for i in items if i.get("pending")]
check(len(pending1) >= 1, f"나머지는 pending ({len(pending1)}개)")
check(b.get("retryAfterSeconds") is not None, f"retryAfterSeconds={b.get('retryAfterSeconds')}")
check(ms < 100, f"왕복 {ms:.0f} ms < 100 — 출처를 안 불렀다")

code, ms = meta("batch2"); b2 = body("batch2")
pending2 = [i["poiId"] for i in b2.get("items", []) if i.get("pending")]
found2 = [i for i in b2.get("items", []) if i.get("found") is not None]
print(f"④ 여럿 다시 (일꾼이 채웠나)           {code} · {ms:.0f} ms")
check(len(pending2) < len(pending1), f"pending 이 줄었다 {len(pending1)} → {len(pending2)}")
print(f"    찾음 {sum(1 for i in found2 if i.get('found'))} · 없음 {sum(1 for i in found2 if i.get('found') is False)} · pending {len(pending2)}")
for i in found2:
    if i.get("found") is False: print(f"    - {i['poiId']}: {i.get('why')}")

code, _ = meta("missing"); print(f"⑤ /pois/0/card                        {code}")
check(code == 404 and body("missing").get("code") == "POI_NOT_FOUND", "404 POI_NOT_FOUND")
code, _ = meta("badids"); print(f"⑥ /pois/cards (ids 없음)              {code}")
check(code == 400, "400")

print()
if fails: print(f"실패 {len(fails)}건"); sys.exit(1)
print("스모크 통과")
PY
