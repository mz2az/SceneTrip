"""한국어 주소 문장을 쪼개고, 행정안전부 영문도로명주소DB 한 줄로 공식 영문 주소를 조립한다.

입출력이 없는 순수 함수만 둔다 — 단위 시험이 가짜 입력으로 바로 확인한다.
근거와 실측은 docs/project/plans/poi-i18n-image.md §6-3·§11.
"""

import re
from typing import NamedTuple

# 영문도로명주소DB 한 줄의 칸 순서(공개 「영문도로명주소DB 활용가이드」, `|` 구분 18 칸).
ENG_LEGAL_DONG_CODE = 0
ENG_SIDO = 1
ENG_SIGUNGU = 2
ENG_EUPMYEONDONG = 3
ENG_SAN = 5
ENG_JIBUN_MAIN = 6
ENG_JIBUN_SUB = 7
ENG_ROAD_CODE = 8
ENG_ROAD = 9
ENG_UNDERGROUND = 10
ENG_BUILDING_MAIN = 11
ENG_BUILDING_SUB = 12
ENG_BUILDING_ID = 13
ENG_REPRESENTATIVE_JIBUN = 17
ENG_COLUMNS = 18


class RoadKey(NamedTuple):
    """도로명코드(12) + 건물본번 + 건물부번. 부번이 없으면 "0"."""

    road_code: str
    main: str
    sub: str


class JibunKey(NamedTuple):
    """법정동코드(10) + 산 여부("1" 이면 산) + 지번 본번 + 부번."""

    legal_dong_code: str
    san: str
    main: str
    sub: str


class RoadRef(NamedTuple):
    """주소 문장에서 뽑은 「도로명 + 건물번호」 — 도로명은 아직 한국어다."""

    road: str
    main: str
    sub: str


class JibunRef(NamedTuple):
    """주소 문장에서 뽑은 「법정동 + 지번」 — 법정동은 아직 한국어다."""

    dong: str
    san: str
    main: str
    sub: str


def format_english(cols: list[str]) -> str:
    """영문 DB 한 줄 → 공식 영문 주소.

    API(addrEngApi)가 돌려주는 꼴과 같게 만든다 — `55 Hangang-daero 23-gil, Yongsan-gu, Seoul`. 번지 뒤에 쉼표가
    없고, 지하는 번지 앞에 B(`B396 Gangnam-daero`), 읍·면은 도로명 뒤에 붙고 동은 붙지 않는다. 시군구가 없는
    곳(세종)은 그 칸이 빠진다.
    """
    number = cols[ENG_BUILDING_MAIN]
    if cols[ENG_BUILDING_SUB] not in ("", "0"):
        number += "-" + cols[ENG_BUILDING_SUB]
    if cols[ENG_UNDERGROUND] == "1":
        number = "B" + number
    parts = [f"{number} {cols[ENG_ROAD]}"]
    if cols[ENG_EUPMYEONDONG].endswith(("-eup", "-myeon")):
        parts.append(cols[ENG_EUPMYEONDONG])
    parts += [p for p in (cols[ENG_SIGUNGU], cols[ENG_SIDO]) if p]
    return ", ".join(parts)


def road_key(cols: list[str]) -> RoadKey:
    return RoadKey(
        cols[ENG_ROAD_CODE], cols[ENG_BUILDING_MAIN], cols[ENG_BUILDING_SUB] or "0"
    )


def jibun_key(cols: list[str]) -> JibunKey:
    return JibunKey(
        cols[ENG_LEGAL_DONG_CODE],
        cols[ENG_SAN],
        cols[ENG_JIBUN_MAIN],
        cols[ENG_JIBUN_SUB] or "0",
    )


def store_keys(src: dict) -> tuple[RoadKey | None, str | None]:
    """상가정보 원본 한 줄(`src`)의 두 열쇠 — 도로명 열쇠와 건물관리번호.

    도로명 열쇠를 먼저 쓴다. 원본 한 줄 안에서 건물관리번호가 이웃 건물을 가리키는 행이 있고(둔내로 66 의
    건물관리번호가 둔내로 64-1 의 것), 화면의 한국어 주소는 도로명주소라 같은 열쇠로 찾아야 한국어·영어가 같은
    번지를 말한다(계획 §6-3).
    """
    code = (src.get("도로명코드") or "").strip()
    main = (src.get("건물본번지") or "").strip()
    sub = (src.get("건물부번지") or "").strip() or "0"
    road = RoadKey(code, main, sub) if code and main else None
    building = (src.get("건물관리번호") or "").strip() or None
    return road, building


_PAREN = re.compile(r"\(.*?\)")
_SPACED_GIL = re.compile(
    r"(\S+(?:로|길))\s+(\d+(?:번)?길)\b"
)  # 「한강대로 23길」 → 「한강대로23길」
_UNDERGROUND = re.compile(r"지하\s*")  # 「지하 396」·「지하396」 → 「396」
# 「청운동 산 12-3」 → 「산12-3」. 낱말 머리의 「산」 만 — 「남산 12」 의 「남산」 은 건드리지 않는다.
_SPACED_SAN = re.compile(r"(?:(?<=\s)|^)산\s+(?=\d)")
_GLUED_NUMBER = re.compile(
    r"(\S+(?:로|길))(\d+(?:-\d+)?)(?=[\s,]|$)"
)  # 「광장로18」 → 「광장로 18」
_ROAD = re.compile(r"^\S+(?:로|길)$")
_BUILDING_NUMBER = re.compile(r"^(\d+)(?:-(\d+))?(?:번지)?,?$")
_JIBUN = re.compile(r"^(산)?(\d+)(?:-(\d+))?(?:번지)?,?$")


def tokens(address: str) -> list[str]:
    """주소 문장을 낱말로. 괄호 안(동 이름 덧붙임)을 지우고 띄어쓰기 변형 넷을 먼저 맞춘다.

    `「광장로18」 → 「광장로 18」` 은 숫자 뒤가 공백·쉼표·끝일 때만 — 그러지 않으면 `지리산대로1478번길` 같은
    도로명 자체를 쪼갠다(실측에서 한 번 그렇게 깨졌다).
    """
    text = _PAREN.sub(" ", address.replace("\xa0", " "))
    text = _SPACED_GIL.sub(r"\1\2", text)
    text = _UNDERGROUND.sub("", text)
    text = _SPACED_SAN.sub("산", text)
    text = _GLUED_NUMBER.sub(r"\1 \2", text)
    return text.split()


def road_refs(address: str) -> list[RoadRef]:
    """주소 문장의 「도로명 + 건물번호」 후보. 대개 하나이고, 없으면 빈 목록."""
    t = tokens(address)
    out = []
    for i in range(len(t) - 1):
        m = _BUILDING_NUMBER.match(t[i + 1])
        if _ROAD.match(t[i]) and m:
            out.append(RoadRef(t[i], m.group(1), m.group(2) or "0"))
    return out


def jibun_refs(address: str, known_dongs) -> list[JibunRef]:
    """주소 문장의 「법정동 + 지번」 후보. 법정동은 아는 이름(`known_dongs`)만 — `구례읍 원방리 1` 의 「리」 도 법정동이다."""
    t = tokens(address)
    out = []
    for i in range(len(t) - 1):
        m = _JIBUN.match(t[i + 1])
        if t[i] in known_dongs and m:
            out.append(
                JibunRef(
                    t[i], "1" if m.group(1) else "0", m.group(2), m.group(3) or "0"
                )
            )
    return out


def cut_to_building_number(address: str) -> str:
    """API 재시도용 — 건물번호 뒤의 덧붙임(`… 623 (잠원동) 우일빌딩 4층`)을 잘라 낸다. 도로명이 없으면 그대로."""
    t = tokens(address)
    for i in range(len(t) - 1):
        if _ROAD.match(t[i]) and _BUILDING_NUMBER.match(t[i + 1]):
            return " ".join(t[: i + 2]).rstrip(",")
    return " ".join(t)
