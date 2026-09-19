"""ALB 경계·준비 대기·실제 Helm 렌더링 회귀 검사."""

import json
import os
import subprocess
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings


def alb_outputs():
    return {
        "vpc_id": "vpc-aaaaaaaaaaaaaaaaa",
        "public_subnet_ids": ["subnet-aaaaaaaaaaaaaaaaa", "subnet-bbbbbbbbbbbbbbbbb"],
        "public_subnet_cidrs": ["10.40.0.0/24", "10.40.1.0/24"],
        "alb_security_group_id": "sg-aaaaaaaaaaaaaaaaa",
        "workload_security_group_id": "sg-bbbbbbbbbbbbbbbbb",
    }


def load_balancer():
    return {
        "DNSName": "fixture.ap-northeast-2.elb.amazonaws.com",
        "Type": "application",
        "Scheme": "internet-facing",
        "State": {"Code": "active"},
        "SecurityGroups": [alb_outputs()["alb_security_group_id"]],
        "VpcId": alb_outputs()["vpc_id"],
        "AvailabilityZones": [
            {"SubnetId": value} for value in alb_outputs()["public_subnet_ids"]
        ],
        "LoadBalancerArn": "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/scenetrip/123",
    }


class AlbBoundaryTest(unittest.TestCase):
    def test_pending_or_wrong_environment_alb_cannot_pass_readiness(self):
        from tools.aws.alb import application_ready

        valid = load_balancer()
        for changes in (
            {"Scheme": "internal"},
            {"SecurityGroups": ["sg-ccccccccccccccccc"]},
            {"VpcId": "vpc-ccccccccccccccccc"},
            {"AvailabilityZones": []},
            {
                "LoadBalancerArn": valid["LoadBalancerArn"].replace(
                    "123456789012", "999999999999"
                )
            },
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                application_ready(
                    Mock(
                        return_value=json.dumps(
                            {"LoadBalancers": [{**valid, **changes}]}
                        )
                    ),
                    valid_settings(),
                    alb_outputs(),
                    valid["DNSName"],
                )
        with self.assertRaisesRegex(RuntimeError, "failed"):
            application_ready(
                Mock(
                    return_value=json.dumps(
                        {"LoadBalancers": [{**valid, "State": {"Code": "failed"}}]}
                    )
                ),
                valid_settings(),
                alb_outputs(),
                valid["DNSName"],
            )
        with self.assertRaisesRegex(ValueError, "하나"):
            application_ready(
                Mock(return_value=json.dumps({"LoadBalancers": [valid, valid]})),
                valid_settings(),
                alb_outputs(),
                valid["DNSName"],
            )
        for items in ([], [{**valid, "State": {"Code": "provisioning"}}]):
            run = Mock(return_value=json.dumps({"LoadBalancers": items}))
            self.assertFalse(
                application_ready(
                    run, valid_settings(), alb_outputs(), valid["DNSName"]
                )
            )
            self.assertEqual(run.call_count, 1)

    def test_empty_or_incorrect_backend_cannot_pass_readiness(self):
        from tools.aws.alb import healthy_targets

        self.assertFalse(
            healthy_targets(
                Mock(return_value=json.dumps({"TargetGroups": []})), "fixture"
            )
        )
        for changes in (
            {"TargetType": "instance"},
            {"Port": 8899},
            {"Protocol": "HTTPS"},
        ):
            group = {
                "TargetGroupArn": "fixture",
                "TargetType": "ip",
                "Protocol": "HTTP",
                "Port": 8080,
                **changes,
            }
            with (
                self.subTest(changes=changes),
                self.assertRaisesRegex(ValueError, "HTTP:8080"),
            ):
                healthy_targets(
                    Mock(return_value=json.dumps({"TargetGroups": [group]})), "fixture"
                )
        run = Mock(
            side_effect=[
                json.dumps(
                    {
                        "TargetGroups": [
                            {
                                "TargetGroupArn": "fixture",
                                "TargetType": "ip",
                                "Protocol": "HTTP",
                                "Port": 8080,
                            }
                        ]
                    }
                ),
                json.dumps({"TargetHealthDescriptions": []}),
            ]
        )
        self.assertFalse(healthy_targets(run, "fixture"))

    def test_outputs_require_matching_subnets_and_narrow_trust(self):
        from tools.aws.alb import alb_values

        self.assertEqual(
            alb_values(alb_outputs())["trustedProxyCidrs"],
            ["10.40.0.0/24", "10.40.1.0/24"],
        )
        for changes in (
            {"public_subnet_cidrs": ["0.0.0.0/0", "10.40.1.0/24"]},
            {"public_subnet_ids": ["subnet-aaaaaaaaaaaaaaaaa"]},
            {"public_subnet_ids": ["subnet-aaaaaaaaaaaaaaaaa"] * 2},
            {"public_subnet_cidrs": ["10.40.0.0/24"]},
            {"alb_security_group_id": "sg-malicious;"},
            {"public_subnet_cidrs": ["127.0.0.0/24", "10.40.1.0/24"]},
            {"public_subnet_cidrs": ["192.0.2.0/24", "10.40.1.0/24"]},
            {"public_subnet_cidrs": ["10.40.0.0/16", "10.41.0.0/16"]},
            {"public_subnet_cidrs": ["10.40.0.0/28", "10.40.1.0/28"]},
            {"public_subnet_ids": [["invalid"], "subnet-bbbbbbbbbbbbbbbbb"]},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                alb_values({**alb_outputs(), **changes})

    def test_nodeclass_must_resolve_workload_security_group(self):
        from tools.aws.alb import verify_nodeclass

        run = Mock(
            side_effect=[
                "",
                json.dumps(
                    {"status": {"securityGroups": [{"id": "sg-bbbbbbbbbbbbbbbbb"}]}}
                ),
            ]
        )
        verify_nodeclass(run, alb_outputs())
        self.assertIn("nodeclass/default", run.call_args_list[0].args[0])
        run = Mock(
            side_effect=[
                "",
                json.dumps(
                    {"status": {"securityGroups": [{"id": "sg-ccccccccccccccccc"}]}}
                ),
            ]
        )
        with self.assertRaisesRegex(ValueError, "NodeClass"):
            verify_nodeclass(run, alb_outputs())
        run = Mock(
            side_effect=[
                "",
                json.dumps(
                    {
                        "status": {"securityGroups": [{"id": "sg-bbbbbbbbbbbbbbbbb"}]},
                        "spec": {
                            "podSecurityGroupSelectorTerms": [
                                {"id": "sg-ccccccccccccccccc"}
                            ]
                        },
                    }
                ),
            ]
        )
        with self.assertRaisesRegex(ValueError, "Pod"):
            verify_nodeclass(run, alb_outputs())

    def test_wait_uses_ingress_and_retries_until_alb_targets_healthy(self):
        from tools.aws.alb import wait_for_alb

        hostname = "fixture.ap-northeast-2.elb.amazonaws.com"
        lb = {
            "DNSName": hostname,
            "Type": "application",
            "Scheme": "internet-facing",
            "VpcId": alb_outputs()["vpc_id"],
            "AvailabilityZones": [
                {"SubnetId": value} for value in alb_outputs()["public_subnet_ids"]
            ],
            "State": {"Code": "active"},
            "SecurityGroups": ["sg-aaaaaaaaaaaaaaaaa"],
            "LoadBalancerArn": "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/scenetrip/123",
        }
        group = {
            "TargetGroupArn": "group-fixture",
            "TargetType": "ip",
            "Protocol": "HTTP",
            "Port": 8080,
        }
        run = Mock(
            side_effect=[
                json.dumps({"status": {}}),
                json.dumps(
                    {"status": {"loadBalancer": {"ingress": [{"hostname": hostname}]}}}
                ),
                json.dumps({"LoadBalancers": [lb]}),
                json.dumps({"TargetGroups": [group]}),
                json.dumps(
                    {
                        "TargetHealthDescriptions": [
                            {"TargetHealth": {"State": "initial"}}
                        ]
                    }
                ),
                json.dumps(
                    {"status": {"loadBalancer": {"ingress": [{"hostname": hostname}]}}}
                ),
                json.dumps({"LoadBalancers": [lb]}),
                json.dumps({"TargetGroups": [group]}),
                json.dumps(
                    {
                        "TargetHealthDescriptions": [
                            {"TargetHealth": {"State": "healthy"}}
                        ]
                    }
                ),
            ]
        )
        with patch("tools.aws.alb.time.sleep") as sleep:
            self.assertEqual(
                wait_for_alb(run, valid_settings(), alb_outputs(), timeout=30), hostname
            )
        self.assertEqual(sleep.call_count, 2)
        self.assertIn("ingress", run.call_args_list[0].args[0])
        self.assertNotIn("service", run.call_args_list[0].args[0])

    def test_wait_rejects_wrong_load_balancer_type_and_timeout(self):
        from tools.aws.alb import wait_for_alb

        hostname = "fixture.ap-northeast-2.elb.amazonaws.com"
        ingress = json.dumps(
            {"status": {"loadBalancer": {"ingress": [{"hostname": hostname}]}}}
        )
        run = Mock(
            side_effect=[
                ingress,
                json.dumps(
                    {"LoadBalancers": [{"DNSName": hostname, "Type": "network"}]}
                ),
            ]
        )
        with self.assertRaisesRegex(ValueError, "application"):
            wait_for_alb(run, valid_settings(), alb_outputs(), timeout=30)
        run = Mock(return_value=json.dumps({"status": {}}))
        with (
            patch("tools.aws.alb.time.monotonic", side_effect=[0, 0, 2]),
            patch("tools.aws.alb.time.sleep"),
            self.assertRaisesRegex(TimeoutError, "ALB"),
        ):
            wait_for_alb(run, valid_settings(), alb_outputs(), timeout=1)


class AlbChartTest(unittest.TestCase):
    def test_both_environments_render_alb_with_proxy_and_network_boundaries(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        values = {
            "database": {"host": "db.example.internal"},
            "sceneApi": {"image": "fixture:api"},
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
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                process = subprocess.run(
                    [
                        str(root / "tools/bazel/cloud/helm"),
                        "template",
                        "scenetrip",
                        str(root / "platform/helm/scenetrip"),
                        "--namespace",
                        "scenetrip",
                        "--values",
                        str(
                            root / f"platform/helm/scenetrip/values-{environment}.yaml"
                        ),
                        "--values",
                        "-",
                    ],
                    input=json.dumps(values),
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(process.returncode, 0, process.stderr)
                rendered = process.stdout
                self.assertIn("controller: eks.amazonaws.com/alb", rendered)
                self.assertIn("kind: IngressClassParams", rendered)
                self.assertIn("kind: Ingress\n", rendered)
                self.assertNotIn("loadBalancerClass:", rendered)
                self.assertNotIn("type: LoadBalancer", rendered)
                self.assertIn(
                    "alb.ingress.kubernetes.io/listen-ports: '[{\"HTTPS\":443}]'",
                    rendered,
                )
                self.assertIn("alb.ingress.kubernetes.io/target-type: ip", rendered)
                self.assertIn("real_ip_recursive off;", rendered)
                self.assertIn("set_real_ip_from 10.40.0.0/24;", rendered)
                self.assertIn("geo $realip_remote_addr $trusted_alb_peer", rendered)
                self.assertIn("cidr: 10.40.0.0/24", rendered)
                self.assertIn(
                    "routing.http.xff_header_processing.mode=append", rendered
                )
                self.assertIn("idle_timeout.timeout_seconds=60", rendered)
