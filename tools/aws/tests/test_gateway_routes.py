"""게이트웨이 전달 경로 ↔ 공개 API 계약 명세 검사 (MZ2AZ-365).

DEV/PRD 게이트웨이(nginx, Helm templates/config.yaml)는 `location ~ ^/v1/(...)(/|$)` 에 적힌 첫 경로
조각만 scene-api 로 넘기고, 나머지 /v1 은 404 다. 그래서 계약(contracts/openapi/scene-api-v1.yaml)의
`paths` 첫 조각이 그 목록에 하나라도 빠지면, 서버·앱은 맞는데 운영에서만 그 엔드포인트가 막힌다
(실제로 /uploads 가 빠져 있었다). 반대로 actuator·internal 은 계속 막혀 있어야 한다.

구현이 아니라 계약을 기준으로 쓴다 — 계약을 읽어 기대 목록을 만들고, 실제 렌더된 nginx.conf 와 맞춘다.
"""

import os
import re
import unittest
from pathlib import Path

from tools.aws.tests.test_media import BUCKET, MediaChartTest, chart_values

CONTRACT = "contracts/openapi/scene-api-v1.yaml"
INTERNAL = {"actuator", "internal"}

# `location ~ ^/v1/(a|b|c)(/|$) {` 와 `location ~* ^/v1/(a|b)(/|$) {` — 수식어(~ / ~*)와 목록을 꺼낸다.
LOCATION = re.compile(r"location\s+(~\*?)\s+\^/v1/\(([^)]*)\)\(/\|\$\)\s*\{")


def contract_prefixes(text):
    """계약 `paths` 아래 키의 첫 경로 조각 — `/places/{placeId}/reviews` → `places`."""
    prefixes = set()
    inside = False
    for line in text.splitlines():
        if re.match(r"^\S", line):
            inside = line.rstrip() == "paths:"
            continue
        match = re.match(r"^  (/[^:\s]*):\s*$", line) if inside else None
        if match:
            prefixes.add(match.group(1).strip("/").split("/", 1)[0])
    return prefixes


def v1_locations(nginx_conf):
    """API 서버 블록의 /v1 정규식 location 을 순서대로 — (수식어, 조각 목록, 블록 본문)."""
    out = []
    for match in LOCATION.finditer(nginx_conf):
        # 블록 안에 `if (...) { ... }` 가 있어 첫 `}` 로 자르면 안 된다 — 다음 location 까지를 본문으로 본다
        end = nginx_conf.find("location ", match.end())
        body = nginx_conf[match.end() : end if end != -1 else len(nginx_conf)]
        out.append((match.group(1), match.group(2).split("|"), body))
    return out


def forwarded(nginx_conf):
    """scene-api 로 proxy_pass 하는 /v1 location 의 조각 목록."""
    found = [
        set(parts)
        for _, parts, body in v1_locations(nginx_conf)
        if "proxy_pass http://scene-api:8080;" in body
    ]
    if len(found) != 1:
        raise AssertionError(
            f"scene-api 로 넘기는 /v1 location 이 {len(found)} 개입니다"
        )
    return found[0]


def blocked(nginx_conf):
    """`return 404;` 로 막는 /v1 location 의 조각 목록."""
    return {
        part
        for _, parts, body in v1_locations(nginx_conf)
        if "return 404;" in body
        for part in parts
    }


class GatewayContractRoutesTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        self.chart = MediaChartTest("render")
        self.chart.root = self.root
        self.prefixes = contract_prefixes((self.root / CONTRACT).read_text())

    def nginx_conf(self, environment):
        docs = self.chart.render(environment, chart_values(mediaBucket=BUCKET))
        config = self.chart.one(docs, "ConfigMap", "gateway")
        self.assertIn("nginx.conf: |", config)
        return config.split("nginx.conf: |", 1)[1]

    def test_contract_has_prefixes(self):
        # 계약 파싱이 비면 아래 검사가 공짜로 통과한다
        self.assertIn("uploads", self.prefixes)
        self.assertIn("places", self.prefixes)
        self.assertGreaterEqual(len(self.prefixes), 10, self.prefixes)
        self.assertFalse(self.prefixes & INTERNAL, self.prefixes)

    def test_every_contract_prefix_is_forwarded(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                missing = self.prefixes - forwarded(self.nginx_conf(environment))
                self.assertEqual(
                    missing,
                    set(),
                    f"계약에 있는데 게이트웨이가 막는 경로: {sorted(missing)}",
                )

    def test_internal_paths_stay_blocked_and_not_forwarded(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                conf = self.nginx_conf(environment)
                self.assertFalse(forwarded(conf) & INTERNAL, forwarded(conf))
                self.assertLessEqual(INTERNAL, blocked(conf))
                # nginx 정규식 location 은 위에서부터 먼저 맞는 것이 이긴다 — 차단이 전달보다 앞이어야 한다
                kinds = [
                    "block" if "return 404;" in body else "forward"
                    for _, _, body in v1_locations(conf)
                ]
                self.assertLess(kinds.index("block"), kinds.index("forward"), kinds)

    def test_check_catches_a_missing_prefix(self):
        # 검사 자체의 음성 대조 — 렌더 결과에서 uploads 를 지우면 빠진 경로로 잡혀야 한다(/uploads 사고 재현)
        conf = self.nginx_conf("dev")
        self.assertIn("|uploads)", conf)
        broken = conf.replace("|uploads)", ")")
        self.assertEqual(self.prefixes - forwarded(broken), {"uploads"})

    def test_contract_parser_reads_first_segment(self):
        text = (
            "openapi: 3.0.3\npaths:\n  /places/{placeId}/reviews:\n    get: {}\n"
            "  /me:\n    get: {}\ncomponents:\n  schemas:\n    X: {}\n"
        )
        self.assertEqual(contract_prefixes(text), {"places", "me"})


if __name__ == "__main__":
    unittest.main()
