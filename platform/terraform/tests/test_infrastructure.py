"""AWS 계정 접근 없이 배포 권한·환경 경계를 회귀 검증한다."""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class InfrastructureBoundaryTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / "platform/terraform/bootstrap/template.json"
        self.template = json.loads(path.read_text())
        role = self.template["Resources"]["DeploymentRole"]["Properties"]
        self.role = role
        self.statements = role["Policies"][0]["PolicyDocument"]["Statement"]

    def test_oidc_subject_is_exact_environment(self):
        trust = self.role["AssumeRolePolicyDocument"]["Statement"][0]
        condition = trust["Condition"]["StringEquals"]
        self.assertEqual(
            condition["token.actions.githubusercontent.com:aud"], "sts.amazonaws.com"
        )
        self.assertEqual(
            condition["token.actions.githubusercontent.com:sub"],
            {"Fn::Sub": "repo:${GitHubRepository}:environment:${Environment}"},
        )
        self.assertNotIn("StringLike", trust["Condition"])

    def test_cloudformation_intrinsic_shapes_are_valid(self):
        def check(value):
            if isinstance(value, list):
                for item in value:
                    check(item)
            if not isinstance(value, dict):
                return
            if "Fn::Sub" in value:
                self.assertIsInstance(value["Fn::Sub"], str)
            if "Ref" in value:
                self.assertIsInstance(value["Ref"], str)
            if "Fn::GetAtt" in value:
                self.assertEqual(len(value["Fn::GetAtt"]), 2)
                self.assertTrue(all(isinstance(v, str) for v in value["Fn::GetAtt"]))
            for child in value.values():
                check(child)

        check(self.template)

    def test_deployer_cannot_rewrite_iam_trust_or_policy(self):
        forbidden = {
            "iam:*",
            "iam:CreateRole",
            "iam:UpdateAssumeRolePolicy",
            "iam:AttachRolePolicy",
            "iam:PutRolePolicy",
            "iam:CreatePolicyVersion",
        }
        for statement in self.statements:
            self.assertFalse(forbidden.intersection(statement["Action"]))

    def test_cluster_creation_uses_supported_iam_scope(self):
        statements = [s for s in self.statements if "eks:CreateCluster" in s["Action"]]
        self.assertEqual(len(statements), 1)
        statement = statements[0]
        self.assertEqual(statement["Resource"], "*")
        expected = {
            "aws:RequestTag/Project": "scenetrip",
            "aws:RequestTag/Environment": {"Ref": "Environment"},
            "aws:RequestedRegion": {"Ref": "AWS::Region"},
            "eks:authenticationMode": "API",
        }
        self.assertEqual(statement["Condition"]["StringEquals"], expected)
        self.assertEqual(
            statement["Condition"]["Bool"][
                "eks:bootstrapClusterCreatorAdminPermissions"
            ],
            "false",
        )

    def test_state_cannot_be_deleted_and_lock_is_separate(self):
        deletes = [s for s in self.statements if "s3:DeleteObject" in s["Action"]]
        self.assertEqual(len(deletes), 1)
        self.assertTrue(
            deletes[0]["Resource"]["Fn::Sub"].endswith("terraform.tfstate.tflock")
        )
        bucket = self.template["Resources"]["TerraformStateBucket"]
        self.assertEqual(bucket["DeletionPolicy"], "Retain")
        self.assertEqual(
            bucket["Properties"]["VersioningConfiguration"]["Status"], "Enabled"
        )
        self.assertTrue(
            all(bucket["Properties"]["PublicAccessBlockConfiguration"].values())
        )

    def test_alb_readiness_permissions_are_read_only_and_regional(self):
        statement = next(s for s in self.statements if s["Sid"] == "RegionalDiscovery")
        expected = {
            "elasticloadbalancing:DescribeLoadBalancers",
            "elasticloadbalancing:DescribeTargetGroups",
            "elasticloadbalancing:DescribeTargetHealth",
        }
        self.assertTrue(expected.issubset(statement["Action"]))
        self.assertEqual(statement["Resource"], "*")
        self.assertEqual(
            statement["Condition"]["StringEquals"]["aws:RequestedRegion"],
            {"Ref": "AWS::Region"},
        )
        for policy in self.statements:
            for action in policy["Action"]:
                if action.startswith("elasticloadbalancing:"):
                    self.assertIn(action, expected)

    def test_alb_backend_rule_permission_is_bound_to_managed_cluster_group(self):
        statement = next(
            s for s in self.statements if s["Sid"] == "ManageEnvironmentClusterIngress"
        )
        self.assertEqual(
            set(statement["Action"]),
            {
                "ec2:AuthorizeSecurityGroupIngress",
                "ec2:RevokeSecurityGroupIngress",
                "ec2:ModifySecurityGroupRules",
            },
        )
        self.assertTrue(statement["Resource"]["Fn::Sub"].endswith(":security-group/*"))
        self.assertEqual(
            statement["Condition"]["StringEquals"],
            {
                "aws:ResourceTag/aws:eks:cluster-name": {
                    "Fn::Sub": "scenetrip-${Environment}"
                }
            },
        )

    def test_master_secret_reads_are_bound_to_environment_database(self):
        statement = next(
            s for s in self.statements if s["Sid"] == "ReadEnvironmentRdsMasterSecret"
        )
        tag = statement["Condition"]["StringEquals"][
            "secretsmanager:ResourceTag/aws:rds:primaryDBInstanceArn"
        ]
        self.assertTrue(tag["Fn::Sub"].endswith("db:scenetrip-${Environment}"))

    def test_resolved_inline_policy_fits_iam_quota(self):
        values = {
            "AWS::AccountId": "111122223333",
            "AWS::Region": "ap-northeast-2",
            "AWS::Partition": "aws",
            "Environment": "prd",
            "TerraformStateBucket": "scenetrip-tfstate-111122223333-ap-northeast-2-prd",
        }

        def resolve(value):
            if isinstance(value, list):
                return [resolve(item) for item in value]
            if not isinstance(value, dict):
                return value
            if "Ref" in value:
                return values[value["Ref"]]
            if "Fn::Sub" in value:
                return re.sub(
                    r"\$\{([^}]+)\}", lambda match: values[match[1]], value["Fn::Sub"]
                )
            return {key: resolve(child) for key, child in value.items()}

        policy = self.role["Policies"][0]["PolicyDocument"]
        self.assertLessEqual(
            len(json.dumps(resolve(policy), separators=(",", ":"))), 10240
        )

    def test_examples_have_distinct_topology_and_no_real_account(self):
        examples = [
            json.loads(
                (
                    ROOT / f"platform/environments/{env}/terraform.tfvars.json.example"
                ).read_text()
            )
            for env in ("dev", "prd")
        ]
        self.assertEqual([len(e["availability_zones"]) for e in examples], [2, 3])
        self.assertEqual(len({e["vpc_cidr"] for e in examples}), 2)
        for example in examples:
            self.assertEqual(
                example["aws_account_id"], "REPLACE_WITH_ENVIRONMENT_ACCOUNT_ID"
            )
            self.assertNotIn("0.0.0.0/0", example["ingress_allowed_cidrs"])
            self.assertNotIn("0.0.0.0/0", example["eks_public_access_cidrs"])

    def test_secret_values_are_not_terraform_resources(self):
        source = "\n".join(
            p.read_text() for p in (ROOT / "platform/terraform/aws").glob("*.tf")
        )
        self.assertNotIn('resource "aws_secretsmanager_secret_version"', source)
        self.assertNotIn('data "aws_secretsmanager_secret_version"', source)
        self.assertNotIn('resource "random_password"', source)
        self.assertIn("manage_master_user_password", source)


if __name__ == "__main__":
    unittest.main()
