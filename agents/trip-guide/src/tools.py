"""모델이 부를 수 있는 도구들.

정의는 `agents/trip-guide/schemas/tools.json` 에 있고 이 파일은 그것을 읽어 쓴다.
정의를 코드에 또 적지 않는 이유는, 두 벌이 되면 한쪽만 고치는 실수가 반드시 나기
때문이다.

계약 파일을 공용 `contracts/schemas/` 가 아니라 모듈 안에 두는 이유는, 이 도구들이
아직 이 에이전트 안에서만 쓰이기 때문이다. 다른 모듈이 같은 도구를 부르게 되면
그때 공용으로 올린다.

이 층이 지키는 규칙 둘 —

**하나. 모델에게 좌표와 점수를 주지 않는다.** 좌표를 보여 주면 모델이 그것으로
거리를 계산하려 들고, 점수를 보여 주면 나중에 그 숫자를 조금씩 바꿔 말한다.
줄 수 없는 것을 아예 안 주면 틀릴 수가 없다.

**둘. 못 하는 요청에는 결과를 아예 주지 않는다.** 경고만 붙여 결과를 함께 주면
모델이 경고를 무시하고 그 결과를 설명해 버린다 — 없는 3 번 지점을 물었을 때
"3번 지점 주변입니다" 라고 답한 일이 두 번 다 그랬다
(01_Raw/정승길/(3주차)경로탭 개발/06_떠 있는 챗봇과 맛집 추천 (v6).md §6-3).
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from .places import Place
from .planner import (
    PlanError,
    PlanRequest,
    Revision,
    make_plan,
    move_stop,
    plan_to_dict,
    revise_day,
)
from .session import Session

# 계약 파일의 위치. 모듈 안이라 이 파일 기준 한 단계 위다.
_CONTRACT = Path(__file__).resolve().parent.parent / "schemas" / "tools.json"


def load_tool_specs(path: Path | None = None) -> list[dict[str, Any]]:
    """계약 파일에서 도구 정의를 읽어 온다. 모델에게 그대로 보낼 모양이다."""
    src = path or _CONTRACT
    if not src.is_file():
        raise FileNotFoundError(f"도구 계약 파일이 없다: {src}")
    return json.loads(src.read_text(encoding="utf-8"))["tools"]


# ── 인자 검증 ─────────────────────────────────────────────────────────────────
#
# JSON Schema 를 통째로 해석하지 않는다. 우리 계약이 쓰는 것만 본다 —
# type · required · enum · minimum · maximum · default · additionalProperties ·
# array(items · minItems · maxItems).
# 외부 라이브러리를 하나 더 들이는 것보다 이 60 줄이 낫다고 판단했다.


class ToolArgError(ValueError):
    """모델이 보낸 인자가 계약과 다를 때."""


_TYPES = {
    "string": str,
    "integer": int,
    "number": (int, float),
    "boolean": bool,
    "array": list,
}


def validate_args(schema: dict[str, Any], raw: dict[str, Any]) -> dict[str, Any]:
    """인자를 검증하고 기본값을 채운 새 사전을 돌려준다."""
    props: dict[str, Any] = schema.get("properties", {})
    out: dict[str, Any] = {}

    if not schema.get("additionalProperties", True):
        extra = set(raw) - set(props)
        if extra:
            raise ToolArgError(f"모르는 인자다: {', '.join(sorted(extra))}")

    for key in schema.get("required", []):
        if key not in raw or raw[key] in (None, ""):
            raise ToolArgError(f"인자 「{key}」 가 없다")

    for key, spec in props.items():
        if key not in raw or raw[key] is None:
            if "default" in spec:
                out[key] = spec["default"]
            continue
        value = raw[key]

        want = spec.get("type")
        if (
            want == "integer"
            and isinstance(value, str)
            and value.strip().lstrip("-").isdigit()
        ):
            value = int(value)  # 모델이 숫자를 문자열로 보내는 일이 잦다
        if want == "array" and isinstance(value, str):
            # 목록 자리에 문자열 하나를 보내는 일도 잦다. 「도깨비」 하나를 물었을
            # 때 titles="도깨비" 로 오는데, 그것 때문에 도구를 못 부르게 하는 것은
            # 손해다 — 뜻이 분명하므로 감싸서 받는다.
            value = [value]
        if want in _TYPES and not isinstance(value, _TYPES[want]):
            raise ToolArgError(
                f"인자 「{key}」 는 {want} 여야 하는데 {type(value).__name__} 이 왔다"
            )

        if want == "array":
            item_type = (spec.get("items") or {}).get("type")
            cleaned = []
            for v in value:
                if item_type in _TYPES and not isinstance(v, _TYPES[item_type]):
                    raise ToolArgError(
                        f"인자 「{key}」 의 원소는 {item_type} 여야 한다"
                    )
                if isinstance(v, str):
                    v = v.strip()
                    if not v:
                        continue
                cleaned.append(v)
            value = cleaned
            if len(value) < int(spec.get("minItems", 0)):
                raise ToolArgError(
                    f"인자 「{key}」 에 값이 {spec['minItems']} 개는 있어야 한다"
                )
            if "maxItems" in spec:
                value = value[: int(spec["maxItems"])]

        if "enum" in spec and value not in spec["enum"]:
            raise ToolArgError(f"인자 「{key}」 는 {spec['enum']} 중 하나여야 한다")
        if "minimum" in spec and value < spec["minimum"]:
            value = spec["minimum"]
        if "maximum" in spec and value > spec["maximum"]:
            value = spec["maximum"]
        if "minLength" in spec and len(str(value).strip()) < spec["minLength"]:
            raise ToolArgError(f"인자 「{key}」 가 비어 있다")

        out[key] = value
    return out


# ── 모델에게 건네는 모양 ──────────────────────────────────────────────────────


def _brief(p: Place, distance_m: int | None = None) -> dict[str, Any]:
    """장소 하나를 모델이 읽을 만큼만 줄인다. 좌표와 점수는 뺀다."""
    out: dict[str, Any] = {"이름": p.name}
    if p.address:
        out["주소"] = p.address
    if p.kind:
        out["종류"] = p.kind
    if distance_m is not None:
        out["거리"] = f"{distance_m}m"
    out["작품"] = p.titles[:3]
    scene = next((s.description for s in p.scenes if s.description), "")
    if scene:
        out["장면"] = scene
    return out


def _place_id(place: Place) -> Any:
    """장소 하나의 **id**. 없으면 None 이다.

    **이름으로 대신하지 않는다.** 예전에는 id 가 없으면 이름을 넣었는데, 그러면
    백엔드가 이름을 id 로 알고 쓰기를 시도하고 조용히 엉뚱한 행에 걸린다. 없으면
    없다고 말하고 백엔드가 시끄럽게 거절하게 두는 편이 낫다.

    **숫자로 보낸다.** scene-api 의 `placeId` 는 계약상 integer 다
    (contracts/openapi §PlaceSummary.id). 우리가 문자열로 들고 있을 뿐이라
    내보낼 때 되돌린다. CSV 창구의 `st_04123` 같은 값은 숫자가 아니므로 그대로
    나가는데, 그 경로는 시험용이라 백엔드에 닿지 않는다.
    """
    raw = place.place_id
    if not raw:
        return None
    return int(raw) if raw.isdigit() else raw


def _ids(places: list[Place]) -> list[Any]:
    """화면 지시에 실을 id 목록. **id 가 없는 장소는 빠진다.**

    앱이 풀 수 없는 값을 보내면 지도가 조용히 아무 일도 안 한다.
    """
    return [i for i in (_place_id(p) for p in places) if i is not None]


def _refuse(reason: str) -> dict[str, Any]:
    """결과 없이 이유만 돌려준다. 지어낼 재료 자체를 주지 않는 것이 요점이다."""
    return {
        "결과없음": reason,
        "할 일": "목록을 지어내지 말고 이 이유를 사용자에게 그대로 전해라",
    }


# ── 실행 ──────────────────────────────────────────────────────────────────────


def _revision_result(rev: Revision) -> dict[str, Any]:
    """수정 한 번의 결과를 모델이 읽을 사전으로 바꾼다.

    **넘쳐도 말없이 자르지 않는다.** 사용자가 넣으라고 한 곳을 코드가 도로 빼면
    화면의 일정과 사용자가 기억하는 일정이 갈린다. 사실을 알리고 사용자에게 묻는다.

    다만 **사실만 알리고 끝내지도 않는다.** 위반을 알릴 때 「허용 가능한 대안」 을
    함께 준 쪽이 고치기 성공률이 크게 높았다는 통제 실험이 있다(28%→72%,
    arXiv 2607.14167). 그래서 「무엇을 빼면 얼마가 빠지는지」 를 같이 싣는다.
    """
    out = plan_to_dict(rev.plan)
    changed: dict[str, Any] = {
        "일차": rev.day,
        "뺀 곳": rev.removed or "없음",
        "넣은 곳": rev.added or "없음",
    }
    if rev.already:
        changed["이미 있던 곳"] = rev.already
    if rev.moved_from is not None:
        changed = {
            "옮긴 것": rev.added[0] if rev.added else "",
            "어디서": f"{rev.moved_from}일차",
            "어디로": f"{rev.day}일차",
        }
    out["고친 것"] = changed

    warn: list[str] = []
    if rev.over_budget:
        warn.append(
            f"{rev.day}일차가 {rev.minutes_used}분이 되어 "
            f"하루 예산 {rev.budget}분을 넘는다"
        )
    if rev.over_stops:
        warn.append(
            f"{rev.day}일차 정지점이 {rev.stops} 곳으로 "
            f"이 속도의 상한 {rev.max_stops} 곳을 넘는다"
        )

    if warn:
        out["경고"] = warn
        if rev.drop_candidates:
            out["빼면 좋은 후보"] = [
                f"{name} (빼면 {saved}분 준다)" for name, saved in rev.drop_candidates
            ]
        out["할 일"] = (
            "고친 일정은 위와 같다. 다만 " + " 그리고 ".join(warn) + ". "
            "이 사실을 사용자에게 그대로 알리고, 위 후보를 보여 주며 "
            "무엇을 뺄지 물어라. 네가 임의로 빼지 마라."
        )
    else:
        out["할 일"] = (
            "위 시각·거리·순서는 다시 계산된 값이다. 그대로 옮겨 말하고 "
            "다시 계산하지 마라."
        )
    return out


def run_tool(name: str, raw_args: dict[str, Any], session: Session) -> dict[str, Any]:
    """도구 하나를 실행한다. 돌려주는 사전이 그대로 모델에게 간다."""
    specs = {t["function"]["name"]: t["function"] for t in load_tool_specs()}
    spec = specs.get(name)
    if spec is None:
        return _refuse(f"「{name}」 라는 도구는 없다")

    try:
        args = validate_args(spec["parameters"], raw_args or {})
    except ToolArgError as exc:
        return _refuse(str(exc))

    book = session.book

    if name == "search_places":
        hits = book.search(args["query"], args.get("limit", 8))
        if not hits:
            return _refuse(f"「{args['query']}」 로 찾은 촬영지가 없다")
        session.remember(hits)
        session.show(op="map.focus", placeIds=_ids(hits))
        return {"찾은 곳": [_brief(p) for p in hits]}

    if name == "list_title_places":
        matched, hits = book.by_title(args["title"], args.get("limit", 8))
        if not hits:
            return _refuse(f"「{args['title']}」 라는 작품의 촬영지 데이터가 없다")
        session.remember(hits)
        session.show(op="map.focus", placeIds=_ids(hits))
        return {
            "작품": matched,
            "안내": "대표적인 순서다. 앞에 오는 것이 더 대표적인 촬영지다",
            "촬영지": [_brief(p) for p in hits],
        }

    if name == "places_near":
        anchor, err = session.resolve_anchor(args["near"])
        if err:
            # 기준점을 못 풀면 여기서 끝낸다. 지도 한가운데로 대신 떨어지지 않는다.
            return _refuse(err)
        radius = args.get("radius_m", 2000)
        found = book.near(
            anchor.lat,
            anchor.lng,
            radius,
            args.get("limit", 8),
            sort=args.get("sort", "distance"),
        )
        if not found:
            return _refuse(f"{anchor.label} 에서 {radius}m 안에는 촬영지가 없다")
        session.remember([p for p, _ in found])
        session.show(op="map.focus", placeIds=_ids([p for p, _ in found]))
        return {
            "기준": anchor.label,
            "반경": f"{radius}m",
            "찾은 곳": [_brief(p, d) for p, d in found],
        }

    if name == "place_detail":
        p = session.find_shown(args["name"])
        if p is None:
            return _refuse(f"「{args['name']}」 라는 장소를 찾을 수 없다")
        # 목록에는 장면 설명과 네이버 링크가 없다. 한 곳을 콕 집어 물었을
        # 때만 창구에 한 번 더 묻는다.
        p = book.enrich(p)
        session.remember([p])
        session.show(op="place.card", placeId=_place_id(p))
        scenes = [
            {"작품": s.title, "장면": s.description or "장면 설명이 수집되지 않았다"}
            for s in p.scenes[:5]
        ]
        out: dict[str, Any] = {
            "이름": p.name,
            "주소": p.address or "주소 미상",
            "장면들": scenes,
        }
        if p.kind:
            out["종류"] = p.kind
        if p.naver_url:
            out["네이버 지도"] = p.naver_url
        return out

    if name == "update_cart":
        p = session.find_shown(args["name"])
        if p is None:
            return _refuse(f"「{args['name']}」 라는 장소를 찾을 수 없어 담을 수 없다")
        if args["action"] == "add":
            if p in session.cart:
                return {
                    "결과": f"{p.name} 은 이미 담겨 있다",
                    "담긴 곳": [x.name for x in session.cart],
                }
            session.cart.append(p)
            session.emit(op="cart.add", placeId=_place_id(p), name=p.name)
            session.show(op="map.focus", placeIds=_ids(session.cart))
            return {
                "결과": f"{p.name} 을 담았다",
                "담긴 곳": [x.name for x in session.cart],
            }
        if p not in session.cart:
            return _refuse(f"{p.name} 은 담겨 있지 않다")
        session.cart.remove(p)
        session.emit(op="cart.remove", placeId=_place_id(p), name=p.name)
        session.show(op="map.focus", placeIds=_ids(session.cart))
        return {"결과": f"{p.name} 을 뺐다", "담긴 곳": [x.name for x in session.cart]}

    if name == "draft_course":
        if len(session.cart) < 2:
            return _refuse("담은 곳이 두 곳보다 적어 동선을 만들 수 없다")
        route = book.order_by_walk(session.cart)
        legs: list[dict[str, Any]] = []
        for i, p in enumerate(route):
            step: dict[str, Any] = {
                "순서": i + 1,
                "이름": p.name,
                "주소": p.address or "주소 미상",
            }
            if i > 0:
                d = book.leg_meters(route[i - 1], p)
                step["앞 지점에서"] = (
                    f"약 {d}m" if d is not None else "거리를 잴 수 없다"
                )
            legs.append(step)
        session.show(op="route.draw", placeIds=_ids(route))
        return {
            "동선": legs,
            "주의": "직선 거리다. 실제 도보 거리와 소요 시간은 아직 모른다 — 지어내지 마라",
        }

    if name == "plan_course":
        # 여기가 「LLM 샌드위치」 의 가운데다. 모델이 도구를 부른 순간 자연어는 이미
        # 구조화된 제약으로 바뀌어 있고(① 이해), 일정 계산은 planner.py 가 결정적으로
        # 하고(② 계획), 돌려준 결과를 모델이 말로 푼다(③ 설명).
        # 근거는 docs/design/ai-course-planner.md §2.
        start: tuple[float, float] | None = None
        start_label = ""
        if args.get("start"):
            anchor, err = session.resolve_anchor(args["start"])
            if err:
                # 출발점을 못 풀면 조용히 지도 한가운데로 떨어지지 않는다.
                return _refuse(err)
            start, start_label = (anchor.lat, anchor.lng), anchor.label

        req = PlanRequest(
            titles=args["titles"],
            days=args.get("days", 1),
            pace=args.get("pace", "normal"),
            start=start,
            start_label=start_label,
            must=args.get("must", []),
            avoid=args.get("avoid", []),
        )
        try:
            plan = make_plan(session.book, req)
        except PlanError as exc:
            return _refuse(str(exc))

        for day in plan.days:
            session.remember([leg.place for leg in day.legs])

        # **일정을 세션이 들고 있는다.** 이것이 있어야 다음 턴에 revise_plan 이
        # 고칠 대상을 갖는다. 모델의 기억에서 일정을 되짚게 하면 그 자리가 곧
        # 환각이 들어오는 자리다 (session.py 의 plan 주석).
        session.plan = plan

        # **채팅창 바깥이 바뀌어야 끝나는 일이다.** 일정을 말로만 읽어 주고 끝내면
        # 사용자는 그것을 코스 탭에서 다시 만들어야 한다.
        #
        # `save` 가 아니라 `draft` 인 이유 — 코스는 편집 중에 서버로 나가지 않는다는
        # 것이 이 저장소의 규칙이다(계약 PUT /courses/{id}: "편집 화면의 「완료」가
        # 부르는 하나뿐인 요청"). 챗봇이 짰다고 바로 저장하면 사용자가 「취소」를
        # 눌러도 이미 저장돼 있게 된다. 우리는 초안을 건넬 뿐이고, 저장 시점은
        # 사용자가 정한다.
        session.emit(op="plan.draft", plan=plan_to_dict(plan))
        session.show(op="course.open", day=1)
        session.show(op="sheet.collapse")

        out = plan_to_dict(plan)
        if start_label:
            out["출발점"] = start_label
        out["할 일"] = (
            "위 시각·거리·순서는 계산된 값이다. 그대로 옮겨 말하고 다시 계산하지 마라. "
            "일정을 장바구니에 담고 싶은지 사용자에게 물어라."
        )
        return out

    if name == "revise_plan":
        # 대화로 고치기 (MZ2AZ-201). 여기도 가운데는 알고리즘이다 — 모델은 「어느
        # 일차에서 무엇을 빼고 넣을지」 만 정하고, 순서와 시간표는 planner 가 다시 푼다.
        if session.plan is None:
            return _refuse(
                "아직 짜 둔 일정이 없다. plan_course 로 먼저 짜고 나서 고쳐라"
            )

        add_places: list[Place] = []
        for nm in args.get("add", []):
            p = session.find_shown(nm)
            if p is None:
                return _refuse(f"「{nm}」 라는 장소를 찾을 수 없어 넣을 수 없다")
            add_places.append(p)

        try:
            rev = revise_day(
                session.plan,
                args["day"],
                add=add_places,
                remove=args.get("remove", []),
            )
        except PlanError as exc:
            return _refuse(str(exc))

        # 여기서 비로소 세션의 일정이 바뀐다. revise_day 는 원본을 건드리지 않으므로,
        # 위에서 거절당한 수정은 세션에 아무 흔적도 남기지 않는다.
        session.plan = rev.plan
        session.remember([leg.place for day in rev.plan.days for leg in day.legs])

        session.emit(
            op="plan.revise",
            day=rev.day,
            add=rev.added,
            remove=rev.removed,
            plan=plan_to_dict(rev.plan),
        )
        # **바뀐 자리를 짚어 준다.** 무엇이 달라졌는지 눈에 보이지 않으면
        # 사용자는 바뀌었는지도 모른다.
        session.show(op="course.focus", day=rev.day, changed=rev.added + rev.removed)

        return _revision_result(rev)

    if name == "move_stop":
        if session.plan is None:
            return _refuse(
                "아직 짜 둔 일정이 없다. plan_course 로 먼저 짜고 나서 옮겨라"
            )
        try:
            rev = move_stop(session.plan, args["name"], args["to_day"])
        except PlanError as exc:
            return _refuse(str(exc))
        session.plan = rev.plan
        session.emit(
            op="plan.move",
            name=args["name"],
            day=rev.moved_from,
            toDay=rev.day,
            plan=plan_to_dict(rev.plan),
        )
        session.show(op="course.focus", day=rev.day, changed=rev.added)
        return _revision_result(rev)

    return _refuse(f"「{name}」 는 아직 만들어지지 않았다")
