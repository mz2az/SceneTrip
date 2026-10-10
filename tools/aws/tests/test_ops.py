"""운영 보호(ops.py) 명세 검사 — docs/project/plans/ops-protection.md, docs/ops/aws-deployment.md §13·§14.

AWS 를 부르지 않는다 — 실행기를 가짜로 바꿔 어떤 명령이 나갔는지만 본다.
"""

import contextlib
import io
import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.aws import ops
from tools.aws.destroy import prune_after_destroy
from tools.aws.tests.fixtures import valid_settings


def quietly(function, *args, **kwargs):
    with contextlib.redirect_stdout(io.StringIO()):
        return function(*args, **kwargs)


class FakeAws:
    """`(service, operation)` 별 응답을 돌려주는 가짜 실행기. 응답이 예외면 그것을 던진다."""

    def __init__(self, responses=None):
        self.calls = []
        self.responses = dict(responses or {})

    def __call__(self, command, **kwargs):
        self.calls.append(list(command))
        assert command[0] == "aws", command
        response = self.responses.get(tuple(command[1:3]), {})
        if isinstance(response, Exception):
            raise response
        if callable(response):
            response = response(command)
        return response if isinstance(response, str) else json.dumps(response)

    def operations(self):
        return [tuple(c[1:3]) for c in self.calls]

    def commands(self, service, operation):
        return [c for c in self.calls if tuple(c[1:3]) == (service, operation)]


def snapshot(identifier, created, status="available"):
    return {
        "DBSnapshotIdentifier": identifier,
        "SnapshotCreateTime": created,
        "Status": status,
    }


SNAPSHOTS = [
    snapshot("scenetrip-dev-final-0930", "2026-09-30T01:00:00Z"),
    snapshot("scenetrip-dev-final-1009", "2026-10-09T01:00:00Z"),
    snapshot("scenetrip-dev-final-0929", "2026-09-29T01:00:00Z"),
    snapshot("scenetrip-dev-final-1001", "2026-10-01T01:00:00Z"),
    # 이름·상태가 다른 것은 건드리지 않는다
    snapshot("scenetrip-prd-final-0901", "2026-09-01T01:00:00Z"),
    snapshot("scenetrip-dev-manual-0801", "2026-08-01T01:00:00Z"),
    snapshot("other-dev-final-0801", "2026-08-01T01:00:00Z"),
    snapshot("scenetrip-dev-final-0901", "2026-09-01T01:00:00Z", status="creating"),
]


def snapshot_runner(snapshots=SNAPSHOTS):
    return FakeAws({("rds", "describe-db-snapshots"): {"DBSnapshots": snapshots}})


def deleted(run):
    return [
        c[c.index("--db-snapshot-identifier") + 1]
        for c in run.commands("rds", "delete-db-snapshot")
    ]


class SnapshotPruneTest(unittest.TestCase):
    def test_preview_deletes_nothing(self):
        run = snapshot_runner()
        doomed = quietly(ops.prune_snapshots, run, "dev", 2, execute=False)
        self.assertEqual(
            set(doomed), {"scenetrip-dev-final-0930", "scenetrip-dev-final-0929"}
        )
        self.assertEqual(deleted(run), [])
        self.assertNotIn(("rds", "delete-db-snapshot"), run.operations())

    def test_execute_deletes_exactly_the_older_ones(self):
        run = snapshot_runner()
        quietly(ops.prune_snapshots, run, "dev", 2, execute=True)
        self.assertEqual(
            sorted(deleted(run)),
            ["scenetrip-dev-final-0929", "scenetrip-dev-final-0930"],
        )

    def test_keep_is_configurable(self):
        run = snapshot_runner()
        quietly(ops.prune_snapshots, run, "dev", 3, execute=True)
        self.assertEqual(deleted(run), ["scenetrip-dev-final-0929"])
        run = snapshot_runner()
        quietly(ops.prune_snapshots, run, "dev", 10, execute=True)
        self.assertEqual(deleted(run), [])

    def test_other_names_and_statuses_are_ignored(self):
        run = snapshot_runner()
        quietly(ops.prune_snapshots, run, "dev", 1, execute=True)
        gone = set(deleted(run))
        self.assertEqual(
            gone,
            {
                "scenetrip-dev-final-1001",
                "scenetrip-dev-final-0930",
                "scenetrip-dev-final-0929",
            },
        )
        for untouched in (
            "scenetrip-dev-final-1009",
            "scenetrip-prd-final-0901",
            "scenetrip-dev-manual-0801",
            "other-dev-final-0801",
            "scenetrip-dev-final-0901",
        ):
            self.assertNotIn(untouched, gone)

    def test_prd_is_refused_without_any_call(self):
        run = snapshot_runner(
            [snapshot(f"scenetrip-prd-final-{i}", f"2026-09-0{i}") for i in range(1, 5)]
        )
        for execute in (False, True):
            with self.assertRaises(ValueError):
                quietly(ops.prune_snapshots, run, "prd", 2, execute=execute)
        self.assertEqual(run.calls, [])

    def test_keep_below_one_is_refused(self):
        for keep in (0, -1):
            run = snapshot_runner()
            with self.subTest(keep=keep), self.assertRaises(ValueError):
                quietly(ops.prune_snapshots, run, "dev", keep, execute=True)
            self.assertEqual(deleted(run), [])

    def test_malformed_listing_fails_closed(self):
        run = FakeAws({("rds", "describe-db-snapshots"): {"DBSnapshots": "x"}})
        with self.assertRaises(TypeError):
            quietly(ops.prune_snapshots, run, "dev", 2, execute=True)
        self.assertEqual(deleted(run), [])


class PruneAfterDestroyTest(unittest.TestCase):
    prd = valid_settings(
        environment="prd", role="arn:aws:iam::123456789012:role/scenetrip-prd-deploy"
    )

    def test_dev_retain_prunes_keeping_two(self):
        run = snapshot_runner()
        quietly(prune_after_destroy, run, valid_settings(), "retain")
        self.assertEqual(
            sorted(deleted(run)),
            ["scenetrip-dev-final-0929", "scenetrip-dev-final-0930"],
        )

    def test_other_combinations_do_nothing(self):
        for settings, policy in [
            (valid_settings(), "delete"),
            (valid_settings(), "skip"),
            (self.prd, "retain"),
            (self.prd, "delete"),
        ]:
            with self.subTest(environment=settings.environment, policy=policy):
                run = snapshot_runner()
                quietly(prune_after_destroy, run, settings, policy)
                self.assertEqual(run.calls, [])

    def test_failure_does_not_raise(self):
        run = FakeAws({("rds", "describe-db-snapshots"): RuntimeError("권한 없음")})
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            prune_after_destroy(run, valid_settings(), "retain")
        self.assertIn("aws-snapshot-prune", output.getvalue())

    def test_delete_failure_does_not_raise(self):
        run = snapshot_runner()
        run.responses[("rds", "delete-db-snapshot")] = RuntimeError("권한 없음")
        quietly(prune_after_destroy, run, valid_settings(), "retain")


ACCOUNT = "123456789012"
BUCKET = f"scenetrip-tfstate-{ACCOUNT}-ap-northeast-2-dev"
LOCK = "scenetrip/dev/terraform.tfstate.tflock"


def drift_runner(*, state=None, lock=False, clusters=(), dbs=(), nats=(), vpcs=()):
    state_response = (
        RuntimeError("NoSuchKey")
        if state is None
        else json.dumps(
            {
                "resources": [
                    {"mode": mode, "type": kind, "name": name}
                    for mode, kind, name in state
                ]
            }
        )
    )
    return FakeAws(
        {
            ("sts", "get-caller-identity"): {"Account": ACCOUNT},
            ("s3", "cp"): state_response,
            ("s3api", "list-objects-v2"): {"Contents": [{"Key": LOCK}]} if lock else {},
            ("eks", "list-clusters"): {"clusters": list(clusters)},
            ("rds", "describe-db-instances"): {
                "DBInstances": [{"DBInstanceIdentifier": d} for d in dbs]
            },
            ("ec2", "describe-nat-gateways"): {"NatGateways": list(nats)},
            ("ec2", "describe-vpcs"): {"Vpcs": list(vpcs)},
        }
    )


FULL_STATE = [
    ("managed", "aws_eks_cluster", "this"),
    ("managed", "aws_db_instance", "postgres"),
    ("managed", "aws_nat_gateway", "this"),
    ("managed", "aws_vpc", "this"),
]
NAT = {"NatGatewayId": "nat-1", "State": "available"}
VPC = {"VpcId": "vpc-1"}


class DriftTest(unittest.TestCase):
    def test_clean_when_nothing_exists(self):
        run = drift_runner()
        self.assertEqual(quietly(ops.drift, run, "dev"), [])

    def test_clean_when_everything_is_in_state(self):
        run = drift_runner(
            state=FULL_STATE,
            clusters=["scenetrip-dev"],
            dbs=["scenetrip-dev"],
            nats=[NAT],
            vpcs=[VPC],
        )
        self.assertEqual(quietly(ops.drift, run, "dev"), [])

    def test_leftover_lock_is_reported(self):
        findings = quietly(ops.drift, drift_runner(state=FULL_STATE, lock=True), "dev")
        self.assertEqual(len(findings), 1)
        self.assertIn("잠금", findings[0])

    def test_lock_lookup_targets_the_environment_state_bucket(self):
        run = drift_runner()
        quietly(ops.drift, run, "dev")
        (listing,) = run.commands("s3api", "list-objects-v2")
        self.assertEqual(listing[listing.index("--bucket") + 1], BUCKET)
        self.assertEqual(listing[listing.index("--prefix") + 1], LOCK)
        (copy,) = run.commands("s3", "cp")
        self.assertIn(f"s3://{BUCKET}/scenetrip/dev/terraform.tfstate", copy)

    def test_eks_and_rds_outside_state_are_reported(self):
        state = [
            r for r in FULL_STATE if r[1] not in {"aws_eks_cluster", "aws_db_instance"}
        ]
        run = drift_runner(
            state=state, clusters=["scenetrip-dev"], dbs=["scenetrip-dev"]
        )
        findings = quietly(ops.drift, run, "dev")
        self.assertEqual(len(findings), 2)
        self.assertTrue(any("EKS" in f for f in findings))
        self.assertTrue(any("RDS" in f for f in findings))

    def test_other_environment_resources_are_not_findings(self):
        run = drift_runner(clusters=["scenetrip-prd"], dbs=["scenetrip-prd"])
        self.assertEqual(quietly(ops.drift, run, "dev"), [])

    def test_tagged_nat_and_vpc_without_state_are_reported(self):
        run = drift_runner(
            nats=[NAT, {"NatGatewayId": "nat-2", "State": "pending"}], vpcs=[VPC]
        )
        findings = quietly(ops.drift, run, "dev")
        self.assertEqual(len(findings), 2)
        self.assertTrue(any("NAT" in f and "2" in f for f in findings))
        self.assertTrue(any("VPC" in f for f in findings))

    def test_deleted_nat_is_not_a_finding(self):
        run = drift_runner(nats=[{"NatGatewayId": "nat-1", "State": "deleted"}])
        self.assertEqual(quietly(ops.drift, run, "dev"), [])

    def test_unmanaged_state_entries_do_not_count(self):
        state = [("data", kind, name) for _, kind, name in FULL_STATE]
        run = drift_runner(state=state, clusters=["scenetrip-dev"], vpcs=[VPC])
        findings = quietly(ops.drift, run, "dev")
        self.assertTrue(any("EKS" in f for f in findings))
        self.assertTrue(any("VPC" in f for f in findings))

    def test_nat_uses_filter_and_vpc_uses_filters(self):
        run = drift_runner()
        quietly(ops.drift, run, "dev")
        tags = ["Name=tag:Project,Values=scenetrip", "Name=tag:Environment,Values=dev"]
        (nat,) = run.commands("ec2", "describe-nat-gateways")
        self.assertIn("--filter", nat)
        self.assertNotIn("--filters", nat)
        start = nat.index("--filter") + 1
        self.assertEqual(nat[start : start + 2], tags)
        (vpc,) = run.commands("ec2", "describe-vpcs")
        self.assertIn("--filters", vpc)
        self.assertNotIn("--filter", vpc)
        start = vpc.index("--filters") + 1
        self.assertEqual(vpc[start : start + 2], tags)

    def test_drift_is_read_only(self):
        run = drift_runner(
            lock=True,
            clusters=["scenetrip-dev"],
            dbs=["scenetrip-dev"],
            nats=[NAT],
            vpcs=[VPC],
        )
        quietly(ops.drift, run, "dev")
        read_prefixes = ("describe-", "list-", "get-")
        for service, operation in run.operations():
            with self.subTest(operation=operation):
                self.assertTrue(
                    operation.startswith(read_prefixes)
                    or (service, operation) == ("s3", "cp"),
                    operation,
                )
        (copy,) = run.commands("s3", "cp")
        self.assertEqual(
            copy[copy.index("cp") + 2], "-"
        )  # 로컬 파일이 아니라 표준 출력으로


def workspace_root():
    return Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]


def desired_template():
    return json.loads((workspace_root() / ops.SHARED_MEDIA_TEMPLATE).read_text())


def change_set_runner(*, status="UPDATE_COMPLETE", replacement="False"):
    stacks = (
        RuntimeError("Stack with id scenetrip-shared-media does not exist")
        if status is None
        else {"Stacks": [{"StackName": ops.SHARED_MEDIA_STACK, "StackStatus": status}]}
    )
    return FakeAws(
        {
            ("cloudformation", "describe-stacks"): stacks,
            ("cloudformation", "describe-change-set"): {
                "Changes": [
                    {
                        "ResourceChange": {
                            "Action": "Modify",
                            "LogicalResourceId": "SharedMediaBucket",
                            "ResourceType": "AWS::S3::Bucket",
                            "Replacement": replacement,
                        }
                    }
                ]
            },
        }
    )


class SharedMediaTemplateTest(unittest.TestCase):
    def setUp(self):
        self.desired = desired_template()
        self.bucket = self.desired["Resources"]["SharedMediaBucket"]

    def test_bucket_is_the_existing_one_and_retained(self):
        self.assertEqual(ops.SHARED_MEDIA_BUCKET, "scenetrip-media-prod")
        self.assertEqual(ops.SHARED_MEDIA_STACK, "scenetrip-shared-media")
        self.assertEqual(self.bucket["Type"], "AWS::S3::Bucket")
        self.assertEqual(
            self.bucket["Properties"]["BucketName"], ops.SHARED_MEDIA_BUCKET
        )
        self.assertEqual(self.bucket["DeletionPolicy"], "Retain")
        self.assertEqual(self.bucket["UpdateReplacePolicy"], "Retain")

    def test_desired_enables_versioning_and_expires_noncurrent_after_30_days(self):
        props = self.bucket["Properties"]
        self.assertEqual(props["VersioningConfiguration"], {"Status": "Enabled"})
        rule = next(
            r
            for r in props["LifecycleConfiguration"]["Rules"]
            if r.get("Id") == "ExpireNoncurrentVersions"
        )
        self.assertEqual(rule["Status"], "Enabled")
        self.assertEqual(rule["NoncurrentVersionExpiration"], {"NoncurrentDays": 30})
        self.assertEqual(rule.get("Prefix", ""), "")
        # 현재 판을 지우는 규칙은 없다 — 시드가 읽는 사진이 사라지면 안 된다
        for r in props["LifecycleConfiguration"]["Rules"]:
            self.assertNotIn("ExpirationInDays", r)
            self.assertNotIn("ExpirationDate", r)

    def test_public_read_policy_is_kept(self):
        policy = self.desired["Resources"]["SharedMediaBucketPolicy"]
        self.assertEqual(policy["Type"], "AWS::S3::BucketPolicy")
        self.assertEqual(policy["Properties"]["Bucket"], {"Ref": "SharedMediaBucket"})
        (statement,) = policy["Properties"]["PolicyDocument"]["Statement"]
        self.assertEqual(statement["Sid"], "PublicReadForImages")
        self.assertEqual(statement["Effect"], "Allow")
        self.assertEqual(statement["Principal"], "*")
        self.assertEqual(statement["Action"], "s3:GetObject")
        self.assertEqual(
            statement["Resource"],
            {"Fn::Sub": "arn:${AWS::Partition}:s3:::${SharedMediaBucket}/*"},
        )
        block = self.bucket["Properties"]["PublicAccessBlockConfiguration"]
        self.assertFalse(block["BlockPublicPolicy"])
        self.assertFalse(block["RestrictPublicBuckets"])

    def test_import_template_is_desired_minus_versioning_rule_and_policy(self):
        imported = ops.import_template(self.desired)
        expected = json.loads(json.dumps(self.desired))
        del expected["Resources"]["SharedMediaBucketPolicy"]
        props = expected["Resources"]["SharedMediaBucket"]["Properties"]
        del props["VersioningConfiguration"]
        props["LifecycleConfiguration"]["Rules"] = [
            r
            for r in props["LifecycleConfiguration"]["Rules"]
            if r.get("Id") != "ExpireNoncurrentVersions"
        ]
        # 가져오기 템플릿의 출력은 명세가 정하지 않는다 — 비교에서 뺀다
        imported.pop("Outputs", None)
        expected.pop("Outputs", None)
        self.assertEqual(imported, expected)
        bucket = imported["Resources"]["SharedMediaBucket"]
        self.assertEqual(bucket["DeletionPolicy"], "Retain")
        self.assertEqual(bucket["UpdateReplacePolicy"], "Retain")

    def test_import_template_does_not_mutate_desired(self):
        before = json.dumps(self.desired, sort_keys=True)
        ops.import_template(self.desired)
        self.assertEqual(json.dumps(self.desired, sort_keys=True), before)


class SharedMediaFlowTest(unittest.TestCase):
    def invoke(self, run, *, execute):
        with tempfile.TemporaryDirectory() as temporary:
            quietly(
                ops.shared_media,
                run,
                workspace_root(),
                Path(temporary),
                execute=execute,
            )

    def test_preview_without_stack_creates_nothing(self):
        run = change_set_runner(status=None)
        self.invoke(run, execute=False)
        # 스택이 있는지 보는 읽기 하나뿐 — 가져오기 변경 세트는 만들기만 해도 빈 스택을 남긴다
        self.assertEqual(run.operations(), [("cloudformation", "describe-stacks")])

    def test_execute_without_stack_imports_then_applies_desired(self):
        run = change_set_runner(status=None)
        bodies = []

        def capture(command):
            path = command[command.index("--template-body") + 1].removeprefix("file://")
            bodies.append(
                (
                    command[command.index("--change-set-type") + 1],
                    json.loads(Path(path).read_text()),
                )
            )
            return {}

        run.responses[("cloudformation", "create-change-set")] = capture
        self.invoke(run, execute=True)
        desired = desired_template()
        self.assertEqual([kind for kind, _ in bodies], ["IMPORT", "UPDATE"])
        self.assertEqual(bodies[0][1], ops.import_template(desired))
        self.assertEqual(bodies[1][1], desired)
        (create_import, _) = run.commands("cloudformation", "create-change-set")
        to_import = json.loads(
            create_import[create_import.index("--resources-to-import") + 1]
        )
        self.assertEqual(
            to_import,
            [
                {
                    "ResourceType": "AWS::S3::Bucket",
                    "LogicalResourceId": "SharedMediaBucket",
                    "ResourceIdentifier": {"BucketName": "scenetrip-media-prod"},
                }
            ],
        )
        self.assertEqual(len(run.commands("cloudformation", "execute-change-set")), 2)

    def test_preview_with_stack_deletes_its_change_set(self):
        run = change_set_runner()
        self.invoke(run, execute=False)
        operations = run.operations()
        self.assertIn(("cloudformation", "create-change-set"), operations)
        self.assertIn(("cloudformation", "delete-change-set"), operations)
        self.assertNotIn(("cloudformation", "execute-change-set"), operations)

    def test_replacement_aborts_before_execution(self):
        run = change_set_runner(replacement="True")
        with self.assertRaises(RuntimeError):
            self.invoke(run, execute=True)
        operations = run.operations()
        self.assertNotIn(("cloudformation", "execute-change-set"), operations)
        self.assertIn(("cloudformation", "delete-change-set"), operations)

    def test_unexpected_stack_status_stops(self):
        run = change_set_runner(status="UPDATE_ROLLBACK_FAILED")
        with self.assertRaises(RuntimeError):
            self.invoke(run, execute=True)
        self.assertNotIn(("cloudformation", "create-change-set"), run.operations())


class RunnerSelectionTest(unittest.TestCase):
    root = Path("/workspace")

    def select(self, system, machine, which="/usr/local/bin/aws"):
        with (
            patch.object(ops.sys, "platform", system),
            patch.object(ops.platform, "machine", return_value=machine),
            patch.object(ops.shutil, "which", return_value=which),
        ):
            return ops.runner(self.root)

    def test_linux_amd64_uses_bazel_runner(self):
        for machine in ("x86_64", "AMD64"):
            with self.subTest(machine=machine):
                selected = self.select("linux", machine, which=None)
                self.assertIsInstance(selected, ops.Runner)
                self.assertNotIsInstance(selected, ops.OperatorRunner)

    def test_other_hosts_use_host_aws(self):
        for system, machine in [
            ("darwin", "arm64"),
            ("darwin", "x86_64"),
            ("linux", "aarch64"),
        ]:
            with self.subTest(system=system, machine=machine):
                selected = self.select(system, machine)
                self.assertIsInstance(selected, ops.OperatorRunner)
                self.assertEqual(selected.aws, "/usr/local/bin/aws")

    def test_missing_host_aws_is_an_error(self):
        with self.assertRaises(RuntimeError):
            self.select("darwin", "arm64", which=None)

    def test_operator_runner_substitutes_host_aws(self):
        with patch.object(ops.shutil, "which", return_value="/opt/bin/aws"):
            runner = ops.OperatorRunner(self.root)
        completed = ops.subprocess.CompletedProcess([], 0, stdout="{}", stderr="")
        with patch.object(ops.subprocess, "run", return_value=completed) as run:
            self.assertEqual(
                runner(["aws", "sts", "get-caller-identity"], quiet=True), "{}"
            )
        self.assertEqual(
            run.call_args.args[0], ["/opt/bin/aws", "sts", "get-caller-identity"]
        )
        failed = ops.subprocess.CompletedProcess([], 255, stdout="", stderr="denied")
        with (
            patch.object(ops.subprocess, "run", return_value=failed),
            self.assertRaises(RuntimeError),
        ):
            runner(["aws", "sts", "get-caller-identity"], quiet=True)


if __name__ == "__main__":
    unittest.main()
