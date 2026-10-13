"""클러스터가 살아 있을 때 Ingress·ALB를 먼저 정리하는 삭제 회귀 검사."""

import json
import unittest
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import valid_settings
from tools.aws.tests.test_alb import load_balancer, signoz_load_balancer


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
            patch("tools.aws.destroy_gateway.get_signoz_ingress", return_value=None),
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
            # SigNoz 화면 Ingress(ADR 0025)는 이미 없다 — 정리 대기는 SigNozGatewayTeardownTest 가 시험한다.
            patch("tools.aws.destroy_gateway.get_signoz_ingress", return_value=None),
            # SigNoz 정리(ADR 0025)는 따로 시험한다(RemoveSignozTest) — 여기서는 불린 시점만 본다.
            patch(
                "tools.aws.destroy_gateway.remove_signoz",
                side_effect=lambda *unused, **kwargs: run("remove_signoz"),
            ) as remove_signoz,
        ):
            run.return_value = json.dumps(
                [{"name": "scenetrip", "namespace": "scenetrip"}]
            )
            remove_gateway(run, valid_settings(), gateway_state())
        remove_signoz.assert_called_once()
        self.assertEqual(run.call_args_list[-1].args[0], "remove_signoz")
        commands = [entry.args[0] for entry in run.call_args_list[:-1]]
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
            patch("tools.aws.destroy_gateway.get_signoz_ingress", return_value=None),
            patch("tools.aws.destroy_gateway.remove_signoz") as remove_signoz,
        ):
            remove_gateway(run, valid_settings(), gateway_state())
        # SigNoz 정리는 scenetrip 릴리스가 없어도 불린다(남은 디스크 정리) — 동작은 RemoveSignozTest.
        remove_signoz.assert_called_once()
        # SigNoz 화면 Ingress 삭제(ADR 0025)는 --ignore-not-found 로 항상 보낸다 — 그것 말고는 지우거나 해제하지 않는다.
        signoz_delete = [
            "kubectl",
            "-n",
            "signoz",
            "delete",
            "ingress",
            "signoz-ui",
            "--ignore-not-found",
            "--wait=false",
            "--request-timeout=15s",
        ]
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(commands.count(signoz_delete), 1)
        self.assertFalse(
            any(
                "delete" in command or "uninstall" in command
                for command in commands
                if command != signoz_delete
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
            patch("tools.aws.destroy_gateway.get_signoz_ingress", return_value=None),
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


SIGNOZ_INGRESS_READ = [
    "kubectl",
    "-n",
    "signoz",
    "get",
    "ingress",
    "signoz-ui",
    "--ignore-not-found",
    "-o",
    "json",
    "--request-timeout=15s",
]
SIGNOZ_INGRESS_DELETE = [
    "kubectl",
    "-n",
    "signoz",
    "delete",
    "ingress",
    "signoz-ui",
    "--ignore-not-found",
    "--wait=false",
    "--request-timeout=15s",
]


def signoz_state():
    """API ALB 와 SigNoz 화면 ALB(ADR 0025)의 보안 그룹이 모두 있는 state."""
    return {
        **gateway_state(),
        "aws_security_group.signoz_alb": {"id": "sg-ddddddddddddddddd"},
    }


def signoz_ingress():
    return {
        "metadata": {"name": "signoz-ui", "namespace": "signoz"},
        "status": {
            "loadBalancer": {
                "ingress": [{"hostname": signoz_load_balancer()["DNSName"]}]
            }
        },
    }


def listing(*balancers):
    return Mock(return_value=json.dumps({"LoadBalancers": list(balancers)}))


class SignozGatewayTeardownTest(unittest.TestCase):
    """내리기가 SigNoz 화면 ALB 도 클러스터가 살아 있을 때 지운다(ADR 0025)."""

    def test_api_and_signoz_albs_are_both_recognized(self):
        from tools.aws.destroy_gateway import matching_albs, validate_alb

        settings = valid_settings()
        validate_alb(signoz_load_balancer(), settings, signoz_state())
        validate_alb(load_balancer(), settings, signoz_state())
        self.assertEqual(
            matching_albs(
                listing(load_balancer(), signoz_load_balancer()),
                settings,
                signoz_state(),
            ),
            [load_balancer(), signoz_load_balancer()],
        )
        self.assertEqual(
            matching_albs(listing(signoz_load_balancer()), settings, signoz_state()),
            [signoz_load_balancer()],
        )

    def test_signoz_group_without_state_or_with_extra_group_is_rejected(self):
        from tools.aws.destroy_gateway import validate_alb

        settings = valid_settings()
        # state 에 SigNoz 보안 그룹이 없으면 그 그룹을 단 ALB 는 남의 것이다.
        with self.assertRaises(ValueError):
            validate_alb(signoz_load_balancer(), settings, gateway_state())
        for groups in (
            ["sg-ddddddddddddddddd", "sg-aaaaaaaaaaaaaaaaa"],
            [],
            ["sg-eeeeeeeeeeeeeeeee"],
        ):
            with self.subTest(groups=groups), self.assertRaises(ValueError):
                validate_alb(
                    {**signoz_load_balancer(), "SecurityGroups": groups},
                    settings,
                    signoz_state(),
                )
        # 보안 그룹 말고 다른 검사는 그대로다.
        for changes in (
            {"Type": "network"},
            {"Scheme": "internal"},
            {"AvailabilityZones": []},
            {
                "LoadBalancerArn": signoz_load_balancer()["LoadBalancerArn"].replace(
                    "123456789012", "999999999999"
                )
            },
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                validate_alb(
                    {**signoz_load_balancer(), **changes}, settings, signoz_state()
                )

    def test_three_albs_or_two_with_same_group_are_rejected(self):
        from tools.aws.destroy_gateway import matching_albs

        settings = valid_settings()
        third = {**signoz_load_balancer(), "DNSName": "third.elb.amazonaws.com"}
        with self.assertRaisesRegex(ValueError, "많은"):
            matching_albs(
                listing(load_balancer(), signoz_load_balancer(), third),
                settings,
                signoz_state(),
            )
        for pair in (
            (signoz_load_balancer(), third),
            (
                load_balancer(),
                {**load_balancer(), "DNSName": "second.elb.amazonaws.com"},
            ),
        ):
            with self.subTest(pair=pair), self.assertRaises(ValueError):
                matching_albs(listing(*pair), settings, signoz_state())

    def test_albs_in_other_vpc_are_ignored(self):
        from tools.aws.destroy_gateway import matching_albs

        foreign = {**signoz_load_balancer(), "VpcId": "vpc-ccccccccccccccccc"}
        self.assertEqual(
            matching_albs(
                listing(load_balancer(), signoz_load_balancer(), foreign),
                valid_settings(),
                signoz_state(),
            ),
            [load_balancer(), signoz_load_balancer()],
        )

    def test_signoz_ingress_read_and_delete_commands(self):
        from tools.aws.destroy_gateway import delete_signoz_ingress, get_signoz_ingress

        run = Mock(return_value="")
        self.assertIsNone(get_signoz_ingress(run))
        self.assertEqual(run.call_args.args[0], SIGNOZ_INGRESS_READ)
        self.assertIsNone(get_signoz_ingress(Mock(return_value="  \n")))
        self.assertEqual(
            get_signoz_ingress(Mock(return_value=json.dumps(signoz_ingress()))),
            signoz_ingress(),
        )
        run = Mock(return_value="")
        delete_signoz_ingress(run)
        run.assert_called_once()
        self.assertEqual(run.call_args.args[0], SIGNOZ_INGRESS_DELETE)

    def test_gateway_hosts_must_be_subset_of_found_albs(self):
        from tools.aws.destroy_gateway import validate_ingress

        settings = valid_settings()
        validate_ingress(ingress(), settings, [load_balancer(), signoz_load_balancer()])
        validate_ingress(ingress(), settings, [load_balancer()])
        with self.assertRaisesRegex(ValueError, "DNS"):
            validate_ingress(ingress(), settings, [signoz_load_balancer()])
        two_hosts = ingress()
        two_hosts["status"]["loadBalancer"]["ingress"].append(
            {"hostname": "stray.ap-northeast-2.elb.amazonaws.com"}
        )
        with self.assertRaisesRegex(ValueError, "DNS"):
            validate_ingress(
                two_hosts, settings, [load_balancer(), signoz_load_balancer()]
            )

    def test_remove_deletes_both_ingresses_before_waiting_then_uninstalls(self):
        from tools.aws.destroy_gateway import remove_gateway

        def execute(command, **unused):
            if command[:2] == ["helm", "list"]:
                return json.dumps([{"name": "scenetrip", "namespace": "scenetrip"}])
            return ""

        run = Mock(side_effect=execute)
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                return_value=[load_balancer(), signoz_load_balancer()],
            ),
            patch("tools.aws.destroy_gateway.get_ingress", return_value=ingress()),
            patch(
                "tools.aws.destroy_gateway.wait_gateway_absent",
                side_effect=lambda *unused, **kw: run(["wait_gateway_absent"]),
            ),
            patch("tools.aws.destroy_gateway.remove_signoz"),
        ):
            remove_gateway(run, valid_settings(), signoz_state())
        commands = [entry.args[0] for entry in run.call_args_list]
        gateway_delete = next(
            i
            for i, command in enumerate(commands)
            if "delete" in command and "gateway" in command
        )
        signoz_delete = commands.index(SIGNOZ_INGRESS_DELETE)
        wait = commands.index(["wait_gateway_absent"])
        uninstall = next(
            i for i, command in enumerate(commands) if "uninstall" in command
        )
        self.assertLess(gateway_delete, signoz_delete)
        self.assertLess(signoz_delete, wait)
        self.assertLess(wait, uninstall)
        self.assertEqual(commands.count(SIGNOZ_INGRESS_DELETE), 1)

    def test_remove_without_gateway_ingress_still_deletes_signoz_ingress(self):
        from tools.aws.destroy_gateway import remove_gateway

        run = Mock(return_value="[]")
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                return_value=[signoz_load_balancer()],
            ),
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
            patch("tools.aws.destroy_gateway.wait_gateway_absent") as wait,
            patch("tools.aws.destroy_gateway.remove_signoz"),
        ):
            remove_gateway(run, valid_settings(), signoz_state())
        commands = [entry.args[0] for entry in run.call_args_list]
        self.assertIn(SIGNOZ_INGRESS_DELETE, commands)
        self.assertFalse(
            any("delete" in command and "gateway" in command for command in commands)
        )
        wait.assert_called_once()

    def test_wait_continues_while_signoz_ingress_or_any_alb_remains(self):
        from tools.aws.destroy_gateway import POLL_SECONDS, wait_gateway_absent

        with (
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
            patch(
                "tools.aws.destroy_gateway.get_signoz_ingress",
                side_effect=[signoz_ingress(), None, None],
            ),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                side_effect=[[signoz_load_balancer()], [signoz_load_balancer()], []],
            ),
            patch("tools.aws.destroy_gateway.time.sleep") as sleep,
        ):
            wait_gateway_absent(Mock(), valid_settings(), signoz_state())
        self.assertEqual(
            [entry.args for entry in sleep.call_args_list],
            [(POLL_SECONDS,), (POLL_SECONDS,)],
        )

    def test_wait_times_out_on_leftover_signoz_ingress(self):
        from tools.aws.destroy_gateway import wait_gateway_absent

        with (
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
            patch(
                "tools.aws.destroy_gateway.get_signoz_ingress",
                return_value=signoz_ingress(),
            ),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.time.monotonic", side_effect=[0, 2]),
            patch("tools.aws.destroy_gateway.time.sleep"),
            self.assertRaises(TimeoutError),
        ):
            wait_gateway_absent(Mock(), valid_settings(), signoz_state(), timeout=1)


if __name__ == "__main__":
    unittest.main()
