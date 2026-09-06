"""성지 데이터를 읽고 고르는 층.

이 파일에는 LLM 이 없다. **장소를 고르는 계산은 전부 여기서 한다** —
거리 재기, 이름 맞추기, 인기순 줄 세우기. 모델에게 좌표 계산을 시키면 틀리기
때문이다 (01_Raw/정승길/(3주차)경로탭 개발/05_로컬 LLM 여행 가이드 (v5).md §4).

입력은 김태환이 수집·선별한 촬영지 CSV 한 장이다. 컬럼 정의와 선정 기준은
볼트의 `01_Raw/김태환/DataCollection/README_수집방법과_선정기준.md` 에 있다.
"""

from __future__ import annotations

import csv
import math
import re
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

# ── 정규화 ────────────────────────────────────────────────────────────────────
#
# CSV 의 작품명에는 공백이 없다("눈물의여왕"). 사용자는 공백을 넣어 친다
# ("눈물의 여왕"). 그래서 비교 전에 공백과 문장부호를 모두 지운다.

_PUNCT = re.compile(r"[\s\-_·・,.!?'\"()\[\]{}:;/\\]+")


def norm(text: str | None) -> str:
    """비교용 정규화. 공백·문장부호를 지우고 소문자로 만든다."""
    if not text:
        return ""
    text = unicodedata.normalize("NFKC", text)
    return _PUNCT.sub("", text).lower()


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """두 좌표 사이 거리를 미터로 돌려준다."""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


# ── 자료 구조 ─────────────────────────────────────────────────────────────────
#
# CSV 는 한 행이 (작품 × 장소) 하나다. 같은 장소가 여러 작품에 나오면 행이 여러
# 개다. 챗봇이 다루기 좋은 단위는 **장소** 라서, 적재하면서 장소로 묶고 그 밑에
# 작품별 장면(Scene)을 매단다.


@dataclass(frozen=True)
class Scene:
    """한 작품이 이 장소를 어떻게 썼는가."""

    title: str
    title_en: str
    category: str  # drama · movie
    cast: str
    description: str  # 장면 설명. 비어 있는 행이 40% 다
    rank_in_title: int  # 그 작품 안에서 이 장소가 몇 번째로 대표적인가 (1 이 으뜸)
    popularity: float  # 그 작품 안에서 0~100. **작품끼리는 비교하면 안 된다**


@dataclass
class Place:
    """장소 하나. 같은 이름·주소를 가진 CSV 행들을 묶은 결과다."""

    place_id: str
    name: str
    address: str
    lat: float | None
    lng: float | None
    kind: str  # place_type. 2,528 행이 비어 있어 필수로 두지 않는다
    naver_url: str
    image_url: str
    tier: str  # S · A · B · C — 김태환의 등급
    selected: bool  # is_selected=Y. 991 곳뿐인 엄선 목록
    mentions: int  # mention_total. **작품을 가로질러 비교할 수 있는 유일한 값**
    scenes: list[Scene] = field(default_factory=list)

    @property
    def titles(self) -> list[str]:
        """이 장소에 나온 작품 이름들. 중복 없이 등장 순서대로."""
        out: list[str] = []
        for s in self.scenes:
            if s.title not in out:
                out.append(s.title)
        return out

    @property
    def best_rank(self) -> int:
        """어느 작품에서든 가장 높았던 순위. 없으면 큰 수를 준다."""
        return min((s.rank_in_title for s in self.scenes), default=9999)

    def has_coords(self) -> bool:
        return self.lat is not None and self.lng is not None


_TIER_WEIGHT = {"S": 3.0, "A": 2.0, "B": 1.0, "C": 0.0}


def _f(value: str | None) -> float | None:
    try:
        return float((value or "").strip())
    except ValueError:
        return None


def _i(value: str | None, default: int = 9999) -> int:
    try:
        return int(float((value or "").strip()))
    except ValueError:
        return default


class PlaceSource:
    """장소를 가져오는 창구의 공통 뼈대.

    속은 두 가지다 — `CsvPlaceBook` 은 수집 CSV 를 직접 읽고,
    `SceneApiPlaceBook`(sceneapi.py) 은 scene-api 에 HTTP 로 묻는다.
    **어느 쪽을 쓸지는 실행할 때 명시적으로 고른다.** 서버가 안 뜨면 조용히
    CSV 로 넘어가는 식으로는 만들지 않았다 — 그렇게 하면 오래된 데이터로 답하고도
    사용자는 그것을 알 방법이 없다.

    아래 두 계산은 어느 창구를 쓰든 같아서 여기 둔다. 좌표만 있으면 되는 일이고,
    좌표는 양쪽 다 준다.
    """

    def order_by_walk(self, places: list[Place]) -> list[Place]:
        """담은 장소들을 가까운 것부터 이어 붙인 순서로 돌려준다.

        가장 북쪽에서 출발해 매번 가장 가까운 다음 곳으로 가는 방식이다
        (탐욕적 최근접). 최적해가 아니지만 하루 코스 크기(5~10 곳)에서는 사람이
        보기에 납득 가능한 동선이 나오고, 무엇보다 **결과가 매번 같다** —
        평가를 돌릴 수 있다는 뜻이다.

        좌표가 없는 장소는 순서를 정할 근거가 없으므로 뒤에 그대로 붙인다.
        """
        located = [p for p in places if p.has_coords()]
        unlocated = [p for p in places if not p.has_coords()]
        if len(located) <= 2:
            return located + unlocated

        remaining = list(located)
        current = max(remaining, key=lambda p: p.lat)
        route = [current]
        remaining.remove(current)
        while remaining:
            nxt = min(
                remaining,
                key=lambda p: haversine_m(current.lat, current.lng, p.lat, p.lng),
            )
            route.append(nxt)
            remaining.remove(nxt)
            current = nxt
        return route + unlocated

    def enrich(self, place: Place) -> Place:
        """상세 정보를 채워 넣는다. 창구에 따라 할 일이 다르다.

        CSV 는 처음부터 전부 들고 있어 그대로 돌려주면 되고, scene-api 는
        목록 응답에 장면 설명과 네이버 링크가 없어 한 번 더 물어야 한다.
        """
        return place

    def leg_meters(self, a: Place, b: Place) -> int | None:
        """두 장소 사이 직선 거리. 한쪽이라도 좌표가 없으면 None 이다.

        직선이지 도보 거리가 아니다. 실제 도보 경로는 지도 엔진이 풀 몫이고
        (v5 문서 §7-2), 여기서는 순서를 정할 만큼만 잰다.
        """
        if not (a.has_coords() and b.has_coords()):
            return None
        return round(haversine_m(a.lat, a.lng, b.lat, b.lng))

    @staticmethod
    def _quality(p: Place) -> float:
        """등급과 엄선 여부를 작은 가산점으로 바꾼다.

        일부러 작게 준다(최대 4점). 이 값이 커지면 사용자가 이름으로 콕 집어
        물은 장소가 등급에 밀려 뒤로 가는 일이 생긴다. scene-api 쪽은 등급을
        주지 않으므로 그쪽에서는 0 이 된다.
        """
        return _TIER_WEIGHT.get(p.tier, 0.0) + (1.0 if p.selected else 0.0)


class CsvPlaceBook(PlaceSource):
    """CSV 한 장을 메모리에 올려 두고 질의를 받는다.

    5,669 행짜리 파일이라 전부 올려도 몇 MB 다. 색인을 따로 만들 이유가 없어
    선형 훑기로 답한다 — 5,000 건 순회는 1 ms 안쪽이다.
    """

    def __init__(self, places: list[Place], source: Path) -> None:
        self.places = places
        self.source = source
        self._by_norm: dict[str, Place] = {}
        for p in places:
            self._by_norm.setdefault(norm(p.name), p)

    # ── 적재 ──────────────────────────────────────────────────────────────────

    @classmethod
    def load(
        cls, csv_path: str | Path, *, include_overseas: bool = False
    ) -> CsvPlaceBook:
        """CSV 를 읽어 장소 단위로 묶는다.

        김태환이 붙여 둔 품질 플래그를 여기서 적용한다. 걸러내는 이유를 각각
        적어 둔다 — 나중에 왜 이 장소가 안 나오는지 물을 때 이 표가 답이다.

        | 거르는 것 | 왜 |
        | --- | --- |
        | `dup_of` 가 찬 행 | 같은 장소를 두 번 센 것으로 판정된 행이다 (203 행) |
        | `is_overseas=Y` | 국내 여행 안내라 해외 촬영지는 기본으로 뺀다 (116 행) |
        """
        path = Path(csv_path)
        if not path.is_file():
            raise FileNotFoundError(f"성지 CSV 를 찾을 수 없다: {path}")

        merged: dict[tuple[str, str], Place] = {}
        with path.open(encoding="utf-8-sig", newline="") as fh:
            for row in csv.DictReader(fh):
                if (row.get("dup_of") or "").strip():
                    continue
                if (
                    not include_overseas
                    and (row.get("is_overseas") or "").strip().upper() == "Y"
                ):
                    continue

                name = (row.get("place_name") or "").strip()
                if not name:
                    continue
                address = (row.get("place_address") or "").strip()
                key = (norm(name), norm(address))

                place = merged.get(key)
                if place is None:
                    place = Place(
                        place_id=(row.get("id") or "").strip(),
                        name=name,
                        address=address,
                        lat=_f(row.get("place_latitude")),
                        lng=_f(row.get("place_longitude")),
                        kind=(row.get("place_type") or "").strip(),
                        naver_url=(row.get("place_naver_url") or "").strip(),
                        image_url=(row.get("place_image_url") or "").strip(),
                        tier=(row.get("tier") or "C").strip() or "C",
                        selected=(row.get("is_selected") or "").strip().upper() == "Y",
                        mentions=_i(row.get("mention_total"), 0),
                    )
                    merged[key] = place
                else:
                    # 같은 장소의 두 번째 행이다. 더 나은 값이 오면 채워 넣는다 —
                    # 행마다 채움률이 달라(place_type 은 55%, naver_url 은 72%)
                    # 첫 행만 쓰면 있는 정보를 버리게 된다.
                    place.kind = place.kind or (row.get("place_type") or "").strip()
                    place.naver_url = (
                        place.naver_url or (row.get("place_naver_url") or "").strip()
                    )
                    place.image_url = (
                        place.image_url or (row.get("place_image_url") or "").strip()
                    )
                    if place.lat is None:
                        place.lat = _f(row.get("place_latitude"))
                        place.lng = _f(row.get("place_longitude"))
                    place.selected = (
                        place.selected
                        or (row.get("is_selected") or "").strip().upper() == "Y"
                    )
                    place.mentions = max(
                        place.mentions, _i(row.get("mention_total"), 0)
                    )
                    if _TIER_WEIGHT.get(place.tier, 0) < _TIER_WEIGHT.get(
                        (row.get("tier") or "C").strip(), 0
                    ):
                        place.tier = (row.get("tier") or "C").strip()

                place.scenes.append(
                    Scene(
                        title=(row.get("title") or "").strip(),
                        title_en=(row.get("title_aliases") or "").strip(),
                        category=(row.get("title_category") or "").strip(),
                        cast=(row.get("title_cast") or "").strip(),
                        description=(row.get("scene_description") or "").strip(),
                        rank_in_title=_i(row.get("rank_in_title")),
                        popularity=_f(row.get("popularity_score")) or 0.0,
                    )
                )

        return cls(list(merged.values()), path)

    # ── 고르기 ────────────────────────────────────────────────────────────────

    def resolve(self, name: str) -> Place | None:
        """이름으로 장소 하나를 찾는다. 못 찾으면 None — 비슷한 것을 주지 않는다.

        어림짐작으로 다른 장소를 돌려주면 "커피" 사건이 난다: 이름이 짧으면
        아무 데나 걸려서, 628 m 떨어진 남의 가게를 1 위로 올렸다
        (v6 문서 §5). 그래서 정확히 같을 때와 한쪽이 다른 쪽을 통째로 품을
        때만 인정한다.
        """
        key = norm(name)
        if not key:
            return None
        hit = self._by_norm.get(key)
        if hit is not None:
            return hit
        if len(key) < 4:
            # 세 글자 이하는 부분 일치를 허용하지 않는다. 「커피」 대책이다.
            return None
        for p in self.places:
            pk = norm(p.name)
            if key in pk or pk in key:
                return p
        return None

    def search(self, query: str, limit: int = 10) -> list[Place]:
        """작품·장소·배우·주소·장면 설명을 한꺼번에 훑는다.

        점수는 **어디에 걸렸는가**로 매긴다. 장소 이름에 걸린 것이 장면 설명에
        걸린 것보다 사용자가 찾던 것일 가능성이 높다.
        """
        q = norm(query)
        if not q:
            return []

        scored: list[tuple[float, Place]] = []
        for p in self.places:
            score = 0.0
            pname = norm(p.name)
            if pname == q:
                score += 100
            elif q in pname:
                score += 60
            for s in p.scenes:
                if norm(s.title) == q or norm(s.title_en) == q:
                    score += 80
                    break
                if q in norm(s.title) or q in norm(s.title_en):
                    score += 45
                    break
            if score == 0:
                for s in p.scenes:
                    if q in norm(s.cast):
                        score += 30
                        break
            if score == 0 and q in norm(p.address):
                score += 20
            if score == 0:
                for s in p.scenes:
                    if q in norm(s.description):
                        score += 10
                        break
            if score == 0:
                continue
            scored.append((score + self._quality(p), p))

        scored.sort(key=lambda t: -t[0])
        return [p for _, p in scored[:limit]]

    def by_title(self, title: str, limit: int = 10) -> tuple[str | None, list[Place]]:
        """한 작품의 대표 촬영지를 순서대로 돌려준다.

        작품 안에서의 순위(`rank_in_title`)를 그대로 쓴다. 이 값은 김태환이
        웹 언급량을 세어 매긴 것이라 우리가 다시 계산할 이유가 없다.

        돌려주는 첫 값은 **실제로 걸린 작품명** 이다. 사용자가 "눈물의 여왕"
        이라 쳤을 때 CSV 의 "눈물의여왕" 을 찾았다는 것을 호출한 쪽이 알아야
        한다 — 모르면 못 찾았을 때와 구별이 안 된다.
        """
        q = norm(title)
        if not q:
            return None, []

        matched: str | None = None
        rows: list[tuple[int, Place]] = []
        for p in self.places:
            for s in p.scenes:
                if (
                    q == norm(s.title)
                    or q == norm(s.title_en)
                    or (len(q) >= 3 and (q in norm(s.title) or q in norm(s.title_en)))
                ):
                    matched = matched or s.title
                    rows.append((s.rank_in_title, p))
                    break

        rows.sort(key=lambda t: t[0])
        return matched, [p for _, p in rows[:limit]]

    def near(
        self,
        lat: float,
        lng: float,
        radius_m: int = 2000,
        limit: int = 10,
        *,
        sort: str = "distance",
    ) -> list[tuple[Place, int]]:
        """한 점을 중심으로 반경 안의 장소를 (장소, 거리) 로 돌려준다.

        `sort="popular"` 는 반경 안에서 언급량순으로 줄 세운다. 언급량
        (`mention_total`)은 작품을 가로질러 비교할 수 있는 유일한 값이다 —
        `popularity_score` 는 작품 안에서 100 점 만점으로 정규화된 값이라
        서로 다른 작품끼리 견주면 틀린다.
        """
        found: list[tuple[Place, int]] = []
        for p in self.places:
            if not p.has_coords():
                continue
            d = haversine_m(lat, lng, p.lat, p.lng)
            if d <= radius_m:
                found.append((p, round(d)))

        if sort == "popular":
            found.sort(key=lambda t: (-t[0].mentions - self._quality(t[0]), t[1]))
        else:
            found.sort(key=lambda t: t[1])
        return found[:limit]
