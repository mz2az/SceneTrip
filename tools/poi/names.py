"""가게 이름 — 로마자 읽기와, 확실할 때만의 영어 이름.

계획 docs/project/plans/poi-i18n-image.md §12. 영어 이름은 브랜드 사전 → 보수적 규칙 번역 순서로만 만들고,
확실하지 않으면 만들지 않는다(None). 로마자 읽기는 모든 이름에 만든다 — 번역이 아니라 읽는 법이다.
"""

import csv
import re
import unicodedata
from collections import Counter
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

# ── 국어의 로마자 표기법 ────────────────────────────────────────────────────────
#
# 한글 음절 = 0xAC00 + (초성 × 21 + 중성) × 28 + 종성.
_INITIAL = [
    "g",
    "kk",
    "n",
    "d",
    "tt",
    "r",
    "m",
    "b",
    "pp",
    "s",
    "ss",
    "",
    "j",
    "jj",
    "ch",
    "k",
    "t",
    "p",
    "h",
]
_VOWEL = [
    "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae",
    "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
]  # fmt: skip
_I_VOWEL = 20  # ㅣ — 구개음화(ㄷ·ㅌ + 이)
_INIT_NG, _INIT_N, _INIT_M, _INIT_R, _INIT_H = 11, 2, 6, 5, 18
_INIT_G, _INIT_D, _INIT_J = 0, 3, 12

# 종성 번호 → (대표음 갈래, 모음 앞에서 이어 읽을 때 (남는 받침, 다음 첫소리)).
# 갈래: K·N·T·L·M·P·NG. ㅎ 받침은 따로 표시(H)해 거센소리를 만든다.
_FINAL = {
    0: (None, ("", "")),
    1: ("K", ("", "g")), 2: ("K", ("", "kk")), 3: ("K", ("k", "s")), 4: ("N", ("", "n")),
    5: ("N", ("n", "j")), 6: ("NH", ("", "n")), 7: ("T", ("", "d")), 8: ("L", ("", "r")),
    9: ("K", ("l", "g")), 10: ("M", ("l", "m")), 11: ("L", ("l", "b")), 12: ("L", ("l", "s")),
    13: ("L", ("l", "t")), 14: ("P", ("l", "p")), 15: ("LH", ("", "r")), 16: ("M", ("", "m")),
    17: ("P", ("", "b")), 18: ("P", ("p", "s")), 19: ("T", ("", "s")), 20: ("T", ("", "ss")),
    21: ("NG", ("ng", "")), 22: ("T", ("", "j")), 23: ("T", ("", "ch")), 24: ("K", ("", "k")),
    25: ("T", ("", "t")), 26: ("P", ("", "p")), 27: ("H", ("", "")),
}  # fmt: skip
_REPR = {
    "K": "k",
    "N": "n",
    "NH": "n",
    "T": "t",
    "H": "t",
    "L": "l",
    "LH": "l",
    "M": "m",
    "P": "p",
    "NG": "ng",
}
_NASAL = {"K": "ng", "T": "n", "H": "n", "P": "m"}
_ASPIRATE = {_INIT_G: "k", _INIT_D: "t", _INIT_J: "ch"}


def _syllables(run: str) -> list[tuple[int, int, int]]:
    out = []
    for ch in run:
        o = ord(ch) - 0xAC00
        out.append((o // 588, (o % 588) // 28, o % 28))
    return out


def _romanize_run(run: str) -> str:
    """한글 음절 덩어리 하나. 음절 경계마다 (앞 받침, 뒤 첫소리) 를 소리 규칙으로 함께 정한다."""
    syl = _syllables(run)
    initials = [_INITIAL[c] for c, _, _ in syl]
    finals = [""] * len(syl)
    for i, (_c, v, f) in enumerate(syl):
        group, (keep, carry) = _FINAL[f]
        if i + 1 == len(syl):
            finals[i] = _REPR.get(group, "") if group else ""
            continue
        nc, nv, _ = syl[i + 1]
        if group is None:
            continue
        if nc == _INIT_NG:  # 받침 이어 읽기
            if (
                group == "T" and f in (7, 25) and nv == _I_VOWEL
            ):  # 구개음화 — 굳이 guji, 같이 gachi
                carry = "j" if f == 7 else "ch"
            finals[i], initials[i + 1] = keep, carry
            if group == "NG":
                initials[i + 1] = ""
            continue
        if (
            group in ("H", "NH", "LH") and nc in _ASPIRATE
        ):  # ㅎ 받침 + ㄱ·ㄷ·ㅈ → 거센소리
            finals[i] = {"H": "", "NH": "n", "LH": "l"}[group]
            initials[i + 1] = _ASPIRATE[nc]
            continue
        if nc == _INIT_R:
            if group in ("L", "LH", "N", "NH"):  # 유음화 — 신라 Silla, 별라 byeolla
                finals[i], initials[i + 1] = "l", "l"
            elif group in ("M", "NG"):  # 종로 Jongno, 침략 chimnyak
                finals[i], initials[i + 1] = _REPR[group], "n"
            else:  # 백로 baengno, 협력 hyeomnyeok
                finals[i], initials[i + 1] = _NASAL[group], "n"
            continue
        if nc in (_INIT_N, _INIT_M):
            if group in _NASAL:  # 비음화 — 왕십리 Wangsimni 의 앞 고리
                finals[i] = _NASAL[group]
                continue
            if group in ("L", "LH") and nc == _INIT_N:  # 설날 seollal
                finals[i], initials[i + 1] = "l", "l"
                continue
        # 체언의 ㄱ·ㄷ·ㅂ 뒤 ㅎ 은 적는다(묵호 Mukho) — 대표음 + h 가 그대로 나온다.
        finals[i] = _REPR[group]
    return "".join(
        initials[i] + _VOWEL[v] + finals[i] for i, (_, v, _f) in enumerate(syl)
    )


_RUN = re.compile(r"[가-힣]+|[A-Za-z0-9]+|[^\sA-Za-z0-9가-힣]+")
_JAMO = re.compile(r"[ㄱ-ㆎ]")


def romanize(name: str) -> str | None:
    """이름의 로마자 읽기. 원래 띄어쓰기를 따르고, 한글 덩어리와 영문자·숫자 덩어리 사이는 띄운다.

    `K모텔 → K Motel`, `7080라이브클럽 → 7080 Raibeukeulleop`, `도시어부 → Dosieobu`. 한글 덩어리의 첫 글자는
    대문자. 낱자 자모(ㅋㅋ)는 버린다. 이름이 비었거나 「업소명없음」 이면 None.
    """
    name = unicodedata.normalize("NFKC", name or "").strip()
    if not name or name == "업소명없음":
        return None
    words = []
    for word in _JAMO.sub("", name).split():
        out = ""
        prev_kind = None
        for m in _RUN.finditer(word):
            run = m.group(0)
            kind = (
                "hangul"
                if "가" <= run[0] <= "힣"
                else ("alnum" if run[0].isalnum() else "punct")
            )
            text = _romanize_run(run).capitalize() if kind == "hangul" else run
            if out and {prev_kind, kind} == {"hangul", "alnum"}:
                out += " "
            out += text
            prev_kind = kind
        if out:
            words.append(out)
    return " ".join(words) or None


# ── 영어 이름 — 확실할 때만 ─────────────────────────────────────────────────────
#
# 브랜드 사전과 흔한 낱말 둘뿐이다. 한식 800 메뉴명 번역은 뺐다 — 띄어쓰기 없는 이름에서 끝말을 메뉴로 읽으면
# 앞 낱말이 재료를 바꾸는 오역(매운등갈비찜 → Short Ribs, 연어육회 → Beef Tartare, 닭보쌈 → with Pork)이 검증할
# 때마다 새로 나왔다(계획 §12-4~§12-6). 흔한 낱말은 낱말마다 허용 분류를 대조해 오역이 나오지 않았다.


def _key(text: str) -> str:
    return re.sub(r"\s+", "", text)


@dataclass(frozen=True)
class Dictionaries:
    brands: dict[str, str]  # 띄어쓰기 없는 한국어 → 영어
    generic: dict[str, tuple[str, frozenset[str]]]  # 낱말 → (영어, 번역해도 되는 분류)

    @classmethod
    def load(cls, data_dir: Path) -> "Dictionaries":
        def rows(name: str):
            with (data_dir / name).open(encoding="utf-8") as fh:
                lines = [ln for ln in fh if not ln.startswith("#")]
            return list(csv.DictReader(lines, delimiter="\t"))

        brands = {}
        for r in rows("brands.tsv"):
            ko, en = _key(r["ko"] or ""), (r["en"] or "").strip()
            if not ko:
                continue
            # 영어가 있는 줄은 근거(공식 사이트 주소)가 있어야 한다 — 확인하지 않은 이름이 들어오지 못하게.
            # 영어가 빈 줄(「브랜드 아님」)은 근거가 필요 없다.
            if en and not (r.get("source") or "").strip().startswith(
                ("https://", "http://")
            ):
                raise ValueError(
                    f"brands.tsv: {r['ko']} 의 근거(source)가 없습니다 — 공식 사이트 주소(http·https)를 적으세요"
                )
            brands[ko] = en
        generic = {
            r["word"].strip(): (
                r["en"].strip(),
                frozenset(c.strip() for c in r["allowed"].split(",")),
            )
            for r in rows("generic.tsv")
        }
        return cls(brands, generic)


# 브랜드 뒤 나머지에 이것이 있으면 나머지는 버린다 — 두 번째 상호·괄호 속 지점(`;명가치킨`, `(산척점)`, `()`).
_MESSY_REST = re.compile(r"[()\[\];/]")

_SEPARATOR_END = re.compile(r"[/;,&·+]$")
# 브랜드 바로 뒤가 구분 기호면 거기서 상호가 끝난다 — 뒤는 두 번째 상호(`처갓집양념치킨;명가치킨`).
_SEPARATOR_START = re.compile(r"[()\[\];/,&·+.，]")


# `…점` 으로 끝나지만 지점이 아닌 낱말 — 입점한 곳·업태다(`폴바셋롯데김포공항점 백화점`). 지점으로 읽지 않고 버린다.
NOT_BRANCH = {"백화점", "대리점", "편의점", "할인점", "전문점"}
# 붙여 써도 지점이 아니다(`롯데백화점`, `스타벅스할인점`, `스타벅스전문점`, `족발전문점`).
_HOST_SUFFIX = ("백화점", "대리점", "편의점", "할인점", "전문점")


def _not_branch(token: str) -> bool:
    return token in NOT_BRANCH or token.endswith(_HOST_SUFFIX)


_NUMBERED = re.compile(r"^(.*?)(\d+)호점$")


def _branch(token: str) -> str:
    """`…점` 낱말 → 영어 지점. `본점` → Main Branch, `2호점` → No. 2 Branch, `옥정2호점` → Okjeong No. 2 Branch."""
    if token == "본점":
        return "Main Branch"
    m = _NUMBERED.match(token)
    if m:
        place = romanize(m.group(1)) if m.group(1) else None
        return f"{place + ' ' if place else ''}No. {m.group(2)} Branch"
    return f"{romanize(token[:-1])} Branch"


def _brand_boundary(first: str, ko: str, has_branch: bool) -> bool:
    """브랜드가 첫 낱말의 머리에서 끝나는가.

    첫 낱말이 브랜드 그대로이거나, 브랜드 뒤가 붙여 쓴 `…점`·구분 기호이거나, 뒤 낱말이 `…점` 지점일 때(원본이
    지점 이름을 두 낱말에 걸쳐 띄어 썼다 — `메가엠지씨커피야당 중앙점`). `커피빈스`·`샐러디아 강남`·`맥도날드빌딩` 은
    브랜드가 아니다(다른 가게 이름의 머리가 브랜드와 겹쳤다). 대가로 `파리바게뜨평촌귀인` 처럼 어디에도 「점」 이 없는
    지점은 브랜드로 보지 않는다 — 잘못 붙는 것보다 낫다.
    """
    if not first.startswith(ko):
        return False
    rest = first[len(ko) :]
    return (
        rest == ""
        or rest.endswith("점")
        or has_branch
        or _SEPARATOR_START.match(rest) is not None
    )


def english_name(
    name: str, category: str | None, d: Dictionaries
) -> tuple[str | None, str | None]:
    """(영어 이름, 출처 brand·generic) — 확실하지 않으면 (None, None).

    순서: 브랜드 사전(경계가 맞을 때) → 흔한 낱말(낱말마다 허용 분류일 때). 지점명: 첫 낱말이 아닌 마지막 `…점`
    낱말이 지점이고(`본점` 은 Main Branch) 그 뒤의 낱말(구 이름·입점한 곳)과 `코리아` 는 버린다. `전문점` 은 지점이
    아니다. 띄어 쓰지 않은 `…점` 으로 끝나는 이름은 지점인지 낱말인지 알 수 없어 흔한 낱말로 옮기지 않는다 —
    끝이 「반점」 처럼 사전 낱말이면 그 낱말이다.
    """
    name = unicodedata.normalize("NFKC", name or "").strip()  # 전각 숫자·영문(２, Ａ)
    if not name or name == "업소명없음":
        return None, None
    tokens = name.split()
    branch = None
    while len(tokens) > 1 and (
        tokens[-1] == "코리아" or tokens[-1].endswith(_HOST_SUFFIX)
    ):
        tokens = tokens[:-1]
    last_branch = max(
        (
            i
            for i, t in enumerate(tokens)
            if i > 0 and t.endswith("점") and len(t) > 1 and not _not_branch(t)
        ),
        default=None,
    )
    if last_branch is not None:
        branch = _branch(tokens[last_branch])
        tokens = tokens[:last_branch]
    elif len(tokens) > 1 and tokens[-1] == "전문점":
        tokens = tokens[:-1]
    base = _key("".join(tokens))
    base = base.removesuffix("전문") or base
    first = _key(tokens[0])

    candidates = (b for b in d.brands if _brand_boundary(first, b, branch is not None))
    for ko in sorted(candidates, key=len, reverse=True)[:1]:
        en = d.brands[ko]
        if not en:  # 사전의 「브랜드 아님」 — 브랜드와 머리가 같은 다른 상호(샐러디아)
            break
        glued = first[len(ko) :]  # 첫 낱말 안에서 브랜드 뒤
        rest = base[len(first) :]  # 뒤 낱말들(띄어 쓴 지점 이름 등)
        if glued and _SEPARATOR_START.match(glued):  # 두 번째 상호·괄호 — 버린다
            glued, rest = "", ""
        if glued.endswith(
            _HOST_SUFFIX
        ):  # 브랜드에 붙은 입점한 곳(`스타벅스할인점`) — 지점이 아니다
            glued = ""
        if glued and glued != "점":
            if (
                branch is None
            ):  # 붙여 쓴 지점 — 뒤 낱말은 입점한 곳이다(`투썸플레이스영통역점 롯데마트`)
                branch, rest = _branch(glued), ""
            else:  # 지점 이름의 앞부분 — `메가엠지씨커피야당 중앙점` → Yadang Jungang Branch
                branch = f"{romanize(glued)} {branch}"
        if _MESSY_REST.search(rest):
            rest = ""
        if rest:
            en += " " + (romanize(rest) or rest)
        return (f"{en} {branch}" if branch else en), "brand"

    word = max((k for k in d.generic if base.endswith(k)), key=len, default=None)
    if not word or category not in d.generic[word][1]:
        return None, None
    if branch is None and base.endswith("점") and not word.endswith("점"):
        return None, None  # 띄어 쓰지 않은 지점명일 수 있다
    head_r = romanize(base[: -len(word)]) if base[: -len(word)] else None
    en = d.generic[word][0]
    if not head_r:
        out = en
    elif _SEPARATOR_END.search(
        head_r
    ):  # 「여관/모텔」 → Yeogwan/Motel — 구분 기호 뒤를 띄우지 않는다
        out = head_r + en
    else:
        out = f"{head_r} {en}"
    return (f"{out} {branch}" if branch else out), "generic"


# ── 브랜드 후보 — 분기 갱신 때 사전에 없는 큰 체인을 알린다 ────────────────────────────────
#
# 띄어 쓴 이름의 첫 낱말(`새로운치킨 강남점` → 새로운치킨)을 센다. 붙여 쓴 이름(`새로운치킨강남점`)은 어디까지가
# 상호인지 알 수 없어 세지 않는다. 힌트일 뿐이다 — 흔한 낱말(`행복`)도 섞여 나오고, 사전에 자동으로 넣지 않는다.
BRAND_CANDIDATE_MIN = 100


def brand_candidates(
    rows: Iterable[tuple[str, str | None]],
    d: Dictionaries,
    min_count: int = BRAND_CANDIDATE_MIN,
) -> list[tuple[str, int]]:
    """(이름, name_en_source) 들에서 사전에 없는 첫 낱말 중 min_count 곳 이상인 것 — 많은 순."""
    counts: Counter[str] = Counter()
    for name, source in rows:
        if source == "brand":
            continue
        tokens = unicodedata.normalize("NFKC", name or "").split()
        if len(tokens) < 2:
            continue
        first = tokens[0]
        if len(first) < 2 or first.endswith("점") or _key(first) in d.brands:
            continue
        counts[first] += 1
    return [(k, n) for k, n in counts.most_common() if n >= min_count]
