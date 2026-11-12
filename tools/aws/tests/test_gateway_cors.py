"""gateway 웹 CORS 허용 Origin 명세 검사 — docs/project/plans/web-release.md §5.3–5.4.

DEV 는 배포 주소·로컬 개발 서버·Pages 미리보기 패턴(~정규식)을, PRD 는 배포 주소 하나만 허용한다.
와일드카드·localhost 는 PRD 에 들어가면 안 된다. 실제 nginx 동작은 통합 시험(//tools/aws/integration:gateway_test)이 본다.
"""

import os
import unittest
from pathlib import Path

from tools.aws.tests.test_gateway_limits import nginx_conf
from tools.aws.tests.test_public_ingress import PublicChartTest, chart_values

PRD_ORIGIN = "https://app.scenetrip.io"
DEV_ORIGINS = ("https://dev.scenetrip.io", "http://localhost:5173")
# values-dev.yaml 의 「~」 뒤 정규식 본문 — nginx 설정에 그대로 실려야 한다.
DEV_PAGES_REGEX = r"^https://[a-z0-9-]+\.scenetrip-app\.pages\.dev$"


class GatewayCorsChartTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        # 렌더러는 PublicChartTest 의 것을 그대로 쓴다 — 같은 고정 helm, 같은 값 모양.
        self.chart = PublicChartTest("helm_template")
        self.chart.setUp()

    def render(self, environment, **gateway):
        # chart_values 는 corsOrigins 를 넣지 않는다 — values-<환경>.yaml 의 값이 그대로 남는다.
        process = self.chart.helm_template(environment, chart_values(**gateway))
        self.assertEqual(process.returncode, 0, process.stderr)
        return nginx_conf(process.stdout)

    def assert_contains(self, conf, text):
        # nginx.conf 전체를 실패 메시지에 싣지 않는다 — 무엇이 없는지만 보인다.
        self.assertTrue(text in conf, f"nginx.conf 에 {text!r} 가 없습니다")

    def test_prd_allows_only_production_origin(self):
        conf = self.render("prd")
        self.assert_contains(conf, PRD_ORIGIN)
        self.assert_contains(conf, "Access-Control-Allow-Origin")
        # 개발 주소·로컬·미리보기 패턴은 PRD 에 없다.
        for forbidden in DEV_ORIGINS + ("localhost", "pages.dev", "scenetrip-app"):
            with self.subTest(forbidden=forbidden):
                self.assertNotIn(forbidden, conf)

    def test_dev_allows_dev_origins_and_preview_pattern(self):
        conf = self.render("dev")
        for origin in DEV_ORIGINS + (DEV_PAGES_REGEX,):
            with self.subTest(origin=origin):
                self.assert_contains(conf, origin)
        self.assert_contains(conf, "Access-Control-Allow-Origin")

    def test_allow_origin_is_never_wildcard(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                conf = self.render(environment)
                self.assertNotRegex(conf, r"Access-Control-Allow-Origin\s+['\"]?\*")
                self.assertNotIn("Access-Control-Allow-Credentials", conf)

    def test_empty_origins_emit_no_cors(self):
        # 차트 기본값과 같은 빈 목록이면 CORS 헤더 줄이 하나도 없다.
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                conf = self.render(environment, corsOrigins=[])
                self.assertNotIn("Access-Control-", conf)


if __name__ == "__main__":
    unittest.main()
