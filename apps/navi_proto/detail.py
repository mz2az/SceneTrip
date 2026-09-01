"""엔진 응답을 사람이 읽는 구간 목록으로 펼친다.

총 거리와 시간만 보면 어느 엔진이 맞는지 판단할 수 없다. 어디서 어디까지 걸었는지,
무슨 버스를 어디서 타고 어디서 내렸는지, 횡단보도를 몇 번 건넜는지를 봐야
"이 경로가 말이 되는가" 를 사람이 가릴 수 있다.

엔진마다 주는 것이 다르다. 그 차이 자체가 검증 결과이므로, 없는 것은 없다고
그대로 남긴다 — 억지로 채우지 않는다.
"""

# TMAP 보행자 응답의 facilityType 코드. 확인된 것만 적는다.
# 공식 코드표를 문서에서 찾지 못해, 실제 응답의 description 과 함께 나타난 것만
# 확정했다. 나머지는 "미확인" 으로 두고 숫자를 그대로 보여준다.
TMAP_FACILITY = {
    "11": "보행자도로",
    "15": "횡단보도",
    # 17 = 계단. 문서에 없어 실험으로 갈랐다 — `searchOption=30`(계단 제외)로 같은
    # 구간을 부르면 17 이 **완전히 사라진다**(남산 9→0, 낙산 2→0). 안내문에는
    # "계단" 이라는 말이 아예 안 나오므로 **글자로는 못 잡는다.**
    "17": "계단",
}

# 안내문에서 찾을 시설. 코드표가 불완전하므로 한국어 안내문을 근거로 센다.
FACILITY_WORDS = [
    ("횡단보도", "횡단보도"),
    ("육교", "육교"),
    ("지하보도", "지하보도"),
    ("지하도", "지하보도"),
    ("계단", "계단"),
    ("에스컬레이터", "에스컬레이터"),
    ("엘리베이터", "엘리베이터"),
    ("교량", "다리"),
    ("터널", "터널"),
]


def count_facilities(texts):
    """안내문 목록에서 지나간 시설을 센다."""
    out = {}
    for t in texts:
        if not t:
            continue
        for word, label in FACILITY_WORDS:
            if word in t:
                out[label] = out.get(label, 0) + 1
                break  # 한 안내문이 두 번 세지 않게
    return out


# ── 도보 ─────────────────────────────────────────────────────────────────────


def steps_tmap_walk(data):
    """TMAP 보행자. Point 가 안내 지점, LineString 이 그 사이의 선이다.

    셋 중 유일하게 **횡단보도·시설 정보를 준다.** 검증의 기준선이 되는 이유다.
    """
    steps = []
    for f in data.get("features", []):
        p = f.get("properties", {})
        if f["geometry"]["type"] != "LineString":
            continue
        code = str(p.get("facilityType", "")).strip()
        steps.append(
            {
                "text": (p.get("description") or "").strip(),
                "road": p.get("name") or "",
                "distance_m": p.get("distance") or 0,
                "duration_s": p.get("time") or 0,
                "facility": TMAP_FACILITY.get(code, f"코드 {code}" if code else ""),
            }
        )
    # 안내문은 Point 쪽이 더 자세하다(횡단보도 표시가 거기 붙는다). 순서대로 옮긴다.
    guides = [
        (f["properties"].get("description") or "").strip()
        for f in data.get("features", [])
        if f["geometry"]["type"] == "Point"
    ]
    for i, s in enumerate(steps):
        if i < len(guides) and guides[i]:
            s["text"] = guides[i]
    return steps


def steps_osrm(data):
    """OSRM. 도로 이름과 회전 방향은 주지만 횡단보도·계단은 알려주지 않는다."""
    steps = []
    for leg in data["routes"][0].get("legs", []):
        for s in leg.get("steps", []):
            m = s.get("maneuver", {})
            turn = " ".join(x for x in (m.get("modifier"), m.get("type")) if x)
            steps.append(
                {
                    "text": turn,
                    "road": s.get("name") or "",
                    "distance_m": round(s.get("distance", 0)),
                    "duration_s": round(s.get("duration", 0)),
                    "facility": "페리" if s.get("mode") == "ferry" else "",
                }
            )
    return steps


def steps_valhalla(trip):
    """Valhalla. 영어 안내문을 준다. 횡단보도·계단은 알려주지 않는다."""
    steps = []
    for leg in trip.get("legs", []):
        for m in leg.get("maneuvers", []):
            steps.append(
                {
                    "text": m.get("instruction", ""),
                    "road": ", ".join(m.get("street_names", []) or []),
                    "distance_m": round((m.get("length") or 0) * 1000),
                    "duration_s": round(m.get("time") or 0),
                    # type 27/28 이 페리 승하선이다
                    "facility": "페리" if m.get("type") in (27, 28) else "",
                }
            )
    return steps


def steps_ors(feature):
    """OpenRouteService. segments 안에 steps 가 있다."""
    steps = []
    for seg in feature["properties"].get("segments", []):
        for s in seg.get("steps", []):
            steps.append(
                {
                    "text": s.get("instruction", ""),
                    "road": s.get("name") if s.get("name") != "-" else "",
                    "distance_m": round(s.get("distance", 0)),
                    "duration_s": round(s.get("duration", 0)),
                    "facility": "",
                }
            )
    return steps


# ── 대중교통 ─────────────────────────────────────────────────────────────────

MODE_KO = {
    "WALK": "도보",
    "BUS": "버스",
    "SUBWAY": "지하철",
    "EXPRESSBUS": "고속·시외버스",
    "TRAIN": "기차",
    "AIRPLANE": "항공",
    "FERRY": "배",
}


def legs_tmap_transit(itinerary):
    """TMAP 대중교통. 구간마다 무엇을 타고 어디서 어디까지 가는지가 다 있다."""
    out = []
    for l in itinerary.get("legs", []):
        mode = l.get("mode", "")
        start = (l.get("start") or {}).get("name", "")
        end = (l.get("end") or {}).get("name", "")
        stops = (l.get("passStopList") or {}).get("stations") or []
        walk_texts = [s.get("description", "") for s in (l.get("steps") or [])]
        out.append(
            {
                "mode": mode,
                "mode_ko": MODE_KO.get(mode, mode),
                "from": start,
                "to": end,
                "route": l.get("route") or "",
                "route_color": l.get("routeColor") or "",
                "distance_m": l.get("distance") or 0,
                "duration_s": l.get("sectionTime") or 0,
                "stop_count": max(0, len(stops) - 1),
                "stops": [s.get("stationName", "") for s in stops],
                "walk_steps": [
                    {
                        "text": s.get("description", ""),
                        "road": s.get("streetName", ""),
                        "distance_m": s.get("distance", 0),
                        "duration_s": 0,
                        "facility": "",
                    }
                    for s in (l.get("steps") or [])
                ],
                "facilities": count_facilities(walk_texts),
            }
        )
    return out


def summarize_transit(itinerary):
    fare = ((itinerary.get("fare") or {}).get("regular") or {}).get("totalFare")
    return {
        "fare_krw": fare,
        "transfer_count": itinerary.get("transferCount"),
        "walk_distance_m": itinerary.get("totalWalkDistance"),
        "walk_duration_s": itinerary.get("totalWalkTime"),
    }
