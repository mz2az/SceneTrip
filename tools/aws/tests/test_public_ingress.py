"""ADR 0019(MZ2AZ-333) 명세 검사 — DEV 만 공개, PRD 는 허용 CIDR.

구현이 아니라 명세(ADR 0019·Helm README)를 기준으로 쓴다.
"""

import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock

from tools.aws.aws import render
from tools.aws.deploy import gateway_values
from tools.aws.tests.fixtures import valid_settings

CERTIFICATE = "arn:aws:acm:ap-northeast-2:123456789012:certificate/" + "a" * 36
INBOUND = "alb.ingress.kubernetes.io/inbound-cidrs:"


def settings_for(environment):
    return valid_settings(
        environment=environment,
        role=f"arn:aws:iam::123456789012:role/scenetrip-{environment}-deploy",
    )


def outputs(**changes):
    return {
        "ingress_allowed_cidrs": ["203.0.113.1/32"],
        "ingress_certificate_arn": CERTIFICATE,
        **changes,
    }


class PublicGatewayValuesTest(unittest.TestCase):
    def test_dev_public_output_reaches_helm(self):
        values = gateway_values(settings_for("dev"), outputs(ingress_public=True))
        self.assertIs(values["gateway"]["public"], True)

    def test_dev_not_public_when_output_false(self):
        values = gateway_values(settings_for("dev"), outputs(ingress_public=False))
        self.assertIs(values["gateway"]["public"], False)

    def test_missing_output_is_not_public(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                values = gateway_values(settings_for(environment), outputs())
                self.assertIs(values["gateway"]["public"], False)

    def test_prd_public_is_rejected(self):
        with self.assertRaises(ValueError):
            gateway_values(settings_for("prd"), outputs(ingress_public=True))

    def test_prd_not_public_is_accepted(self):
        values = gateway_values(settings_for("prd"), outputs(ingress_public=False))
        self.assertIs(values["gateway"]["public"], False)
        self.assertEqual(values["gateway"]["allowedCidrs"], ["203.0.113.1/32"])

    def test_non_bool_is_rejected(self):
        # 1/0 은 파이썬에서 True/False 와 같다고 비교되지만 bool 이 아니다.
        for environment in ("dev", "prd"):
            for value in ("true", "false", 1, 0, [True]):
                with (
                    self.subTest(environment=environment, value=value),
                    self.assertRaises(TypeError),
                ):
                    gateway_values(
                        settings_for(environment), outputs(ingress_public=value)
                    )

    def test_public_does_not_relax_world_cidr_check(self):
        with self.assertRaises(ValueError):
            gateway_values(
                settings_for("dev"),
                outputs(ingress_public=True, ingress_allowed_cidrs=["0.0.0.0/0"]),
            )


def chart_values(**gateway):
    return {
        "database": {"host": "db.example.internal"},
        "sceneApi": {"image": "fixture:api"},
        "tripGuide": {"image": "fixture:guide"},
        "network": {"dnsCidr": "172.20.0.10/32"},
        "gateway": {
            "host": "api.example.com",
            "certificateArn": CERTIFICATE,
            "allowedCidrs": ["192.0.2.1/32", "198.51.100.0/24"],
            "albSubnetIds": ["subnet-aaaaaaaaaaaaaaaaa", "subnet-bbbbbbbbbbbbbbbbb"],
            "trustedProxyCidrs": ["10.40.0.0/24", "10.40.1.0/24"],
            "securityGroupId": "sg-aaaaaaaaaaaaaaaaa",
            **gateway,
        },
    }


class PublicChartTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        self.helm = str(self.root / "tools/bazel/cloud/helm")

    def helm_template(self, environment, values):
        return subprocess.run(
            [
                self.helm,
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

    def inbound_cidrs(self, rendered):
        lines = [line for line in rendered.splitlines() if INBOUND in line]
        self.assertEqual(len(lines), 1, lines)
        value = lines[0].split(INBOUND, 1)[1].strip().strip("'\"")
        return [part.strip() for part in value.split(",")]

    def test_dev_public_renders_world_annotation_and_keeps_alb_peer_check(self):
        process = self.helm_template("dev", chart_values(public=True))
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(self.inbound_cidrs(process.stdout), ["0.0.0.0/0"])
        # 공개여도 ALB peer 신뢰 경계는 남는다(README·ADR 0019).
        self.assertIn("real_ip_recursive off;", process.stdout)
        self.assertIn("set_real_ip_from 10.40.0.0/24;", process.stdout)
        self.assertIn("geo $realip_remote_addr $trusted_alb_peer", process.stdout)

    def test_not_public_renders_allowed_cidrs_annotation(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                process = self.helm_template(environment, chart_values(public=False))
                self.assertEqual(process.returncode, 0, process.stderr)
                self.assertEqual(
                    self.inbound_cidrs(process.stdout),
                    ["192.0.2.1/32", "198.51.100.0/24"],
                )

    def test_default_is_not_public(self):
        process = self.helm_template("dev", chart_values())
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertNotIn("0.0.0.0/0", self.inbound_cidrs(process.stdout))

    def test_public_and_not_public_gateways_differ(self):
        public = self.helm_template("dev", chart_values(public=True))
        private = self.helm_template("dev", chart_values(public=False))
        self.assertEqual(public.returncode, 0, public.stderr)
        self.assertEqual(private.returncode, 0, private.stderr)
        self.assertNotEqual(
            public.stdout.split("nginx.conf: |", 1)[1].split("\n---", 1)[0],
            private.stdout.split("nginx.conf: |", 1)[1].split("\n---", 1)[0],
            "public 이 nginx client 검사를 바꾸지 않았습니다",
        )

    def test_prd_public_fails_to_render(self):
        process = self.helm_template("prd", chart_values(public=True))
        self.assertNotEqual(process.returncode, 0, process.stdout)

    def test_world_allowed_cidr_still_fails_to_render(self):
        for environment, public in (("dev", False), ("dev", True), ("prd", False)):
            with self.subTest(environment=environment, public=public):
                process = self.helm_template(
                    environment,
                    chart_values(public=public, allowedCidrs=["0.0.0.0/0"]),
                )
                self.assertNotEqual(process.returncode, 0, process.stdout)

    def run_render(self, environment):
        run = Mock()
        render(run, self.root, environment)
        command = run.call_args.args[0]
        stdin = run.call_args.kwargs["stdin"]
        self.assertEqual(command[0], "helm")
        process = subprocess.run(
            [self.helm, *command[1:]],
            input=stdin,
            text=True,
            capture_output=True,
            check=False,
            timeout=60,
        )
        return json.loads(stdin), process

    def test_cli_render_dev_is_public(self):
        values, process = self.run_render("dev")
        self.assertIs(values["gateway"]["public"], True)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(self.inbound_cidrs(process.stdout), ["0.0.0.0/0"])

    def test_cli_render_prd_is_not_public(self):
        values, process = self.run_render("prd")
        self.assertIs(values["gateway"]["public"], False)
        self.assertEqual(process.returncode, 0, process.stderr)
        self.assertEqual(
            self.inbound_cidrs(process.stdout), values["gateway"]["allowedCidrs"]
        )


if __name__ == "__main__":
    unittest.main()
