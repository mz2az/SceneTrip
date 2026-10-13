"""바깥 감시 알람(alerts.py, ADR 0025) 명세 검사.

AWS 를 부르지 않는다 — 실행기를 가짜로 바꿔 어떤 명령이 나갔는지만 본다.
"""

import contextlib
import io
import json
import os
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.aws import alerts as alerts_module
from tools.aws import ops

EXPECTED_RESOURCES = {
    "AlertTopic": "AWS::SNS::Topic",
    "ApiHealthCheck": "AWS::Route53::HealthCheck",
    "ApiDownAlarm": "AWS::CloudWatch::Alarm",
    "MonthlyBudget": "AWS::Budgets::Budget",
}
EMAIL = "ops@example.com"
EMAIL_LITERAL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}")


def workspace_root():
    return Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]


def template_text():
    return (workspace_root() / alerts_module.ALERTS_TEMPLATE).read_text()


def captured(function, *args, **kwargs):
    """함수를 돌리고 표준 출력을 돌려준다. 예외는 출력과 함께 다시 던진다."""
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        try:
            function(*args, **kwargs)
        except BaseException as error:
            error.stdout = out.getvalue()
            raise
    return out.getvalue()


class FakeCloudFormation:
    """명령을 기록하는 가짜 실행기. `status=None` 이면 describe-stacks 가 실패한다(스택 없음)."""

    def __init__(self, *, status=None, changes=(), fail_wait=None, events=()):
        self.calls = []
        self.status = status
        self.changes = list(changes)
        self.fail_wait = fail_wait
        self.events = list(events)

    def __call__(self, command, **_kwargs):
        self.calls.append(list(command))
        assert command[:2] == ["aws", "cloudformation"], command
        operation = command[2]
        if operation == "describe-stacks":
            if self.status is None:
                raise RuntimeError("Stack with id scenetrip-alerts does not exist")
            return json.dumps({"Stacks": [{"StackStatus": self.status}]})
        if operation == "wait":
            if command[3] == self.fail_wait:
                raise RuntimeError(f"Waiter {command[3]} failed")
            return ""
        if operation == "describe-change-set":
            return json.dumps({"Changes": self.changes})
        if operation == "describe-stack-events":
            return json.dumps({"StackEvents": self.events})
        return "{}"

    def operations(self):
        return [c[2] if c[2] != "wait" else f"wait {c[3]}" for c in self.calls]

    def commands(self, operation):
        return [c for c in self.calls if c[2] == operation]


def flag(command, name):
    return command[command.index(name) + 1]


def invoke(run, *, execute, email=EMAIL):
    with tempfile.TemporaryDirectory() as temporary:
        temp = Path(temporary)
        out = captured(
            alerts_module.alerts,
            run,
            workspace_root(),
            temp,
            email=email,
            execute=execute,
        )
        return out, temp


class AlertsTemplateTest(unittest.TestCase):
    def setUp(self):
        self.text = template_text()
        self.template = json.loads(self.text)
        self.resources = self.template["Resources"]

    def props(self, logical):
        return self.resources[logical]["Properties"]

    def test_constants(self):
        self.assertEqual(alerts_module.ALERTS_STACK, "scenetrip-alerts")
        self.assertEqual(alerts_module.ALERTS_REGION, "us-east-1")
        self.assertEqual(
            alerts_module.ALERTS_TEMPLATE, "platform/terraform/alerts/template.json"
        )
        self.assertEqual(alerts_module.CHANGE_SET, "scenetrip-alerts-change")

    def test_parameters(self):
        parameters = self.template["Parameters"]
        email = parameters["AlertEmail"]
        self.assertEqual(email["Type"], "String")
        self.assertNotIn("Default", email)
        pattern = re.compile(email["AllowedPattern"])
        self.assertTrue(pattern.fullmatch(EMAIL))
        self.assertFalse(pattern.fullmatch("not-an-email"))
        self.assertEqual(parameters["ApiDomain"]["Default"], "api.scenetrip.io")
        self.assertEqual(parameters["MonthlyBudgetUsd"]["Type"], "Number")
        self.assertEqual(parameters["MonthlyBudgetUsd"]["Default"], 900)

    def test_resources_are_exactly_the_four(self):
        self.assertEqual(
            {logical: r["Type"] for logical, r in self.resources.items()},
            EXPECTED_RESOURCES,
        )

    def test_topic_subscribes_the_parameter_email(self):
        props = self.props("AlertTopic")
        self.assertEqual(props["TopicName"], "scenetrip-alerts")
        (subscription,) = props["Subscription"]
        self.assertEqual(subscription["Protocol"], "email")
        self.assertEqual(subscription["Endpoint"], {"Ref": "AlertEmail"})

    def test_health_check_calls_prd_api_over_https(self):
        config = self.props("ApiHealthCheck")["HealthCheckConfig"]
        self.assertEqual(config["Type"], "HTTPS")
        self.assertEqual(config["FullyQualifiedDomainName"], {"Ref": "ApiDomain"})
        self.assertEqual(config["Port"], 443)
        self.assertEqual(config["ResourcePath"], "/v1/search/suggestions?q=a")
        self.assertIs(config["EnableSNI"], True)
        self.assertEqual(config["RequestInterval"], 30)
        self.assertEqual(config["FailureThreshold"], 3)
        self.assertEqual(len(config["Regions"]), 3)
        self.assertEqual(len(set(config["Regions"])), 3)

    def test_down_alarm(self):
        props = self.props("ApiDownAlarm")
        self.assertEqual(props["AlarmName"], "scenetrip-prd-api-down")
        self.assertEqual(props["Namespace"], "AWS/Route53")
        self.assertEqual(props["MetricName"], "HealthCheckStatus")
        self.assertEqual(
            props["Dimensions"],
            [{"Name": "HealthCheckId", "Value": {"Ref": "ApiHealthCheck"}}],
        )
        self.assertEqual(props["Statistic"], "Minimum")
        self.assertEqual(props["Period"], 60)
        self.assertEqual(props["EvaluationPeriods"], 2)
        self.assertEqual(props["Threshold"], 1)
        self.assertEqual(props["ComparisonOperator"], "LessThanThreshold")
        self.assertEqual(props["TreatMissingData"], "breaching")
        self.assertEqual(props["AlarmActions"], [{"Ref": "AlertTopic"}])
        self.assertEqual(props["OKActions"], [{"Ref": "AlertTopic"}])

    def test_monthly_budget(self):
        props = self.props("MonthlyBudget")
        budget = props["Budget"]
        self.assertEqual(budget["BudgetName"], "scenetrip-monthly")
        self.assertEqual(budget["BudgetType"], "COST")
        self.assertEqual(budget["TimeUnit"], "MONTHLY")
        self.assertEqual(
            budget["BudgetLimit"],
            {"Amount": {"Ref": "MonthlyBudgetUsd"}, "Unit": "USD"},
        )
        notifications = props["NotificationsWithSubscribers"]
        self.assertEqual(
            sorted(n["Notification"]["NotificationType"] for n in notifications),
            ["ACTUAL", "FORECASTED"],
        )
        for entry in notifications:
            notification = entry["Notification"]
            self.assertEqual(notification["ComparisonOperator"], "GREATER_THAN")
            self.assertEqual(notification["Threshold"], 100)
            self.assertEqual(notification["ThresholdType"], "PERCENTAGE")
            self.assertEqual(
                entry["Subscribers"],
                [{"SubscriptionType": "EMAIL", "Address": {"Ref": "AlertEmail"}}],
            )

    def test_no_email_literal_in_template(self):
        self.assertEqual(EMAIL_LITERAL.findall(self.text), [])


class AlertsFlowTest(unittest.TestCase):
    def assert_all_in_region(self, run):
        for command in run.calls:
            self.assertEqual(flag(command, "--region"), "us-east-1", command)

    def test_status_is_read_from_alerts_stack(self):
        run = FakeCloudFormation(status="UPDATE_COMPLETE")
        invoke(run, execute=False)
        (describe,) = run.commands("describe-stacks")[:1]
        self.assertEqual(
            describe,
            [
                "aws",
                "cloudformation",
                "describe-stacks",
                "--stack-name",
                "scenetrip-alerts",
                "--region",
                "us-east-1",
                "--output",
                "json",
            ],
        )

    def test_preview_without_stack_lists_template_and_creates_nothing(self):
        run = FakeCloudFormation(status=None)
        out, _ = invoke(run, execute=False)
        self.assertEqual(run.operations(), ["describe-stacks"])
        for logical, kind in EXPECTED_RESOURCES.items():
            self.assertRegex(out, rf"Add\s+{logical}\s+{re.escape(kind)}")
        self.assert_all_in_region(run)

    def test_bad_status_raises_before_any_change_set(self):
        for status in ("ROLLBACK_COMPLETE", "CREATE_FAILED", "UPDATE_IN_PROGRESS"):
            for execute in (False, True):
                with self.subTest(status=status, execute=execute):
                    run = FakeCloudFormation(status=status)
                    with self.assertRaises(RuntimeError):
                        invoke(run, execute=execute)
                    self.assertEqual(run.commands("create-change-set"), [])
                    self.assertEqual(run.commands("execute-change-set"), [])

    def test_good_statuses_proceed_to_update_change_set(self):
        for status in (
            "CREATE_COMPLETE",
            "UPDATE_COMPLETE",
            "UPDATE_ROLLBACK_COMPLETE",
        ):
            with self.subTest(status=status):
                run = FakeCloudFormation(status=status)
                invoke(run, execute=False)
                (create,) = run.commands("create-change-set")
                self.assertEqual(flag(create, "--change-set-type"), "UPDATE")

    def check_create_change_set(self, run, temp, change_type):
        (create,) = run.commands("create-change-set")
        self.assertEqual(flag(create, "--stack-name"), "scenetrip-alerts")
        self.assertEqual(flag(create, "--change-set-name"), "scenetrip-alerts-change")
        self.assertEqual(flag(create, "--change-set-type"), change_type)
        self.assertEqual(
            flag(create, "--template-body"), f"file://{temp / 'alerts.json'}"
        )
        self.assertEqual(
            json.loads(flag(create, "--parameters")),
            [{"ParameterKey": "AlertEmail", "ParameterValue": EMAIL}],
        )

    def test_preview_with_stack_describes_then_deletes_change_set(self):
        changes = [
            {
                "ResourceChange": {
                    "Action": "Modify",
                    "LogicalResourceId": "ApiDownAlarm",
                    "ResourceType": "AWS::CloudWatch::Alarm",
                }
            }
        ]
        run = FakeCloudFormation(status="UPDATE_COMPLETE", changes=changes)
        out, temp = invoke(run, execute=False)
        self.check_create_change_set(run, temp, "UPDATE")
        self.assertEqual(
            run.operations()[1:],
            [
                "create-change-set",
                "wait change-set-create-complete",
                "describe-change-set",
                "delete-change-set",
            ],
        )
        self.assertRegex(out, r"Modify\s+ApiDownAlarm\s+AWS::CloudWatch::Alarm")
        self.assertEqual(run.commands("execute-change-set"), [])
        for operation in ("describe-change-set", "delete-change-set"):
            (command,) = run.commands(operation)
            self.assertEqual(flag(command, "--stack-name"), "scenetrip-alerts")
            self.assertEqual(
                flag(command, "--change-set-name"), "scenetrip-alerts-change"
            )
        self.assert_all_in_region(run)

    def test_execute_without_stack_creates_and_waits_for_create(self):
        run = FakeCloudFormation(status=None)
        _, temp = invoke(run, execute=True)
        self.check_create_change_set(run, temp, "CREATE")
        operations = run.operations()
        self.assertIn("execute-change-set", operations)
        self.assertIn("wait stack-create-complete", operations)
        self.assertNotIn("wait stack-update-complete", operations)
        self.assertNotIn("delete-change-set", operations)
        self.assertLess(
            operations.index("wait change-set-create-complete"),
            operations.index("execute-change-set"),
        )
        self.assertLess(
            operations.index("execute-change-set"),
            operations.index("wait stack-create-complete"),
        )
        self.assert_all_in_region(run)

    def test_execute_with_stack_waits_for_update(self):
        run = FakeCloudFormation(status="UPDATE_COMPLETE")
        _, temp = invoke(run, execute=True)
        self.check_create_change_set(run, temp, "UPDATE")
        operations = run.operations()
        self.assertIn("execute-change-set", operations)
        self.assertIn("wait stack-update-complete", operations)
        self.assertNotIn("wait stack-create-complete", operations)
        (wait,) = [c for c in run.calls if c[2:4] == ["wait", "stack-update-complete"]]
        self.assertEqual(flag(wait, "--stack-name"), "scenetrip-alerts")
        self.assert_all_in_region(run)

    def test_template_body_is_the_repository_template(self):
        seen = {}

        class Capturing(FakeCloudFormation):
            def __call__(self, command, **kwargs):
                if command[2] == "create-change-set":
                    body = flag(command, "--template-body").removeprefix("file://")
                    seen["template"] = json.loads(Path(body).read_text())
                return super().__call__(command, **kwargs)

        invoke(Capturing(status="UPDATE_COMPLETE"), execute=False)
        self.assertEqual(seen["template"], json.loads(template_text()))

    def test_failed_wait_prints_failed_events_and_reraises(self):
        events = [
            {
                "LogicalResourceId": "MonthlyBudget",
                "ResourceStatus": "CREATE_FAILED",
                "ResourceStatusReason": "denied by SCP",
            },
            {
                "LogicalResourceId": "AlertTopic",
                "ResourceStatus": "CREATE_COMPLETE",
                "ResourceStatusReason": "fine-topic",
            },
            {
                "LogicalResourceId": "scenetrip-alerts",
                "ResourceStatus": "ROLLBACK_IN_PROGRESS",
                "ResourceStatusReason": "rolling-back",
            },
        ]
        run = FakeCloudFormation(
            status=None, fail_wait="stack-create-complete", events=events
        )
        with self.assertRaises(RuntimeError) as raised:
            invoke(run, execute=True)
        out = raised.exception.stdout
        self.assertIn("MonthlyBudget", out)
        self.assertIn("denied by SCP", out)
        self.assertNotIn("fine-topic", out)
        self.assertNotIn("rolling-back", out)
        (events_call,) = run.commands("describe-stack-events")
        self.assertEqual(flag(events_call, "--stack-name"), "scenetrip-alerts")
        self.assert_all_in_region(run)

    def test_email_reaches_only_parameters(self):
        run = FakeCloudFormation(status="UPDATE_COMPLETE")
        invoke(run, execute=True)
        for command in run.calls:
            if command[2] == "create-change-set":
                continue
            self.assertNotIn(EMAIL, " ".join(command))


class AlertsCliTest(unittest.TestCase):
    def call_main(self, argv):
        sentinel_root = Path("/nonexistent-root")
        sentinel_run = object()
        with (
            patch.object(ops, "workspace", return_value=sentinel_root),
            patch.object(ops, "runner", return_value=sentinel_run),
            patch.object(ops, "alerts") as fake,
        ):
            ops.main(argv)
        return fake, sentinel_root, sentinel_run

    def test_preview_by_default(self):
        fake, root, run = self.call_main(["alerts", "--email", EMAIL])
        fake.assert_called_once()
        args, kwargs = fake.call_args
        self.assertIs(args[0], run)
        self.assertEqual(args[1], root)
        self.assertIsInstance(args[2], Path)
        self.assertEqual(kwargs, {"email": EMAIL, "execute": False})

    def test_execute_flag(self):
        fake, _, _ = self.call_main(["alerts", "--email", EMAIL, "--execute"])
        self.assertEqual(fake.call_args.kwargs, {"email": EMAIL, "execute": True})

    def test_email_is_required(self):
        with (
            contextlib.redirect_stderr(io.StringIO()),
            self.assertRaises(SystemExit) as raised,
        ):
            self.call_main(["alerts"])
        self.assertNotEqual(raised.exception.code, 0)


class AlertsRecipeTest(unittest.TestCase):
    def test_recipe_forwards_flags_to_ops_alerts(self):
        path = workspace_root() / "tools/just/infra.just"
        if not path.exists():
            self.skipTest(
                "tools/just/infra.just 는 이 시험의 runfiles 에 없다 — tools/just 에 BUILD 대상이 없어 data 로 걸 수 없다"
            )
        text = path.read_text()
        match = re.search(
            r"\[positional-arguments\]\s*\naws-alerts \*flags:\n(\s+.*)",
            text,
            re.DOTALL,
        )
        self.assertIsNotNone(match)
        self.assertIn('//tools/aws:ops -- alerts "$@"', match.group(1).splitlines()[0])


if __name__ == "__main__":
    unittest.main()
