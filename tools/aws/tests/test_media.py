"""사용자 사진 버킷 배선 명세 검사 — docs/project/plans/review.md §13, docs/ops/aws-deployment.md §12.

Terraform 출력 user_media_bucket → 배포기의 Helm 값 sceneApi.mediaBucket → ConfigMap SCENETRIP_MEDIA_BUCKET.
scene-api 파드는 서비스 계정 scene-api 로 뜨고(Pod Identity 가 그 이름으로 -media 역할을 잇는다), 쿠버네티스 API
토큰은 여전히 마운트하지 않는다.
"""

import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings
from tools.aws.tests.test_alb import alb_outputs
from tools.aws.tests.test_auth_secret import cluster_commands, deploy_outputs

BUCKET = "scenetrip-user-media-123456789012-ap-northeast-2-dev"


def chart_values(**scene_api):
    return {
        "database": {"host": "db.example.internal"},
        "sceneApi": {"image": "fixture:api", **scene_api},
        "tripGuide": {"image": "fixture:guide"},
        "network": {"dnsCidr": "172.20.0.10/32"},
        "gateway": {
            "host": "api.example.com",
            "certificateArn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
            + "a" * 36,
            "allowedCidrs": ["192.0.2.1/32"],
            "albSubnetIds": alb_outputs()["public_subnet_ids"],
            "trustedProxyCidrs": alb_outputs()["public_subnet_cidrs"],
            "securityGroupId": alb_outputs()["alb_security_group_id"],
        },
    }


def documents(rendered):
    """렌더 결과를 문서별로 — (kind, metadata.name, 본문)."""
    out = []
    for doc in rendered.split("\n---"):
        kind = name = None
        for line in doc.splitlines():
            if line.startswith("kind: "):
                kind = line.split(":", 1)[1].strip()
            if line.startswith("  name: ") and name is None:
                name = line.split(":", 1)[1].strip().strip("'\"")
        if kind:
            out.append((kind, name, doc))
    return out


class MediaChartTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]

    def render(self, environment, values):
        process = subprocess.run(
            [
                str(self.root / "tools/bazel/cloud/helm"),
                "template",
                "scenetrip",
                str(self.root / "platform/helm/scenetrip"),
                "--namespace",
                "scenetrip",
                "--values",
                str(self.root / f"platform/helm/scenetrip/values-{environment}.yaml"),
                "--values",
                "-",
            ],
            input=json.dumps(values),
            text=True,
            capture_output=True,
            check=False,
            timeout=60,
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        return documents(process.stdout)

    def one(self, docs, kind, name):
        found = [doc for k, n, doc in docs if k == kind and n == name]
        self.assertEqual(len(found), 1, f"{kind}/{name}: {len(found)}")
        return found[0]

    def test_scene_api_runs_as_its_service_account_without_api_token(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                docs = self.render(environment, chart_values(mediaBucket=BUCKET))
                deployment = self.one(docs, "Deployment", "scene-api")
                self.assertIn("      serviceAccountName: scene-api\n", deployment)
                self.assertIn("      automountServiceAccountToken: false\n", deployment)
                account = self.one(docs, "ServiceAccount", "scene-api")
                self.assertIn("  namespace: scenetrip\n", account)
                self.assertIn("automountServiceAccountToken: false", account)

    def test_only_scene_api_gets_the_media_service_account(self):
        docs = self.render("dev", chart_values(mediaBucket=BUCKET))
        for name in ("trip-guide", "gateway"):
            with self.subTest(deployment=name):
                deployment = self.one(docs, "Deployment", name)
                self.assertNotIn("serviceAccountName: scene-api", deployment)
                self.assertIn("automountServiceAccountToken: false", deployment)
        accounts = [n for k, n, _ in docs if k == "ServiceAccount"]
        self.assertEqual(accounts, ["scene-api"])

    def test_config_map_carries_media_bucket(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                docs = self.render(environment, chart_values(mediaBucket=BUCKET))
                config = self.one(docs, "ConfigMap", "scene-api")
                self.assertIn(f'  SCENETRIP_MEDIA_BUCKET: "{BUCKET}"\n', config)

    def test_missing_media_bucket_renders_empty_value(self):
        # 값이 없으면 빈 문자열 — 서버는 뜨고 사진 올리기만 503 이다
        docs = self.render("dev", chart_values())
        config = self.one(docs, "ConfigMap", "scene-api")
        self.assertIn('  SCENETRIP_MEDIA_BUCKET: ""\n', config)


class MediaDeployValuesTest(unittest.TestCase):
    def test_terraform_bucket_output_reaches_helm_values(self):
        from tools.aws.deploy import deploy

        settings = valid_settings()
        outputs = {**deploy_outputs(settings), "user_media_bucket": BUCKET}
        run = Mock(side_effect=cluster_commands)
        with (
            patch("tools.aws.deploy.verify_nodeclass"),
            patch(
                "tools.aws.deploy.wait_for_alb",
                return_value="fixture.ap-northeast-2.elb.amazonaws.com",
            ),
            patch(
                "tools.aws.deploy.database_credentials",
                return_value={
                    "username": "app_runtime",
                    "password": "fixture",
                    "migration_username": "app_migrate",
                    "migration_password": "fixture",
                },
            ),
            patch(
                "tools.aws.deploy.auth_secret",
                return_value={"SCENETRIP_AUTH_JWT_SECRET": "fixture-signing-key"},
            ),
            patch(
                "tools.aws.deploy.secret_value",
                side_effect=[
                    {"KAKAO_REST_KEY": "fixture"},
                    {"DEEPSEEK_API_KEY": "fixture"},
                ],
            ),
            patch("tools.aws.deploy.migrate_database"),
            patch("tools.aws.deploy.verify_network_deny"),
        ):
            deploy(run, Path("."), settings, outputs)
        helm = next(call for call in run.call_args_list if call.args[0][0] == "helm")
        values = json.loads(helm.kwargs["stdin"])
        self.assertEqual(values["sceneApi"]["mediaBucket"], BUCKET)
        self.assertEqual(values["sceneApi"]["image"], "scene-image:" + settings.tag)
        # 버킷 이름만 넘긴다 — 자격 증명은 Pod Identity 가 준다
        self.assertNotIn("AWS_ACCESS_KEY_ID", json.dumps(values))
        self.assertNotIn("AWS_SECRET_ACCESS_KEY", json.dumps(values))


if __name__ == "__main__":
    unittest.main()
