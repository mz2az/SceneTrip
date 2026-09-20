"""bootstrap 삭제 순서와 조회 실패 시 중단 경계."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from tools.aws.bootstrap_delete import delete_bootstrap
from tools.aws.tests.fixtures import valid_settings


class BootstrapRunner:
    def __init__(self):
        self.calls = []
        self.settings = valid_settings()
        self.bucket = "scenetrip-tfstate-123456789012-ap-northeast-2-dev"
        self.key = "scenetrip/dev/terraform.tfstate"
        self.stack_id = "arn:aws:cloudformation:ap-northeast-2:123456789012:stack/scenetrip-dev-bootstrap/abc-123"
        self.state = {"version": 4, "serial": 1, "lineage": "fixture", "resources": []}
        self.versions = [{"Key": self.key, "VersionId": "current", "IsLatest": True}]
        self.markers = []
        self.stack_exists = True
        self.bucket_exists = True
        self.fail = None
        self.overrides = {}

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        operation = tuple(command[1:3])
        if operation == self.fail:
            raise RuntimeError("조회 실패")
        if operation in self.overrides:
            return json.dumps(self.overrides[operation])
        name = "scenetrip-dev-bootstrap"
        resources = [
            ("TerraformStateBucket", "AWS::S3::Bucket", self.bucket),
            ("TerraformStateBucketPolicy", "AWS::S3::BucketPolicy", self.bucket),
            ("ClusterRole", "AWS::IAM::Role", "scenetrip-dev-eks-cluster"),
            ("NodeRole", "AWS::IAM::Role", "scenetrip-dev-eks-node"),
            ("DeploymentRole", "AWS::IAM::Role", "scenetrip-dev-deploy"),
        ]
        outputs = {
            "TerraformStateBucket": self.bucket,
            "TerraformStateKey": self.key,
            "DeploymentRoleArn": "arn:aws:iam::123456789012:role/scenetrip-dev-deploy",
            "ClusterRoleArn": "arn:aws:iam::123456789012:role/scenetrip-dev-eks-cluster",
            "NodeRoleArn": "arn:aws:iam::123456789012:role/scenetrip-dev-eks-node",
        }
        responses = {
            ("cloudformation", "list-stacks"): {
                "StackSummaries": [
                    {
                        "StackName": name,
                        "StackId": self.stack_id,
                        "StackStatus": "CREATE_COMPLETE",
                    }
                ]
                if self.stack_exists
                else []
            },
            ("cloudformation", "describe-stacks"): {
                "Stacks": [
                    {
                        "StackName": name,
                        "StackId": self.stack_id,
                        "StackStatus": "CREATE_COMPLETE",
                        "Parameters": [
                            {"ParameterKey": "Environment", "ParameterValue": "dev"},
                            {
                                "ParameterKey": "GitHubRepository",
                                "ParameterValue": "mz2az/SceneTrip",
                            },
                            {
                                "ParameterKey": "GitHubOidcProviderArn",
                                "ParameterValue": "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com",
                            },
                        ],
                        "Outputs": [
                            {"OutputKey": key, "OutputValue": value}
                            for key, value in outputs.items()
                        ],
                    }
                ]
            },
            ("cloudformation", "list-stack-resources"): {
                "StackResourceSummaries": [
                    {
                        "LogicalResourceId": logical,
                        "ResourceType": kind,
                        "PhysicalResourceId": physical,
                    }
                    for logical, kind, physical in resources
                ]
            },
            ("cloudformation", "get-template"): {
                "TemplateBody": {
                    "Resources": {"TerraformStateBucket": {"DeletionPolicy": "Retain"}}
                }
            },
            ("s3api", "list-buckets"): {
                "Buckets": [{"Name": self.bucket}] if self.bucket_exists else []
            },
            ("s3api", "get-bucket-location"): {"LocationConstraint": "ap-northeast-2"},
            ("s3api", "get-bucket-tagging"): {
                "TagSet": [
                    {"Key": "Project", "Value": "scenetrip"},
                    {"Key": "Environment", "Value": "dev"},
                    {"Key": "ManagedBy", "Value": "cloudformation"},
                    {"Key": "aws:cloudformation:stack-id", "Value": self.stack_id},
                ]
            },
            ("s3api", "list-object-versions"): {
                "Versions": self.versions,
                "DeleteMarkers": self.markers,
                "IsTruncated": False,
            },
            ("s3api", "list-multipart-uploads"): {"Uploads": [], "IsTruncated": False},
            ("eks", "list-clusters"): {"clusters": []},
            ("rds", "describe-db-instances"): {"DBInstances": []},
            ("ec2", "describe-vpcs"): {"Vpcs": []},
            ("ecr", "describe-repositories"): {"repositories": []},
            ("secretsmanager", "list-secrets"): {"SecretList": []},
        }
        if operation == ("s3api", "get-object"):
            target = Path(command[-1])
            assert target.stat().st_mode & 0o777 == 0o600
            target.write_text(json.dumps(self.state))
            return "{}"
        if operation == ("s3api", "delete-objects"):
            deleted = json.loads(kwargs["stdin"])["Delete"]["Objects"]
            keys = {(item["Key"], item["VersionId"]) for item in deleted}
            self.versions = [
                item
                for item in self.versions
                if (item["Key"], item["VersionId"]) not in keys
            ]
            self.markers = [
                item
                for item in self.markers
                if (item["Key"], item["VersionId"]) not in keys
            ]
        return json.dumps(responses.get(operation, {}))


class BootstrapDeleteTest(unittest.TestCase):
    def invoke(self, run, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            delete_bootstrap(run, run.settings, Path(directory), **kwargs)

    def test_plan_is_read_only(self):
        run = BootstrapRunner()
        self.invoke(run, purge_state=True)
        self.assertFalse(
            any(command[2].startswith(("delete", "abort")) for command, _ in run.calls)
        )
        self.assertTrue(all(kwargs.get("quiet") for _, kwargs in run.calls))

    def test_execute_removes_stack_before_bucket_and_preserves_state_by_default(self):
        run = BootstrapRunner()
        self.invoke(run, execute=True)
        operations = [command[2] for command, _ in run.calls]
        self.assertIn("delete-stack", operations)
        self.assertIn("wait", operations)
        self.assertNotIn("delete-bucket", operations)

    def test_purge_deletes_versions_and_bucket_after_stack_wait(self):
        run = BootstrapRunner()
        self.invoke(run, execute=True, purge_state=True)
        operations = [command[2] for command, _ in run.calls]
        self.assertLess(operations.index("wait"), operations.index("delete-objects"))
        self.assertLess(
            operations.index("delete-objects"), operations.index("delete-bucket")
        )
        for command, _ in run.calls:
            if command[1] == "s3api" and command[2] != "list-buckets":
                self.assertIn("--expected-bucket-owner", command)

    def test_managed_resource_blocks_stack_deletion(self):
        run = BootstrapRunner()
        run.state = {
            **run.state,
            "resources": [
                {
                    "mode": "managed",
                    "instances": [{"attributes": {"password": "sentinel"}}],
                }
            ],
        }
        with self.assertRaisesRegex(ValueError, "서비스"):
            self.invoke(run, execute=True)
        self.assertFalse(any(command[2] == "delete-stack" for command, _ in run.calls))

    def test_live_lock_blocks_deletion(self):
        run = BootstrapRunner()
        run.versions += [
            {"Key": run.key + ".tflock", "VersionId": "lock", "IsLatest": True}
        ]
        with self.assertRaisesRegex(ValueError, "잠금"):
            self.invoke(run, execute=True)

    def test_never_deployed_bootstrap_can_be_deleted(self):
        run = BootstrapRunner()
        run.versions = []
        self.invoke(run, execute=True)
        self.assertTrue(any(command[2] == "delete-stack" for command, _ in run.calls))

    def test_missing_current_state_with_history_blocks_deletion(self):
        run = BootstrapRunner()
        run.versions = [{**run.versions[0], "IsLatest": False}]
        run.markers = [{"Key": run.key, "VersionId": "marker", "IsLatest": True}]
        with self.assertRaisesRegex(ValueError, "state"):
            self.invoke(run, execute=True)

    def test_service_inventory_blocks_empty_state(self):
        responses = [
            (("eks", "list-clusters"), {"clusters": ["scenetrip-dev"]}),
            (
                ("rds", "describe-db-instances"),
                {"DBInstances": [{"DBInstanceIdentifier": "scenetrip-dev"}]},
            ),
            (("ec2", "describe-vpcs"), {"Vpcs": [{"VpcId": "vpc-123"}]}),
            (
                ("ecr", "describe-repositories"),
                {"repositories": [{"repositoryName": "scenetrip-dev/scene-api"}]},
            ),
            (
                ("secretsmanager", "list-secrets"),
                {"SecretList": [{"Name": "/scenetrip/dev/scene-api"}]},
            ),
        ]
        for operation, response in responses:
            with self.subTest(operation=operation):
                run = BootstrapRunner()
                run.overrides[operation] = response
                with self.assertRaisesRegex(ValueError, "서비스"):
                    self.invoke(run, execute=True)

    def test_scheduled_secret_deletion_does_not_block(self):
        run = BootstrapRunner()
        run.overrides[("secretsmanager", "list-secrets")] = {
            "SecretList": [
                {"Name": "/scenetrip/dev/scene-api", "DeletedDate": "2026-09-21"}
            ]
        }
        self.invoke(run)

    def test_absent_stack_and_bucket_are_idempotent(self):
        run = BootstrapRunner()
        run.stack_exists = run.bucket_exists = False
        self.invoke(run, execute=True, purge_state=True)
        self.assertFalse(
            any(command[2].startswith("delete") for command, _ in run.calls)
        )

    def test_retained_bucket_can_be_purged_after_stack_deletion(self):
        run = BootstrapRunner()
        run.stack_exists = False
        self.invoke(run, execute=True, purge_state=True)
        operations = [command[2] for command, _ in run.calls]
        self.assertNotIn("delete-stack", operations)
        self.assertIn("delete-bucket", operations)

    def test_api_failure_is_not_treated_as_absence(self):
        run = BootstrapRunner()
        run.fail = ("cloudformation", "list-stacks")
        with self.assertRaises(RuntimeError):
            self.invoke(run, execute=True)

    def test_stack_wait_failure_prevents_bucket_purge(self):
        run = BootstrapRunner()
        run.fail = ("cloudformation", "wait")
        with self.assertRaises(RuntimeError):
            self.invoke(run, execute=True, purge_state=True)
        self.assertFalse(
            any(command[2] == "delete-objects" for command, _ in run.calls)
        )

    def test_missing_bucket_with_live_stack_blocks_deletion(self):
        run = BootstrapRunner()
        run.bucket_exists = False
        with self.assertRaisesRegex(ValueError, "버킷"):
            self.invoke(run, execute=True)

    def test_invalid_stack_metadata_blocks_deletion(self):
        original = BootstrapRunner()
        describe = json.loads(original(["aws", "cloudformation", "describe-stacks"]))[
            "Stacks"
        ][0]
        cases = [
            {**describe, "StackName": "other"},
            {**describe, "StackStatus": "UPDATE_IN_PROGRESS"},
            {**describe, "Outputs": []},
            {**describe, "Parameters": []},
            {
                **describe,
                "Parameters": [{"ParameterKey": "Environment", "ParameterValue": 1}],
            },
            {**describe, "Parameters": describe["Parameters"] * 2},
        ]
        for description in cases:
            with self.subTest(description=description):
                run = BootstrapRunner()
                run.overrides[("cloudformation", "describe-stacks")] = {
                    "Stacks": [description]
                }
                with self.assertRaises((ValueError, TypeError)):
                    self.invoke(run, execute=True)

    def test_wrong_repository_blocks_deletion(self):
        with (
            patch.dict("os.environ", {"GITHUB_REPOSITORY": "foreign/repository"}),
            self.assertRaisesRegex(ValueError, "저장소"),
        ):
            self.invoke(BootstrapRunner(), execute=True)

    def test_foreign_stack_arn_resources_and_retention_fail_closed(self):
        cases = [
            (
                ("cloudformation", "list-stacks"),
                {
                    "StackSummaries": [
                        {
                            "StackName": "scenetrip-dev-bootstrap",
                            "StackStatus": "CREATE_COMPLETE",
                            "StackId": "arn:aws:cloudformation:ap-northeast-2:999999999999:stack/scenetrip-dev-bootstrap/abc",
                        }
                    ]
                },
            ),
            (
                ("cloudformation", "list-stacks"),
                {"StackSummaries": [{"StackName": "scenetrip-dev-bootstrap"}] * 2},
            ),
            (("cloudformation", "describe-stacks"), {"Stacks": []}),
            (
                ("cloudformation", "list-stack-resources"),
                {"StackResourceSummaries": []},
            ),
            (
                ("cloudformation", "get-template"),
                {
                    "TemplateBody": {
                        "Resources": {
                            "TerraformStateBucket": {"DeletionPolicy": "Delete"}
                        }
                    }
                },
            ),
            (("cloudformation", "get-template"), {"TemplateBody": []}),
        ]
        for operation, response in cases:
            with self.subTest(operation=operation, response=response):
                run = BootstrapRunner()
                run.overrides[operation] = response
                with self.assertRaises((ValueError, TypeError)):
                    self.invoke(run, execute=True)

    def test_incomplete_inventory_and_invalid_options_fail_closed(self):
        for operation, response in [
            (("cloudformation", "list-stacks"), {}),
            (("eks", "list-clusters"), {}),
            (("rds", "describe-db-instances"), {"DBInstances": [{}]}),
        ]:
            with self.subTest(operation=operation):
                run = BootstrapRunner()
                run.overrides[operation] = response
                with self.assertRaises(ValueError):
                    self.invoke(run, execute=True)
        with self.assertRaises(TypeError):
            self.invoke(BootstrapRunner(), execute="false")

    def test_bucket_owned_by_another_stack_generation_is_not_removed(self):
        run = BootstrapRunner()
        tags = json.loads(run(["aws", "s3api", "get-bucket-tagging"]))["TagSet"]
        tags = [
            {**item, "Value": item["Value"] + "-other"}
            if item["Key"] == "aws:cloudformation:stack-id"
            else item
            for item in tags
        ]
        run.overrides[("s3api", "get-bucket-tagging")] = {"TagSet": tags}
        with self.assertRaisesRegex(ValueError, "다른 bootstrap"):
            self.invoke(run, execute=True)

    def test_wrong_bucket_tags_and_location_fail_closed(self):
        for operation, response in [
            (("s3api", "get-bucket-tagging"), {"TagSet": []}),
            (("s3api", "get-bucket-location"), {"LocationConstraint": "us-east-1"}),
        ]:
            with self.subTest(operation=operation):
                run = BootstrapRunner()
                run.overrides[operation] = response
                with self.assertRaises(ValueError):
                    self.invoke(run, execute=True)

    def test_malformed_state_fails_without_echoing_contents(self):
        for state in (
            {},
            {"version": 4, "resources": "sentinel"},
            {"version": 4, "resources": [{"mode": "managed"}]},
        ):
            with self.subTest(state=state):
                run = BootstrapRunner()
                run.state = state
                with self.assertRaises((ValueError, TypeError)) as error:
                    self.invoke(run, execute=True)
                self.assertNotIn("sentinel", str(error.exception))
