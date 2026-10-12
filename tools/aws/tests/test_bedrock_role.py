"""trip-guide 의 Bedrock 을 Pod Identity 역할로 — docs/project/plans/bedrock-iam-role.md (MZ2AZ-317).

- Helm: trip-guide 는 자기 서비스 계정(trip-guide, API 토큰 마운트 없음)으로 뜬다.
- 네트워크 정책: scene-api·trip-guide 에 Pod Identity 에이전트(169.254.170.23/32 TCP 80)만 더한다.
- 배포기: trip_guide 비밀값에 필수 키가 없다. 예전 고정 키는 받아 들이되(파드에는 넣지 않는다) 모르는 키는 거절한다.
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
    def validate(self, value, environment=None):
        from tools.aws.config import validate_secret

        return validate_secret("trip_guide", value, environment)

    def test_no_required_keys(self):
        for environment in (None, "dev", "prd"):
            with self.subTest(environment=environment):
                self.validate({}, environment)

    def test_legacy_bedrock_keys_are_optional(self):
        for value in (
            {"AWS_ACCESS_KEY_ID": "fixture-id", "AWS_SECRET_ACCESS_KEY": "fixture"},
            {"AWS_ACCESS_KEY_ID": "fixture-id"},
            {"AWS_SECRET_ACCESS_KEY": "fixture"},
        ):
            for environment in ("dev", "prd"):
                with self.subTest(keys=sorted(value), environment=environment):
                    self.validate(value, environment)

    def test_unknown_keys_are_rejected(self):
        for value in (
            {"AWS_SESSION_TOKEN": "fixture"},
            {"OPENAI_API_KEY": "fixture"},
            {"AWS_ACCESS_KEY_ID": "fixture-id", "SCENETRIP_SERVICE_TOKEN": "x"},
        ):
            with self.subTest(keys=sorted(value)), self.assertRaises(ValueError):
                self.validate(value, "dev")

    def test_values_must_be_non_empty_strings(self):
        for bad in ("", "   ", 123, None):
            with self.subTest(value=bad), self.assertRaises(ValueError):
                self.validate({"AWS_ACCESS_KEY_ID": bad}, "dev")

    def test_non_object_is_rejected(self):
        for bad in ([], "AWS_ACCESS_KEY_ID", None):
            with self.subTest(value=bad), self.assertRaises(ValueError):
                self.validate(bad, "dev")


if __name__ == "__main__":
    unittest.main()
