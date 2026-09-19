"""AWS 배포 경계의 격리된 회귀 검사."""

import json
import unittest
from unittest.mock import Mock

from tools.aws.tests.fixtures import valid_settings


class CliTest(unittest.TestCase):
    def test_terraform_applies_same_saved_plan_and_never_auto_approve(self):
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import terraform

        config = {
            "environment": "dev",
            "aws_account_id": "123456789012",
            "aws_region": "ap-northeast-2",
            "ingress_allowed_cidrs": ["192.0.2.1/32"],
            "ingress_certificate_arn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
            + "a" * 36,
        }
        run = Mock(
            side_effect=["", "", "", "", json.dumps({"environment": {"value": "dev"}})]
        )
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.dict("os.environ", {"TF_VAR_FILE_JSON": json.dumps(config)}),
            patch("tools.aws.aws.isolated_terraform", return_value=Path(".")),
        ):
            self.assertEqual(
                terraform(run, Path("."), valid_settings(), Path(temp), "apply"),
                {"environment": "dev"},
            )
        plan = run.call_args_list[2].args[0]
        apply = run.call_args_list[3].args[0]
        self.assertEqual(
            next(value[5:] for value in plan if value.startswith("-out=")), apply[-1]
        )
        self.assertNotIn("-auto-approve", apply)

    def test_plan_never_calls_apply(self):
        import tempfile
        from pathlib import Path
        from unittest.mock import patch

        from tools.aws.aws import terraform

        with (
            tempfile.TemporaryDirectory() as temp,
            patch("tools.aws.aws.variables", return_value={}),
            patch("tools.aws.aws.isolated_terraform", return_value=Path(".")),
        ):
            run = Mock(return_value="")
            self.assertIsNone(
                terraform(run, Path("."), valid_settings(), Path(temp), "plan")
            )
        self.assertFalse(any("apply" in call.args[0] for call in run.call_args_list))

    def test_acm_hostname_and_status(self):
        from tools.aws.aws import verify_certificate

        run = Mock(
            return_value=json.dumps(
                {
                    "Certificate": {
                        "Status": "ISSUED",
                        "SubjectAlternativeNames": ["*.dev.example.com"],
                    }
                }
            )
        )
        verify_certificate(
            run, valid_settings(), {"ingress_certificate_arn": "fixture"}
        )
        run.return_value = json.dumps(
            {
                "Certificate": {
                    "Status": "ISSUED",
                    "SubjectAlternativeNames": ["*.example.com"],
                }
            }
        )
        with self.assertRaises(ValueError):
            verify_certificate(
                run, valid_settings(), {"ingress_certificate_arn": "fixture"}
            )

    def test_image_credentials_are_private_and_temporary(self):
        import tempfile
        from pathlib import Path

        from tools.aws.aws import publish

        settings = valid_settings()
        registry = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
        outputs = {
            "ecr_repository_urls": {
                "scene_api": registry + "/scenetrip-dev/scene-api",
                "trip_guide": registry + "/scenetrip-dev/trip-guide",
                "migration": registry + "/scenetrip-dev/migration",
            }
        }
        run = Mock(
            side_effect=[
                json.dumps(
                    {
                        "authorizationData": [
                            {
                                "proxyEndpoint": "https://" + registry,
                                "authorizationToken": "fixture-token",
                            }
                        ]
                    }
                ),
                "",
                "",
                "",
            ]
        )
        with tempfile.TemporaryDirectory() as temp:
            publish(run, outputs, settings, Path(temp))
            config = Path(temp) / "docker/config.json"
            self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        self.assertFalse(config.exists())
        for call in run.call_args_list[1:]:
            self.assertNotIn("fixture-token", " ".join(call.args[0]))
            self.assertIn(settings.tag, call.args[0])
