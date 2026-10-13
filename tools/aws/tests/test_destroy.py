"""서비스 삭제의 실행 순서, 조회 전용 계획, 부분 삭제와 스냅샷 검사."""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.destroy_plan import resource_values
from tools.aws.tests.fixtures import valid_settings
from tools.aws.tests.test_destroy_plan import change, plan, resource


def database_values():
    return {
        "tags_all": {
            "Project": "scenetrip",
            "Environment": "dev",
            "ManagedBy": "terraform",
        },
        "identifier": "scenetrip-dev",
        "arn": "arn:aws:rds:ap-northeast-2:123456789012:db:scenetrip-dev",
        "deletion_protection": False,
        "skip_final_snapshot": False,
        "final_snapshot_identifier": "scenetrip-dev-final-123-1",
        "delete_automated_backups": True,
    }


def database_plan():
    values = database_values()
    return plan(
        [change("aws_db_instance.postgres", values, None, ["delete"])],
        [resource("aws_db_instance.postgres", values)],
    )


def preparation_plan():
    values = database_values()
    return {
        **plan([change("aws_db_instance.postgres", values, values, ["no-op"])]),
        "complete": False,
    }


class DestroyServiceTest(unittest.TestCase):
    def test_new_resource_or_reappearing_alb_blocks_final_destroy(self):
        from tools.aws.destroy import finish_destroy

        new_values = {
            "id": "vpc-aaaaaaaaaaaaaaaaa",
            "tags_all": database_values()["tags_all"],
        }
        unexpected = plan(
            [change("aws_vpc.this", new_values, None, ["delete"])],
            [resource("aws_vpc.this", new_values)],
        )
        run = Mock()
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            with (
                patch("tools.aws.destroy.saved_plan", return_value=unexpected),
                self.assertRaises(ValueError),
            ):
                finish_destroy(run, valid_settings(), temp, temp / "vars", temp, {}, {})
            with (
                patch("tools.aws.destroy.saved_plan", return_value=plan()),
                patch("tools.aws.destroy.matching_albs", return_value=[{}]),
                self.assertRaises(RuntimeError),
            ):
                finish_destroy(run, valid_settings(), temp, temp / "vars", temp, {}, {})
        run.assert_not_called()

    def run_service(self, run, *, execute=False, plans=None, snapshot_policy="retain"):
        from tools.aws.destroy import destroy_service

        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            with (
                patch(
                    "tools.aws.destroy.saved_plan",
                    side_effect=plans
                    or [database_plan(), preparation_plan(), database_plan()],
                ) as saved,
                patch("tools.aws.destroy.remove_gateway") as gateway,
            ):
                destroy_service(
                    run,
                    valid_settings(),
                    temp,
                    {"environment": "dev"},
                    temp,
                    execute=execute,
                    snapshot_policy=snapshot_policy,
                )
                return saved, gateway

    def test_plan_never_applies_or_mutates_kubernetes_or_aws(self):
        run = Mock(return_value='{"DBSnapshots": []}')
        saved, gateway = self.run_service(run)
        gateway.assert_not_called()
        self.assertEqual(saved.call_count, 2)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertFalse(
            any(
                "apply" in command or command[0] in {"helm", "kubectl"}
                for command in commands
            )
        )
        self.assertTrue(saved.call_args_list[0].kwargs["destroy"])
        self.assertEqual(
            saved.call_args_list[1].kwargs["targets"], ["aws_db_instance.postgres"]
        )

    def test_execute_applies_only_after_cleanup_and_regenerates_destroy_plan(self):
        from tools.aws.destroy import destroy_service

        events = []

        def run(command, **_kwargs):
            events.append(command[0:2])
            return '{"DBSnapshots": []}' if command[0] == "aws" else ""

        def saved(*_args, **kwargs):
            events.append(["plan", "destroy" if kwargs.get("destroy") else "prepare"])
            return database_plan() if kwargs.get("destroy") else preparation_plan()

        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            with (
                patch("tools.aws.destroy.saved_plan", side_effect=saved),
                patch(
                    "tools.aws.destroy.remove_gateway",
                    side_effect=lambda *_: events.append(["gateway", "removed"]),
                ),
            ):
                destroy_service(run, valid_settings(), temp, {}, temp, execute=True)
        selected = [
            event
            for event in events
            if event[0] in {"plan", "gateway"} or event == ["terraform", "apply"]
        ]
        self.assertEqual(
            selected,
            [
                ["plan", "destroy"],
                ["plan", "prepare"],
                ["gateway", "removed"],
                ["terraform", "apply"],
                ["plan", "destroy"],
                ["terraform", "apply"],
            ],
        )

    def test_invalid_preparation_fails_before_gateway_and_cloud_mutations(self):
        invalid = plan([change("aws_vpc.this", {"id": "a"}, {"id": "b"})])
        with self.assertRaises(ValueError):
            self.run_service(
                Mock(return_value='{"DBSnapshots": []}'),
                execute=True,
                plans=[database_plan(), invalid],
            )

    def test_existing_snapshot_or_wrong_database_identity_fails_before_cleanup(self):
        run = Mock(
            return_value=json.dumps(
                {"DBSnapshots": [{"DBSnapshotIdentifier": "scenetrip-dev-final-123-1"}]}
            )
        )
        with self.assertRaisesRegex(ValueError, "스냅샷"):
            self.run_service(run, execute=True)
        invalid = database_plan()
        invalid["prior_state"]["values"]["root_module"]["resources"][0]["values"][
            "identifier"
        ] = "another-environment"
        with self.assertRaises(ValueError):
            self.run_service(Mock(), execute=True, plans=[invalid])

    def test_empty_state_and_discard_need_no_snapshot_reads(self):
        run = Mock(return_value="")
        saved, gateway = self.run_service(run, execute=True, plans=[plan()])
        gateway.assert_not_called()
        self.assertEqual(saved.call_count, 1)
        self.assertFalse(any(call.args[0][0] == "aws" for call in run.call_args_list))
        run = Mock(return_value="")
        discarded = {
            **database_values(),
            "skip_final_snapshot": True,
            "final_snapshot_identifier": None,
        }
        discarded_preparation = plan(
            [change("aws_db_instance.postgres", database_values(), discarded)]
        )
        self.run_service(
            run,
            snapshot_policy="discard",
            plans=[database_plan(), discarded_preparation],
        )
        self.assertFalse(any(call.args[0][0] == "aws" for call in run.call_args_list))

    def test_failed_cleanup_never_applies_and_nonempty_final_state_fails(self):
        from tools.aws.destroy import destroy_service

        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            run = Mock(return_value='{"DBSnapshots": []}')
            with (
                patch(
                    "tools.aws.destroy.saved_plan",
                    side_effect=[database_plan(), preparation_plan()],
                ),
                patch(
                    "tools.aws.destroy.remove_gateway", side_effect=TimeoutError("ALB")
                ),
                self.assertRaises(TimeoutError),
            ):
                destroy_service(run, valid_settings(), temp, {}, temp, execute=True)
            self.assertFalse(
                any("apply" in call.args[0] for call in run.call_args_list)
            )
        run = Mock(
            side_effect=lambda command, **_: (
                '{"DBSnapshots": []}'
                if command[0] == "aws"
                else "aws_vpc.this"
                if command[1:3] == ["state", "list"]
                else ""
            )
        )
        with self.assertRaisesRegex(RuntimeError, "state"):
            self.run_service(run, execute=True)

    def test_final_destroy_uses_prepared_snapshot_policy(self):
        invalid = database_plan()
        invalid["resource_changes"][0]["change"]["before"]["skip_final_snapshot"] = True
        with self.assertRaisesRegex(ValueError, "정책"):
            self.run_service(
                Mock(return_value='{"DBSnapshots": []}'),
                execute=True,
                plans=[database_plan(), preparation_plan(), invalid],
            )


NAT_ADDRESSES = ["aws_eip.nat[0]", "aws_eip.nat[1]"]
REFRESH = [
    "terraform",
    "apply",
    "-refresh-only",
    "-auto-approve",
    "-input=false",
    "-no-color",
    "-lock-timeout=60s",
]


def eip_plan(addresses):
    values = {"id": "eipalloc-aaaaaaaaaaaaaaaaa", "association_id": ""}
    return plan(
        [change(address, values, None, ["delete"]) for address in addresses],
        [resource(address, values) for address in addresses],
    )


class ReleaseDetachedNatAddressesTest(unittest.TestCase):
    """최종 삭제가 NAT EIP 만 남기고 실패했을 때 state 를 새로 읽고 그 EIP 만 다시 지운다."""

    def fake_run(self, states, *, fail_final=True):
        """`terraform state list` 는 states 를 차례로 돌려주고, 첫 최종 apply 는 실패시킨다."""
        commands = []
        states = list(states)

        def run(command, **kwargs):
            commands.append((command, kwargs))
            if command[1:3] == ["state", "list"]:
                return states.pop(0)
            if (
                fail_final
                and command[:2] == ["terraform", "apply"]
                and command[-1].endswith("teardown-destroy.tfplan")
            ):
                raise RuntimeError("UnauthorizedOperation: DisassociateAddress")
            return ""

        return run, commands

    def finish(self, run, plans):
        from tools.aws.destroy import finish_destroy

        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            with (
                patch("tools.aws.destroy.saved_plan", side_effect=plans) as saved,
                patch("tools.aws.destroy.matching_albs", return_value=[]),
                patch("tools.aws.destroy.validate_resource_identity"),
            ):
                resources = resource_values(plans[0])
                finish_destroy(
                    run, valid_settings(), temp, temp / "vars", temp, resources, {}
                )
            return temp, saved

    def test_only_nat_eips_left_refreshes_then_destroys_them(self):
        run, commands = self.fake_run(["\n".join(NAT_ADDRESSES) + "\n", ""])
        with redirect_stdout(io.StringIO()) as output:
            temp, saved = self.finish(
                run, [eip_plan(NAT_ADDRESSES), eip_plan(NAT_ADDRESSES)]
            )
        steps = [command for command, _ in commands]
        self.assertEqual(
            steps,
            [
                [
                    "terraform",
                    "apply",
                    "-input=false",
                    "-no-color",
                    "-lock-timeout=60s",
                    str(temp / "teardown-destroy.tfplan"),
                ],
                ["terraform", "state", "list"],
                [*REFRESH, f"-var-file={temp / 'vars'}"],
                [
                    "terraform",
                    "apply",
                    "-input=false",
                    "-no-color",
                    "-lock-timeout=60s",
                    str(temp / "teardown-release-eip.tfplan"),
                ],
                ["terraform", "state", "list"],
            ],
        )
        retry = saved.call_args_list[1]
        self.assertEqual(retry.args[3], temp / "teardown-release-eip.tfplan")
        self.assertTrue(retry.kwargs["destroy"])
        self.assertIn("서비스 삭제 완료", output.getvalue())

    def test_refresh_command_is_exact_and_quiet_in_terraform_directory(self):
        from tools.aws.destroy import release_detached_nat_addresses

        run, commands = self.fake_run(["aws_eip.nat[2]\n"], fail_final=False)
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            with (
                patch(
                    "tools.aws.destroy.saved_plan",
                    return_value=eip_plan(["aws_eip.nat[2]"]),
                ),
                redirect_stdout(io.StringIO()),
            ):
                self.assertTrue(
                    release_detached_nat_addresses(run, temp, temp / "vars", temp)
                )
        command, kwargs = commands[1]
        self.assertEqual(command, [*REFRESH, f"-var-file={temp / 'vars'}"])
        self.assertEqual(kwargs, {"cwd": temp, "quiet": True})
        self.assertEqual(commands[0][1], {"cwd": temp, "quiet": True})

    def test_other_resources_left_reraises_original_error_without_refresh(self):
        for left in (
            "aws_eip.nat[0]\naws_vpc.this\n",
            "aws_eip.nat[3]\n",
            "aws_eip.other[0]\n",
            "module.x.aws_eip.nat[0]\n",
        ):
            with self.subTest(left=left):
                run, commands = self.fake_run([left])
                with self.assertRaisesRegex(RuntimeError, "UnauthorizedOperation"):
                    self.finish(run, [eip_plan(NAT_ADDRESSES)])
                steps = [command for command, _ in commands]
                self.assertEqual(len(steps), 2)
                self.assertEqual(steps[1], ["terraform", "state", "list"])
                self.assertFalse(any("-refresh-only" in step for step in steps))

    def test_empty_state_list_returns_false_without_other_commands(self):
        from tools.aws.destroy import release_detached_nat_addresses

        run, commands = self.fake_run(["\n"], fail_final=False)
        with (
            tempfile.TemporaryDirectory() as temporary,
            patch("tools.aws.destroy.saved_plan") as saved,
        ):
            temp = Path(temporary)
            self.assertFalse(
                release_detached_nat_addresses(run, temp, temp / "vars", temp)
            )
        self.assertEqual(
            [command for command, _ in commands], [["terraform", "state", "list"]]
        )
        saved.assert_not_called()

    def test_retry_plan_with_another_resource_fails_before_apply(self):
        from tools.aws.destroy import release_detached_nat_addresses

        run, commands = self.fake_run(["aws_eip.nat[0]\n"], fail_final=False)
        with tempfile.TemporaryDirectory() as temporary:
            temp = Path(temporary)
            with (
                patch(
                    "tools.aws.destroy.saved_plan",
                    return_value=eip_plan(["aws_eip.nat[0]", "aws_eip.nat[1]"]),
                ),
                redirect_stdout(io.StringIO()),
                self.assertRaises(ValueError),
            ):
                release_detached_nat_addresses(run, temp, temp / "vars", temp)
        steps = [command for command, _ in commands]
        self.assertEqual(len(steps), 2)
        self.assertIn("-refresh-only", steps[1])


if __name__ == "__main__":
    unittest.main()
