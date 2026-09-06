"""한 번의 대화가 들고 있는 상태.

서버가 기억하는 것이 아니라 **대화 하나가 들고 있다가 매 턴 프롬프트에 넣어
준다.** 모델은 턴마다 백지에서 시작하므로, 담은 지점이 3 곳이라는 사실을 아는
방법은 매 턴 코드가 넣어 주는 것뿐이다
(01_Raw/정승길/(3주차)경로탭 개발/06_떠 있는 챗봇과 맛집 추천 (v6).md §6-1, §7-1).

여기 담기는 것 넷 —

    here    지금 어디 있는가. 「이 근처」 의 기준점이다
    cart    오늘 코스에 담은 곳. 번호가 붙고 그 번호로 지목할 수 있다
    shown   이번 대화에서 실제로 보여 준 장소들. 모델이 이름으로 지목할 수 있는 범위다
    plan    마지막으로 짠 일차별 일정. 대화로 고치는 대상이다
    effects 이번 턴에 백엔드가 저장해야 할 것
    ui      이번 턴에 앱이 화면에서 해야 할 것

`shown` 이 있는 이유는 모델이 이름을 지어내기 때문이다. 4,700 곳 전체에서 이름을
찾게 하면 지어낸 이름이 엉뚱한 동명 장소에 우연히 걸린다 (v5 문서 §5-1).
"""

from __future__ import annotations

from dataclasses import dataclass, field

from .places import Place, PlaceSource
from .planner import Plan


@dataclass
class Anchor:
    """「이 근처」 의 기준점. 사람이 읽을 이름과 좌표를 함께 들고 있다."""

    label: str
    lat: float
    lng: float


@dataclass
class Session:
    book: PlaceSource
    here: Anchor | None = None
    cart: list[Place] = field(default_factory=list)
    shown: list[Place] = field(default_factory=list)
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

        if key.isdigit():
            idx = int(key)
            if not self.cart:
                return None, "담은 지점이 하나도 없다"
            if not (1 <= idx <= len(self.cart)):
                return (
                    None,
                    f"「{idx}」 번 지점이 없다 (담은 것은 {len(self.cart)} 곳이다)",
                )
            p = self.cart[idx - 1]
            if not p.has_coords():
                return None, f"{p.name} 에는 좌표가 없어 주변을 찾을 수 없다"
            return Anchor(p.name, p.lat, p.lng), None

        pool = self.shown + self.cart
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
        for p in self.shown + self.cart:
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

        if self.cart:
            lines.append("- 오늘 코스에 담은 곳 (번호로 지목할 수 있다):")
            for i, p in enumerate(self.cart, 1):
                lines.append(f"    {i}번 — {p.name}")
        else:
            lines.append("- 오늘 코스에 담은 곳: 없음")

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
