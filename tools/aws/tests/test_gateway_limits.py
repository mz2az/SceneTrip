"""gateway 의 IP 별 상한 명세 검사 — MZ2AZ-334, docs/project/plans/rate-limit.md, platform/helm/scenetrip/README.md.

IP 는 폭주만 막는 바깥 울타리다: 클라이언트 IP 당 30r/s, burst 60(nodelay), 동시 연결 60. 넘으면 429.
실제 사용자 한도는 계정 기준으로 scene-api 가 센다. 상한은 API 경로(location ^/v1/(…)) 에 걸려야 한다.
"""

import os
import re
import unittest
from pathlib import Path

from tools.aws.tests.test_public_ingress import PublicChartTest, chart_values


def nginx_conf(rendered):
    """렌더 결과에서 gateway ConfigMap 의 nginx.conf 본문만."""
    return rendered.split("nginx.conf: |", 1)[1].split("\n---", 1)[0]


def api_location(conf):
    """/v1 API 허용 목록 location 블록(중첩 없음 — 첫 닫는 괄호까지)."""
    match = re.search(
        r"location ~ \^/v1/\([^)]*\)\(/\|\$\) \{(.*?)\n\s*\}", conf, re.DOTALL
    )
    if match is None:
        raise AssertionError("API location 블록을 찾지 못했습니다")
    return match.group(1)


class GatewayLimitsChartTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        # 렌더러는 PublicChartTest 의 것을 그대로 쓴다 — 같은 고정 helm, 같은 값 모양.
        self.chart = PublicChartTest("helm_template")
        self.chart.setUp()

    def render(self, environment, **gateway):
        process = self.chart.helm_template(environment, chart_values(**gateway))
        self.assertEqual(process.returncode, 0, process.stderr)
        return nginx_conf(process.stdout)

    def test_ip_request_rate_is_30_per_second(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                conf = self.render(environment)
                zones = re.findall(r"limit_req_zone [^;]+;", conf)
                self.assertEqual(
                    zones,
                    ["limit_req_zone $binary_remote_addr zone=api:10m rate=30r/s;"],
                )

    def test_api_location_uses_burst_60_nodelay_and_60_connections(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                conf = self.render(environment)
                self.assertIn(
                    "limit_conn_zone $binary_remote_addr zone=connections:10m;", conf
                )
                block = api_location(conf)
                self.assertIn("limit_req zone=api burst=60 nodelay;", block)
                self.assertIn("limit_req_status 429;", block)
                self.assertIn("limit_conn connections 60;", block)
                # 예전 값(10r/s · burst 20 · 동시 20)이 어디에도 남지 않는다
                self.assertNotIn("burst=20", conf)
                self.assertNotIn("rate=10r/s", conf)
                self.assertNotIn("limit_conn connections 20;", conf)

    def test_limits_do_not_change_with_public_flag(self):
        # 공개 여부는 client 허용 검사만 바꾼다 — IP 상한은 같다.
        public = api_location(self.render("dev", public=True))
        private = api_location(self.render("dev", public=False))
        for line in (
            "limit_req zone=api burst=60 nodelay;",
            "limit_conn connections 60;",
        ):
            with self.subTest(line=line):
                self.assertIn(line, public)
                self.assertIn(line, private)


if __name__ == "__main__":
    unittest.main()
