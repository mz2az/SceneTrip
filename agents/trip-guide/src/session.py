"""한 번의 대화가 들고 있는 상태.

서버가 기억하는 것이 아니라 **대화 하나가 들고 있다가 매 턴 프롬프트에 넣어
준다.** 모델은 턴마다 백지에서 시작하므로, 담은 지점이 3 곳이라는 사실을 아는
방법은 매 턴 코드가 넣어 주는 것뿐이다
(01_Raw/정승길/(3주차)경로탭 개발/06_떠 있는 챗봇과 맛집 추천 (v6).md §6-1, §7-1).

여기 담기는 것 —

    here    지금 어디 있는가. 「이 근처」 의 기준점이다
    stops   지금 일차의 지점들. **지도의 번호 핀과 같은 번호**다 (앱이 매 턴 보낸다)
    picked  사용자가 화면에서 고른 곳 (앱이 매 턴 보낸다)
    plan    편집 중인 일정 (앱이 매 턴 보낸다)
    shown   이번 대화에서 실제로 보여 준 장소들. 모델이 이름으로 지목할 수 있는 범위다
    effects 이번 턴에 백엔드가 저장해야 할 것
    ui      이번 턴에 앱이 화면에서 해야 할 것

**앞의 넷은 기억하지 않는다.** 앱이 요청마다 `context` 로 보내 주고, 우리는 매 턴
그것으로 갈아 끼운다(`adopt_context`). 화면이 정본이고 우리 기억은 낡을 수 있기
때문이다 — 사용자가 편집 화면에서 지운 곳을 되살리거나, 지도의 2 번과 다른 곳을
「2 번」 이라고 부르는 일이 여기서 생겼다 (MZ2AZ-318 · MZ2AZ-320).

`shown` 만 우리가 들고 있는다. 계약에 이 칸이 없고, 모델이 이름을 지어내기
때문이다 — 4,700 곳 전체에서 찾게 하면 지어낸 이름이 엉뚱한 동명 장소에 우연히
걸린다 (v5 문서 §5-1).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .places import Place, PlaceSource
from .planner import Plan, plan_from_api


@dataclass
class Anchor:
    """「이 근처」 의 기준점. 사람이 읽을 이름과 좌표를 함께 들고 있다."""

    label: str
    lat: float
    lng: float


@dataclass
class Stop:
    """화면의 지점 하나. 계약 `GuideStop` 그대로다.

    **번호는 우리가 매기지 않는다.** 앱이 지도에 그린 번호를 그대로 받는다 —
    순서를 바꾸거나 「동선 최적화」를 누르면 번호가 바뀌는데, 모델이 보는 번호와
    지도의 번호가 다르면 「2 번 주변」이 엉뚱한 곳을 가리킨다.
    """

    number: int
    name: str
    lat: float | None = None
    lng: float | None = None
    category: str = ""
    visited: bool = False

    def anchor(self) -> Anchor | None:
        if self.lat is None or self.lng is None:
            return None
        return Anchor(self.name, self.lat, self.lng)


def _stop(raw: dict) -> Stop | None:
    if not isinstance(raw, dict) or not str(raw.get("name") or "").strip():
        return None
    lat, lng = raw.get("latitude"), raw.get("longitude")
    return Stop(
        number=int(raw.get("number") or 0),
        name=str(raw["name"]).strip(),
        lat=float(lat) if lat is not None else None,
        lng=float(lng) if lng is not None else None,
        category=str(raw.get("category") or ""),
        visited=bool(raw.get("visited")),
    )


@dataclass
class Session:
    book: PlaceSource
    here: Anchor | None = None
    stops: list[Stop] = field(default_factory=list)
    """지금 일차의 지점들. 매 턴 `context.stops` 로 갈아 끼운다."""

    picked: Stop | None = None
    """사용자가 화면에서 고른 곳. 모델은 「선택」 으로 지목한다."""

    shown: list[Place] = field(default_factory=list)
    shown_pois: list[dict] = field(default_factory=list)
    """이번 대화에서 보여 준 **편의시설**. 촬영지와 따로 둔다.

    응답의 `places` 에 실어 지도에 핀을 찍기 위한 것이다 — 예전에는 이것이 없어서
    「근처 카페」 에 이름은 말하면서 화면에는 아무것도 안 뜨는 일이 있었다.

    **이름 지목(`find_shown`)에는 쓰지 않는다.** 촬영지와 편의시설은 다른 표라
    id 가 겹칠 수 있고(계약 `GuidePlace`), 카페를 코스 항목으로 담으면 엉뚱한
    촬영지가 담긴다.
    """
    effects: list[dict] = field(default_factory=list)
    """이번 턴에 쌓인 **상태 변경 지시**. 백엔드가 DB 에 반영한다.

    챗봇은 떠 있는 시트 안에 있고 코스와 지도는 그 바깥에 있다. 「2일차에서 빼 줘」 는
    말로 답해서 끝날 일이 아니라 **바깥이 바뀌어야** 끝나는 일이다. 그런데 에이전트는
    DB 를 만지지 않으므로(CLAUDE.md §5), 무엇을 바꿔야 하는지를 여기에 적어 올려 보낸다.

    계약은 `schemas/effects.json` 한 벌뿐이다.
    """

    ui: list[dict] = field(default_factory=list)
    """이번 턴에 쌓인 **화면 지시**. 백엔드는 통과시키고 앱이 수행한다.

    **의도만 적는다.** 「2일차를 보여 줘」 라고 말하고 어느 화면을 어떻게 띄울지는
    앱이 정한다. 좌표나 화면 전환 절차를 여기서 정하면 iOS 와 Android 가 갈리고,
    앱을 고칠 때마다 에이전트를 같이 고쳐야 한다.
    """

    plan: Plan | None = None
    """마지막으로 짠 일정.

    **일정을 세션이 들고 있어야 대화로 고칠 수 있다.** 예전에는 `plan_course` 가
    결과를 모델에게만 주고 버렸다. 그러면 「2일차에서 개뿔 빼 줘」 를 처리하려고
    모델이 일정을 기억에서 되짚어야 하고, 그 되짚기가 곧 환각이 들어오는 자리다.
    고치는 대상은 코드가 들고 있는 이 객체 하나뿐이다.
    """

    def adopt_context(self, context: dict | None) -> None:
        """앱이 보낸 화면 상태를 이번 턴의 정본으로 삼는다 (계약 `GuideContext`).

        **화면이 정본이다.** 우리가 들고 있는 것은 낡을 수 있다 — 사용자가 편집
        화면에서 손으로 지운 것도, 「동선 최적화」로 번호가 바뀐 것도 서버로 나가지
        않는다(계약 `PUT /courses/{id}` — 「완료」가 부르는 하나뿐인 요청). 그래서
        매 요청마다 갈아 끼운다.

        **안 보내면 없는 것으로 본다.** 지난 턴의 것을 몰래 이어 쓰지 않는다.
        「짜 둔 일정이 없다」 고 거절하는 편이, 사용자가 보고 있는 것과 다른 일정을
        말없이 고치는 것보다 낫다. 덤으로 이 창구가 상태를 거의 안 들게 되어,
        인스턴스를 여러 개 띄워도 답이 갈리지 않는다.
        """
        ctx = context or {}

        raw_plan = ctx.get("plan")
        self.plan = plan_from_api(raw_plan, self.book) if raw_plan else None

        self.stops = [s for s in map(_stop, ctx.get("stops") or []) if s is not None]
        self.picked = _stop(ctx.get("picked") or {})

    # ── 보여 준 것 기억하기 ───────────────────────────────────────────────────

    def remember(self, places: list[Place]) -> None:
        """도구가 돌려준 장소를 「지목 가능한 것」 목록에 넣는다.

        최근 60 곳만 남긴다. 대화가 길어지면 프롬프트가 무한정 커지는데,
        사용자가 20 턴 전에 본 장소를 「거기」 라고 부르는 일은 없다.
        """
        for p in places:
            if p not in self.shown:
                self.shown.append(p)
        if len(self.shown) > 60:
            self.shown = self.shown[-60:]

    def remember_pois(self, rows: list[dict]) -> None:
        """보여 준 편의시설을 기억한다. 지도 핀에만 쓴다."""
        seen = {r.get("id") for r in self.shown_pois}
        for r in rows:
            if r.get("id") not in seen:
                self.shown_pois.append(r)
                seen.add(r.get("id"))
        if len(self.shown_pois) > 60:
            self.shown_pois = self.shown_pois[-60:]

    # ── 바깥 세상에 내보낼 지시 ───────────────────────────────────────────────

    def emit(self, **effect) -> None:
        """상태를 바꿔야 한다고 알린다. 실제 쓰기는 백엔드가 한다."""
        self.effects.append(effect)

    def show(self, **directive) -> None:
        """화면을 바꿔 달라고 알린다. 실제 그리기는 앱이 한다."""
        self.ui.append(directive)

    def clear_outbox(self) -> None:
        """턴이 시작될 때 비운다.

        **누적되면 안 된다.** 지난 턴의 「2일차를 보여 줘」 가 이번 턴에 또 나가면,
        사용자가 다른 것을 물었는데 화면이 엉뚱한 데로 튄다.
        """
        self.effects = []
        self.ui = []

    # ── 지목 풀기 ─────────────────────────────────────────────────────────────

    def resolve_anchor(self, near: str) -> tuple[Anchor | None, str | None]:
        """`near` 문자열을 기준점으로 바꾼다. (기준점, 오류메시지) 를 돌려준다.

        받아들이는 것 셋 —

            "현위치" · "here"   `/here` 로 설정해 둔 위치
            "2"                 담은 지점 2 번
            "경복궁"            이번 대화에서 보여 준 장소의 이름

        **못 풀면 조용히 지도 한가운데로 떨어지지 않는다.** 예전에 그렇게
        만들었더니 여의도 1 번 핀을 물었는데 마포 음식점이 나왔고, 모델은 그것을
        「1번 주변」 이라고 불렀다 (v6 문서 §6-4). 틀린 답이 맞는 답의 얼굴을
        하고 나오는 것이 가장 나쁘다.
        """
        key = (near or "").strip()
        if not key:
            return None, "기준이 될 위치를 받지 못했다"

        if key.lower() in ("현위치", "여기", "here", "current"):
            if self.here is None:
                return None, "현재 위치가 설정되어 있지 않다"
            return self.here, None

        if key in ("선택", "고른 곳", "picked"):
            if self.picked is None:
                return None, "화면에서 고른 곳이 없다"
            anchor = self.picked.anchor()
            if anchor is None:
                return None, f"{self.picked.name} 에는 좌표가 없어 주변을 찾을 수 없다"
            return anchor, None

        if key.isdigit():
            # **앱이 보낸 번호로 찾는다.** 목록의 몇 번째가 아니다 — 다녀온 곳을
            # 접어 두면 화면의 3 번이 목록의 두 번째일 수 있다.
            idx = int(key)
            if not self.stops:
                return None, "지금 화면에 번호가 붙은 지점이 없다"
            hit = next((s for s in self.stops if s.number == idx), None)
            if hit is None:
                numbers = ", ".join(str(s.number) for s in self.stops)
                return None, f"「{idx}」 번 지점이 없다 (화면에 있는 번호: {numbers})"
            anchor = hit.anchor()
            if anchor is None:
                return None, f"{hit.name} 에는 좌표가 없어 주변을 찾을 수 없다"
            return anchor, None

        # 이름으로 지목. 화면의 지점이 먼저다 — 같은 이름이 둘이면 사용자가
        # 보고 있는 쪽을 뜻한다.
        for st in self.stops:
            if st.name == key and st.anchor() is not None:
                return st.anchor(), None

        pool = self.shown
        for p in pool:
            if p.name == key:
                break
        else:
            p = None
        if p is None:
            from .places import norm

            k = norm(key)
            for cand in pool:
                if norm(cand.name) == k:
                    p = cand
                    break
        if p is None:
            return None, f"「{key}」 는 이번 대화에서 보여 준 적이 없는 이름이다"
        if not p.has_coords():
            return None, f"{p.name} 에는 좌표가 없어 주변을 찾을 수 없다"
        return Anchor(p.name, p.lat, p.lng), None

    def find_shown(self, name: str) -> Place | None:
        """이번 대화에서 보여 준 것 중에서 이름으로 찾는다. 없으면 전체에서 찾는다."""
        from .places import norm

        k = norm(name)
        for p in self.shown:
            if norm(p.name) == k:
                return p
        return self.book.resolve(name)

    # ── 프롬프트에 실을 맥락 ──────────────────────────────────────────────────

    def context_block(self) -> str:
        """매 턴 시스템 메시지 뒤에 붙는 맥락.

        **좌표를 넣지 않는다.** 숫자를 보여 주면 모델이 그것으로 거리를 계산하려
        들고, 그 계산은 틀린다 (v6 문서 §6-2). 기준점을 좌표로 바꾸는 일은
        `resolve_anchor` 가 한다.
        """
        lines = ["## 지금 상태"]
        lines.append(
            f"- 현재 위치: {self.here.label if self.here else '설정되지 않음'}"
        )

        if self.stops:
            lines.append("- 지금 화면의 지점 (이 번호로 지목할 수 있다):")
            for st in self.stops:
                mark = " (다녀옴)" if st.visited else ""
                kind = f" · {st.category}" if st.category else ""
                lines.append(f"    {st.number}번 — {st.name}{kind}{mark}")
        else:
            lines.append("- 지금 화면의 지점: 없음")

        if self.picked is not None:
            lines.append(f"- 사용자가 고른 곳: {self.picked.name} (「선택」 으로 지목)")

        if self.shown:
            names = ", ".join(p.name for p in self.shown[-20:])
            lines.append(f"- 이번 대화에서 보여 준 장소: {names}")

        if self.plan is not None:
            titles = ", ".join(self.plan.request.titles)
            lines.append(
                f"- 짜 둔 일정: 「{titles}」 {len(self.plan.days)}일 "
                f"(고치려면 revise_plan 을 불러라)"
            )
            for d in self.plan.days:
                names = ", ".join(leg.place.name for leg in d.legs) or "비어 있음"
                lines.append(f"    {d.day}일차 — {names}")
        return "\n".join(lines)
