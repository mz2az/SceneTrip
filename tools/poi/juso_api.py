"""행정안전부 영문주소 검색 API(addrEngApi) — 영문 DB 로 못 찾은 주소를 채운다.

부르는 것은 영문 DB 로 못 맞춘 관광공사·교통 행뿐이다(실측 약 2,700 건). 받는 규칙은 계획 §6-3:
결과가 1 건이거나 여러 건이 모두 같은 주소일 때만 받는다. 0 건이면 건물번호까지 잘라 한 번 더 부른다.
시도·시군구를 떼고 「도로명 + 번호」 만으로 찾지 않는다 — 같은 이름 도로가 전국에 있어 경주가 제주로 나왔다.

결과는 캐시 파일(JSON)에 남긴다. 다시 돌릴 때 이미 물어본 검색어는 부르지 않는다 — 분기마다 같은 주소를
다시 묻지 않게 하고, 과도한 호출로 IP 가 막히지 않게 하려는 것이다. 키는 저장소에 두지 않는다
(환경변수 JUSO_ENG_API_KEY).
"""

import json
import time
import urllib.parse
import urllib.request
from collections.abc import Callable
from pathlib import Path

ENDPOINT = "https://business.juso.go.kr/addrlink/addrEngApi.do"

# 부르는 사이 간격(초). 공식 호출 제한은 없지만 과도한 호출은 IP 를 막는다고 안내한다.
PAUSE_SECONDS = 0.2


def accept(results: list[str]) -> str | None:
    """API 결과(roadAddr 목록) → 받을 영문 주소. 1 건이거나 모두 같은 주소일 때만."""
    if not results:
        return None
    if len(set(results)) == 1:
        return results[0]
    return None


class JusoApi:
    """검색어 → 영문 주소. 캐시를 먼저 보고, 없을 때만 부른다."""

    def __init__(
        self,
        key: str,
        cache_path: Path,
        fetch: Callable[[str], dict] | None = None,
        pause: float = PAUSE_SECONDS,
    ):
        self._key = key
        self._cache_path = cache_path
        self._cache: dict[str, list[str]] = (
            json.loads(cache_path.read_text(encoding="utf-8"))
            if cache_path.exists()
            else {}
        )
        self._fetch = fetch or self._http
        self._pause = pause
        self.calls = 0

    def _http(self, keyword: str) -> dict:
        query = urllib.parse.urlencode(
            {
                "confmKey": self._key,
                "currentPage": 1,
                "countPerPage": 5,
                "keyword": keyword,
                "resultType": "json",
            }
        )
        with urllib.request.urlopen(f"{ENDPOINT}?{query}", timeout=15) as res:
            return json.load(res)

    def search(self, keyword: str) -> list[str]:
        """검색어 하나의 roadAddr 목록. 오류(검색어 형식 등)는 빈 목록으로 — 그 행은 영문 주소 없이 간다."""
        if keyword in self._cache:
            return self._cache[keyword]
        self.calls += 1
        try:
            body = self._fetch(keyword)["results"]
            ok = body["common"]["errorCode"] == "0"
            found = [j["roadAddr"] for j in (body.get("juso") or [])] if ok else []
        except (OSError, ValueError, KeyError):
            # 연결 실패는 캐시에 남기지 않는다 — 다음 실행에서 다시 묻는다.
            time.sleep(self._pause)
            return []
        self._cache[keyword] = found
        time.sleep(self._pause)
        return found

    def save(self) -> None:
        self._cache_path.write_text(
            json.dumps(self._cache, ensure_ascii=False, indent=0), encoding="utf-8"
        )
