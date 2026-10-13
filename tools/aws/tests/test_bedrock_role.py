"""trip-guide 의 Bedrock 을 Pod Identity 역할로 — docs/project/plans/bedrock-iam-role.md (MZ2AZ-317).

- Helm: trip-guide 는 자기 서비스 계정(trip-guide, API 토큰 마운트 없음)으로 뜬다.
- 네트워크 정책: scene-api·trip-guide 에 Pod Identity 에이전트(169.254.170.23/32 TCP 80)만 더한다.
- 배포기: trip-guide 비밀값은 아예 없다 — 검사 종류에도 trip_guide 가 없고, 앱 비밀값은 scene_api·database·auth 셋이다.
"""

import unittest

from tools.aws.tests.test_media import BUCKET, MediaChartTest, chart_values

AGENT_CIDR = "169.254.170.23/32"


def egress_rules(policy):
    """NetworkPolicy 본문의 egress 규칙 줄 — 주석·빈 줄을 빼고 공백을 정규화한다."""
    lines = policy.split("\n")
    start = next(i for i, line in enumerate(lines) if line.strip() == "egress:")
    indent = len(lines[start]) - len(lines[start].lstrip())
    rules = []
    for line in lines[start + 1 :]:
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if len(line) - len(line.lstrip()) <= indent and not stripped.startswith("-"):
            break
        rules.append(" ".join(stripped.split()))
    return rules


class TripGuideChartTest(unittest.TestCase):
    setUp = MediaChartTest.setUp
    render = MediaChartTest.render
    one = MediaChartTest.one

    def test_trip_guide_runs_as_its_service_account_without_api_token(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                docs = self.render(environment, chart_values(mediaBucket=BUCKET))
                deployment = self.one(docs, "Deployment", "trip-guide")
                self.assertIn("      serviceAccountName: trip-guide\n", deployment)
                self.assertIn("      automountServiceAccountToken: false\n", deployment)
                account = self.one(docs, "ServiceAccount", "trip-guide")
                self.assertIn("  namespace: scenetrip\n", account)
                self.assertIn("automountServiceAccountToken: false", account)
                # scene-api 는 그대로 자기 서비스 계정
                self.assertIn(
                    "      serviceAccountName: scene-api\n",
                    self.one(docs, "Deployment", "scene-api"),
                )

    def test_pod_identity_agent_egress_only_for_scene_api_and_trip_guide(self):
        expected = {
            "scene-api": [
                "- to: [{ podSelector: { matchLabels: { app: trip-guide } } }]",
                "ports: [{ protocol: TCP, port: 8899 }]",
                "- ports: [{ protocol: TCP, port: 5432 }, { protocol: TCP, port: 443 }]",
                f"- to: [{{ ipBlock: {{ cidr: {AGENT_CIDR} }} }}]",
                "ports: [{ protocol: TCP, port: 80 }]",
            ],
            "trip-guide": [
                "- to: [{ podSelector: { matchLabels: { app: scene-api } } }]",
                "ports: [{ protocol: TCP, port: 8080 }]",
                "- ports: [{ protocol: TCP, port: 443 }]",
                f"- to: [{{ ipBlock: {{ cidr: {AGENT_CIDR} }} }}]",
                "ports: [{ protocol: TCP, port: 80 }]",
            ],
        }
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                docs = self.render(environment, chart_values(mediaBucket=BUCKET))
                policies = {n: doc for k, n, doc in docs if k == "NetworkPolicy"}
                for name, rules in expected.items():
                    with self.subTest(policy=name):
                        self.assertEqual(egress_rules(policies[name]), rules)
                # 다른 정책(기본 차단·DNS·gateway·DB Job)은 링크 로컬 주소로 넓어지지 않는다
                for name, doc in policies.items():
                    if name in expected:
                        continue
                    with self.subTest(policy=name):
                        self.assertNotIn("169.254.", doc)
                        self.assertNotIn("port: 80 ", doc)
                        self.assertNotIn("port: 80}", doc)


class TripGuideSecretValidationTest(unittest.TestCase):
    """MZ2AZ-317 후속 — trip-guide 의 Secrets Manager 비밀값을 없앴다. 검사 종류에도 남지 않는다."""

    def test_app_secrets_are_scene_api_database_auth_signoz(self):
        # trip_guide 는 없다. signoz(SigNoz 관리자 비밀번호)는 ADR 0025 에서 더해졌다.
        from tools.aws.config import APP_SECRETS

        self.assertEqual(APP_SECRETS, ("scene_api", "database", "auth", "signoz"))

    def test_trip_guide_is_not_a_secret_kind(self):
        from tools.aws import config

        self.assertNotIn("trip_guide", config.SECRET_KEYS)
        self.assertNotIn("trip_guide", config.OPTIONAL_SECRET_KEYS)
        self.assertNotIn("trip_guide", getattr(config, "PRD_REQUIRED_SECRET_KEYS", {}))

    def test_validating_trip_guide_kind_fails(self):
        from tools.aws.config import validate_secret

        for value in (
            {},
            {"AWS_ACCESS_KEY_ID": "fixture-id", "AWS_SECRET_ACCESS_KEY": "fixture"},
        ):
            for environment in (None, "dev", "prd"):
                with (
                    self.subTest(keys=sorted(value), environment=environment),
                    self.assertRaises((KeyError, ValueError)),
                ):
                    validate_secret("trip_guide", value, environment)

    def test_scene_api_does_not_accept_legacy_bedrock_keys(self):
        # 예전 고정 키가 다른 칸으로 옮겨 가지 않았는지 — scene_api 에 넣으면 거절한다.
        from tools.aws.config import validate_secret

        validate_secret("scene_api", {"KAKAO_REST_KEY": "fixture"}, "dev")
        for key in ("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_secret(
                    "scene_api", {"KAKAO_REST_KEY": "fixture", key: "fixture"}, "dev"
                )


if __name__ == "__main__":
    unittest.main()
