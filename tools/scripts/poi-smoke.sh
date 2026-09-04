#!/usr/bin/env bash
# 배포된 scene-api 에 편의시설 조회(GET /pois · GET /pois/{id})를 실제로 흘려 본다.
# 사용법: poi-smoke.sh [API 주소]     기본 http://localhost:8081/v1 (kind 의 NodePort · 컨텍스트 경로 /v1)
# 호출: just poi-smoke
#
# 단위·통합 테스트가 못 보는 것을 본다 — 컨테이너로 빌드되어 클러스터에 뜬 서버가 진짜
# HTTP 로, 50 만 행 표에서, 인덱스를 타며 응답하는가. 그리고 psql 로 잰 3.9 ms 가 스프링·
# JDBC·직렬화를 거쳐 몇 ms 가 되는가. 성공/실패 판정은 파이썬 한 토막이 한다.
#
# 자리는 강남역 2 km 뷰포트다 — bbox 안에 4 천 행, 정렬 30 개. 표본 23 행만 든 DB 에서는
# 비어 나오므로 전량 적재(`just seed-poi <파일들>`) 뒤에 돌린다.
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

# 요청 하나를 보내고 상태 코드·Content-Language·왕복 ms 를 앞줄에, 본문을 그 뒤에 남긴다.
call() {
  local name="$1" url="$2"
  shift 2
  curl -s -o "$TMP/$name.body" -w '%{http_code} %{time_total}\n' -D "$TMP/$name.head" "$@" "$url" >"$TMP/$name.meta" \
    || die "$name 요청 실패 — 서버가 떠 있습니까? ($API)"
}

log "대상: $API"
curl -sf -o /dev/null "$API/actuator/health" || die "서버에 닿지 않습니다: $API/actuator/health"

# 첫 요청은 JIT·커넥션 풀 예열이 섞여 수백 ms 다. 한 번 버리고 잰다 — 앱도 두 번째부터를 본다.
call warmup      "$API/pois?bbox=$BBOX&lat=$LAT&lng=$LNG"
call list        "$API/pois?bbox=$BBOX&lat=$LAT&lng=$LNG"
call stay        "$API/pois?bbox=$BBOX&lat=$LAT&lng=$LNG&categoryGroup=stay"
call five        "$API/pois?bbox=$BBOX&lat=$LAT&lng=$LNG&limit=5"
call noarea      "$API/pois"
call badsort     "$API/pois?bbox=$BBOX&sort=distance"
call english     "$API/pois?bbox=$BBOX&lat=$LAT&lng=$LNG&limit=1" -H "Accept-Language: en"
FIRST_ID=$(python3 -c "import json,sys; d=json.load(open(sys.argv[1])); print(d['items'][0]['id'] if d.get('items') else 0)" "$TMP/list.body")
call detail      "$API/pois/$FIRST_ID?lat=$LAT&lng=$LNG"
call missing     "$API/pois/0"

python3 - "$TMP" <<'PY'
import json, sys, pathlib
T = pathlib.Path(sys.argv[1]); fails = []
def meta(n):
    code, secs = (T / f"{n}.meta").read_text().split(); return int(code), float(secs) * 1000
def body(n):
    try: return json.loads((T / f"{n}.body").read_text())
    except Exception: return {}
def lang(n):
    for line in (T / f"{n}.head").read_text().splitlines():
        if line.lower().startswith("content-language:"): return line.split(":", 1)[1].strip()
    return None
def check(ok, msg):
    print(("  ✓ " if ok else "  ✗ ") + msg)
    if not ok: fails.append(msg)

code, ms = meta("list"); d = body("list"); items = d.get("items", [])
print(f"① 강남 2 km 뷰포트 · 거리순     {code} · {ms:.0f} ms")
check(code == 200, "200")
check(len(items) == 30, f"30개 (실제 {len(items)})")
dists = [i.get("distanceMeters") for i in items]
check(all(x is not None for x in dists) and dists == sorted(dists), f"distanceMeters 오름차순 {dists[:3]}…{dists[-1:]}")
check(d.get("total", 0) > 1000, f"total 이 상한보다 훨씬 크다 ({d.get('total')})")
check(d.get("limit") == 30 and d.get("offset") == 0, "limit 30 · offset 0")
check(lang("list") == "ko", f"Content-Language ko (실제 {lang('list')})")
check(ms < 100, f"왕복 {ms:.0f} ms < 100 (psql 3.9 + 14 ms 에 스프링·직렬화·네트워크)")

code, ms = meta("stay"); d = body("stay"); items = d.get("items", [])
print(f"② categoryGroup=stay            {code} · {ms:.0f} ms")
check(code == 200 and items and all(i.get("categoryGroup") == "stay" for i in items), f"전부 stay ({len(items)}개)")

code, ms = meta("five"); d = body("five")
print(f"③ limit=5                       {code} · {ms:.0f} ms")
check(code == 200 and len(d.get("items", [])) == 5, "5개")
check(d.get("total") == body("list").get("total"), f"total 은 ①과 같다 ({d.get('total')})")

for n, label, want in [("noarea", "④ 영역 조건 없음", "MISSING_AREA_FILTER"), ("badsort", "⑤ sort=distance, 기준점 없음", "INVALID_SORT")]:
    code, ms = meta(n); d = body(n)
    print(f"{label:32s}{code}")
    check(code == 400 and d.get("code") == want, f"400 {want} (실제 {d.get('code')})")

code, ms = meta("english"); print(f"⑥ Accept-Language: en           {code}")
check(lang("english") == "ko", f"그래도 Content-Language ko (실제 {lang('english')})")

code, ms = meta("detail"); d = body("detail"); first = body("list").get("items", [{}])[0]
print(f"⑦ 상세 /pois/{first.get('id')}              {code} · {ms:.0f} ms")
check(code == 200 and d.get("name") == first.get("name"), f"이름이 목록과 같다 ({d.get('name')})")
check(d.get("distanceMeters") == first.get("distanceMeters"), f"거리도 같다 ({d.get('distanceMeters')} m)")
# tel 은 34%, road 는 3% 가 비어 있고 null 은 JSON 에서 생략된다. region 은 전 행에 있다 — 상세 전용 필드가 실렸는지는 그걸로 본다.
check(d.get("region") and d.get("city"), f"상세 전용 필드 region·city 실림 ({d.get('region')} {d.get('city')})")

code, ms = meta("missing"); d = body("missing"); print(f"⑧ 없는 id                       {code}")
check(code == 404 and d.get("code") == "POI_NOT_FOUND", f"404 POI_NOT_FOUND (실제 {d.get('code')})")

print()
if fails:
    print(f"실패 {len(fails)}건"); sys.exit(1)
print("스모크 통과")
PY
