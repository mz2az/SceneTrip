"""클러스터가 살아 있을 때 Ingress·ALB를 먼저 정리하는 삭제 회귀 검사."""

import json
import unittest
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings
from tools.aws.tests.test_alb import load_balancer


def gateway_state():
    return {
        "aws_eks_access_entry.deployment": {},
        "aws_eks_access_policy_association.deployment": {},
        "aws_vpc.this": {"id": "vpc-aaaaaaaaaaaaaaaaa"},
        "aws_security_group.alb": {"id": "sg-aaaaaaaaaaaaaaaaa"},
        "aws_subnet.public[0]": {"id": "subnet-aaaaaaaaaaaaaaaaa"},
        "aws_subnet.public[1]": {"id": "subnet-bbbbbbbbbbbbbbbbb"},
        "aws_eks_cluster.this": {
            "arn": "arn:aws:eks:ap-northeast-2:123456789012:cluster/scenetrip-dev",
            "endpoint": "https://fixture.eks.amazonaws.com",
        },
    }


def cluster():
    return {
        **gateway_state()["aws_eks_cluster.this"],
        "name": "scenetrip-dev",
        "status": "ACTIVE",
        "resourcesVpcConfig": {"vpcId": "vpc-aaaaaaaaaaaaaaaaa"},
    }


def ingress():
    return {
        "metadata": {
            "name": "gateway",
            "namespace": "scenetrip",
            "annotations": {
                "meta.helm.sh/release-name": "scenetrip",
                "meta.helm.sh/release-namespace": "scenetrip",
            },
        },
        "spec": {"ingressClassName": "scenetrip-dev-alb"},
        "status": {
            "loadBalancer": {"ingress": [{"hostname": load_balancer()["DNSName"]}]}
        },
    }


class DestroyGatewayTest(unittest.TestCase):
    def test_reading_ingress_and_helm_does_not_mask_absence_or_malformed_identity(self):
        from tools.aws.destroy_gateway import get_ingress, helm_release, matching_albs

        self.assertIsNone(get_ingress(Mock(return_value="")))
        self.assertEqual(
            get_ingress(Mock(return_value=json.dumps(ingress()))), ingress()
        )
        for value in ({}, [{"name": "foreign", "namespace": "scenetrip"}], [{}, {}]):
            with self.subTest(value=value), self.assertRaises(ValueError):
                helm_release(Mock(return_value=json.dumps(value)))
        with self.assertRaises(TypeError):
            matching_albs(
                Mock(return_value='{"LoadBalancers": {}}'),
                valid_settings(),
                gateway_state(),
            )
        with self.assertRaises(ValueError):
            matching_albs(
                Mock(
                    return_value=json.dumps(
                        {"LoadBalancers": [load_balancer(), load_balancer()]}
                    )
                ),
                valid_settings(),
                gateway_state(),
            )

    def test_deleted_cluster_without_alb_and_short_retry_are_allowed(self):
        from tools.aws.destroy_gateway import (
            remove_gateway,
            validate_ingress,
            wait_gateway_absent,
        )

        run = Mock()
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=None),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
        ):
            remove_gateway(run, valid_settings(), gateway_state())
        run.assert_not_called()
        with (
            patch(
                "tools.aws.destroy_gateway.get_ingress", side_effect=[ingress(), None]
            ),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.time.sleep") as sleep,
        ):
            wait_gateway_absent(run, valid_settings(), gateway_state())
        sleep.assert_called_once()
        with self.assertRaises(ValueError):
            validate_ingress(
                ingress(),
                valid_settings(),
                [{**load_balancer(), "DNSName": "other.elb.amazonaws.com"}],
            )

    def test_partial_destroy_without_access_skips_kubernetes_only_when_alb_absent(self):
        from tools.aws.destroy_gateway import remove_gateway

        partial = {
            key: value
            for key, value in gateway_state().items()
            if not key.startswith("aws_eks_access")
        }
        run = Mock()
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
        ):
            remove_gateway(run, valid_settings(), partial)
        run.assert_not_called()
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                return_value=[load_balancer()],
            ),
            self.assertRaises(RuntimeError),
        ):
            remove_gateway(run, valid_settings(), partial)

    def test_live_cluster_identity_and_network_are_exact(self):
        from tools.aws.destroy_gateway import live_cluster

        for changes in (
            {"arn": "wrong"},
            {"endpoint": "https://other"},
            {"resourcesVpcConfig": {"vpcId": "vpc-other"}},
            {"status": "CREATING"},
        ):
            run = Mock(
                side_effect=[
                    json.dumps({"clusters": ["scenetrip-dev"]}),
                    json.dumps({"cluster": {**cluster(), **changes}}),
                ]
            )
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                live_cluster(run, valid_settings(), gateway_state())
        run = Mock(
            side_effect=[
                json.dumps({"clusters": ["scenetrip-dev"]}),
                json.dumps({"cluster": cluster()}),
            ]
        )
        self.assertEqual(
            live_cluster(run, valid_settings(), gateway_state()), cluster()
        )

    def test_absent_cluster_is_explicit_and_errors_are_not_swallowed(self):
        from tools.aws.destroy_gateway import live_cluster

        self.assertIsNone(
            live_cluster(
                Mock(return_value='{"clusters": []}'), valid_settings(), gateway_state()
            )
        )
        with self.assertRaises(RuntimeError):
            live_cluster(
                Mock(side_effect=RuntimeError("denied")),
                valid_settings(),
                gateway_state(),
            )
        with self.assertRaises(ValueError):
            live_cluster(
                Mock(return_value='{"clusters": ["scenetrip-dev"]}'),
                valid_settings(),
                {},
            )

    def test_alb_identity_rejects_wrong_type_security_group_subnet_and_account(self):
        from tools.aws.destroy_gateway import matching_albs

        lb = load_balancer()
        for changes in (
            {"Type": "network"},
            {"SecurityGroups": ["sg-other"]},
            {"AvailabilityZones": []},
            {
                "LoadBalancerArn": lb["LoadBalancerArn"].replace(
                    "123456789012", "999999999999"
                )
            },
        ):
            run = Mock(return_value=json.dumps({"LoadBalancers": [{**lb, **changes}]}))
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                matching_albs(run, valid_settings(), gateway_state())
        run = Mock(return_value=json.dumps({"LoadBalancers": [lb]}))
        self.assertEqual(matching_albs(run, valid_settings(), gateway_state()), [lb])

    def test_ingress_and_alb_disappear_before_helm_and_never_force_finalizers(self):
        from tools.aws.destroy_gateway import remove_gateway

        run = Mock()
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                side_effect=[[load_balancer()], []],
            ),
            patch(
                "tools.aws.destroy_gateway.get_ingress", side_effect=[ingress(), None]
            ),
        ):
            run.return_value = json.dumps(
                [{"name": "scenetrip", "namespace": "scenetrip"}]
            )
            remove_gateway(run, valid_settings(), gateway_state())
        commands = [entry.args[0] for entry in run.call_args_list]
        delete = next(i for i, command in enumerate(commands) if "delete" in command)
        uninstall = next(
            i for i, command in enumerate(commands) if "uninstall" in command
        )
        self.assertLess(delete, uninstall)
        self.assertNotIn("patch", [part for command in commands for part in command])
        self.assertIn("--no-hooks", commands[uninstall])

    def test_missing_helm_and_ingress_are_restartable(self):
        from tools.aws.destroy_gateway import remove_gateway

        run = Mock(return_value="[]")
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
        ):
            remove_gateway(run, valid_settings(), gateway_state())
        self.assertFalse(
            any(
                "delete" in call.args[0] or "uninstall" in call.args[0]
                for call in run.call_args_list
            )
        )

    def test_orphan_alb_without_cluster_and_wrong_ingress_fail_before_mutation(self):
        from tools.aws.destroy_gateway import remove_gateway, validate_ingress

        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=None),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                return_value=[load_balancer()],
            ),
            self.assertRaises(RuntimeError),
        ):
            remove_gateway(Mock(), valid_settings(), gateway_state())
        for item in (
            {**ingress(), "metadata": {"name": "foreign"}},
            {**ingress(), "spec": {"ingressClassName": "other"}},
        ):
            with self.assertRaises(ValueError):
                validate_ingress(item, valid_settings(), [load_balancer()])

    def test_wait_has_deadline_and_propagates_api_failures(self):
        from tools.aws.destroy_gateway import wait_gateway_absent

        with (
            patch("tools.aws.destroy_gateway.get_ingress", return_value=ingress()),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.time.monotonic", side_effect=[0, 2]),
            self.assertRaises(TimeoutError),
        ):
            wait_gateway_absent(Mock(), valid_settings(), gateway_state(), timeout=1)
        with (
            patch(
                "tools.aws.destroy_gateway.get_ingress",
                side_effect=RuntimeError("denied"),
            ),
            self.assertRaises(RuntimeError),
        ):
            wait_gateway_absent(Mock(), valid_settings(), gateway_state())


if __name__ == "__main__":
    unittest.main()
