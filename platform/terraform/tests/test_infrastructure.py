"""AWS 계정 접근 없이 배포 권한·환경 경계를 회귀 검증한다."""

import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def resolve_conditions(template, value, environment):
    """`Fn::If` 를 그 환경의 조건값으로 풀고 `AWS::NoValue` 를 지운다 — CloudFormation 이 배포 때 하는 것."""
    conditions = template.get("Conditions", {})

    def truth(name):
        condition = conditions[name]
        ((operator, operands),) = condition.items()
        if operator != "Fn::Equals":
            raise AssertionError(f"알 수 없는 조건 {operator}")
        left, right = (
            environment if operand == {"Ref": "Environment"} else operand
            for operand in operands
        )
        return left == right

    def walk(node):
        if isinstance(node, list):
            items = [walk(item) for item in node]
            return [item for item in items if item is not NO_VALUE]
        if not isinstance(node, dict):
            return node
        if node == {"Ref": "AWS::NoValue"}:
            return NO_VALUE
        if set(node) == {"Fn::If"}:
            name, yes, no = node["Fn::If"]
            return walk(yes if truth(name) else no)
        items = {key: walk(child) for key, child in node.items()}
        return {key: child for key, child in items.items() if child is not NO_VALUE}

    return walk(value)


NO_VALUE = object()


class InfrastructureBoundaryTest(unittest.TestCase):
    def setUp(self):
        path = ROOT / "platform/terraform/bootstrap/template.json"
        self.template = json.loads(path.read_text())
        role = self.template["Resources"]["DeploymentRole"]["Properties"]
        self.role = role
        self.statements = role["Policies"][0]["PolicyDocument"]["Statement"]

    def test_iam_role_descriptions_are_latin_only(self):
        # IAM 은 역할 Description 에 [ -~¡-ÿ] 만 허용한다.
        # 한글 설명은 CloudFormation 실제 apply 에서만 CREATE_FAILED 로 드러난다.
        allowed = re.compile(r"[\u0009\u000A\u000D -~¡-ÿ]*")
        for logical, resource in self.template["Resources"].items():
            if resource["Type"] != "AWS::IAM::Role":
                continue
            description = resource["Properties"].get("Description", "")
            with self.subTest(role=logical):
                self.assertTrue(allowed.fullmatch(description), description)

    def test_security_group_descriptions_use_ec2_charset(self):
        # EC2 는 SG·SG 규칙 description 에 a-zA-Z0-9. _-:/()#,@[]+=&;{}!$* 만 허용한다.
        # 아포스트로피 하나가 첫 실제 apply 를 중단시켰고 plan·validate 는 이를 잡지 못한다.
        allowed = re.compile(r"[a-zA-Z0-9. _\-:/()#,@\[\]+=&;{}!$*]{1,255}")
        block = re.compile(
            r'resource\s+"(aws_security_group|aws_vpc_security_group_\w+_rule)"\s+"(\w+)"\s*\{(.*?)\n\}',
            re.DOTALL,
        )
        found = 0
        for path in sorted((ROOT / "platform/terraform/aws").glob("*.tf")):
            for kind, name, body in block.findall(path.read_text()):
                match = re.search(r'description\s*=\s*"([^"]*)"', body)
                self.assertIsNotNone(match, f"{kind}.{name} description 누락")
                found += 1
                with self.subTest(resource=f"{kind}.{name}"):
                    self.assertTrue(allowed.fullmatch(match.group(1)), match.group(1))
        self.assertGreaterEqual(found, 6)

    def test_lifecycle_role_is_narrow_and_environment_bound(self):
        role = self.template["Resources"]["LifecycleRole"]["Properties"]
        self.assertEqual(
            role["RoleName"], {"Fn::Sub": "scenetrip-${Environment}-lifecycle"}
        )
        self.assertLessEqual(role["MaxSessionDuration"], 14400)
        trust = role["AssumeRolePolicyDocument"]["Statement"]
        self.assertEqual(len(trust), 1)
        self.assertEqual(
            trust[0]["Condition"],
            self.role["AssumeRolePolicyDocument"]["Statement"][0]["Condition"],
        )
        self.assertNotIn("StringLike", trust[0]["Condition"])
        statements = {
            statement["Sid"]: statement
            for statement in role["Policies"][0]["PolicyDocument"]["Statement"]
        }
        actions = {
            action
            for statement in statements.values()
            for action in statement["Action"]
        }
        self.assertEqual(
            actions,
            {
                "ec2:StartInstances",
                "ec2:StopInstances",
                "ec2:DescribeInstances",
                "ec2:DescribeSecurityGroups",
                "elasticloadbalancing:DescribeLoadBalancers",
                "secretsmanager:ListSecrets",
                "secretsmanager:DescribeSecret",
                "secretsmanager:RestoreSecret",
            },
        )
        # 인스턴스 전원은 배포 runner 태그 세 개가 모두 맞을 때만 다룬다.
        power = statements["RunnerPower"]["Condition"]["StringEquals"]
        self.assertEqual(power["aws:ResourceTag/project"], "scenetrip")
        self.assertEqual(power["aws:ResourceTag/environment"], {"Ref": "Environment"})
        self.assertEqual(power["aws:ResourceTag/role"], "github-runner")
        # Secret 값은 읽지 못하고 해당 환경 경로의 복원만 한다.
        self.assertNotIn("secretsmanager:GetSecretValue", actions)
        self.assertTrue(
            statements["SecretRestore"]["Resource"]["Fn::Sub"].endswith(
                ":secret:/scenetrip/${Environment}/*"
            )
        )
        outputs = self.template["Outputs"]
        self.assertEqual(
            outputs["LifecycleRoleArn"]["Value"],
            {"Fn::GetAtt": ["LifecycleRole", "Arn"]},
        )

    def test_oidc_subject_is_exact_environment(self):
        trust = self.role["AssumeRolePolicyDocument"]["Statement"][0]
        condition = trust["Condition"]["StringEquals"]
        self.assertEqual(
            condition["token.actions.githubusercontent.com:aud"], "sts.amazonaws.com"
        )
        self.assertEqual(
            condition["token.actions.githubusercontent.com:sub"],
            {"Fn::Sub": "${GitHubOidcSubjectPrefix}:environment:${Environment}"},
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
            if "Fn::If" in value:
                condition = value["Fn::If"]
                self.assertIsInstance(condition, list)
                self.assertEqual(len(condition), 3)
                self.assertIn(condition[0], self.template.get("Conditions", {}))
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

    # --- 사용자 사진 버킷(docs/project/plans/review.md §13, docs/ops/aws-deployment.md §12) ---

    def _media_bucket(self):
        return self.template["Resources"]["UserMediaBucket"]

    def _media_role(self):
        return self.template["Resources"]["MediaRole"]["Properties"]

    def _sid(self, sid):
        return next(s for s in self.statements if s["Sid"] == sid)

    def test_user_media_bucket_is_retained_private_and_expires_tmp(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                self._check_user_media_bucket(environment)

    def _check_user_media_bucket(self, environment):
        bucket = resolve_conditions(self.template, self._media_bucket(), environment)
        self.assertEqual(bucket["Type"], "AWS::S3::Bucket")
        # dev 를 내려도(Terraform 을 지워도) 사진은 남는다 — bootstrap 의 Retain
        self.assertEqual(bucket["DeletionPolicy"], "Retain")
        self.assertEqual(bucket["UpdateReplacePolicy"], "Retain")
        props = bucket["Properties"]
        self.assertEqual(
            props["BucketName"],
            {
                "Fn::Sub": "scenetrip-user-media-${AWS::AccountId}-${AWS::Region}-${Environment}"
            },
        )
        sse = props["BucketEncryption"]["ServerSideEncryptionConfiguration"]
        self.assertTrue(sse)
        self.assertIn(
            sse[0]["ServerSideEncryptionByDefault"]["SSEAlgorithm"],
            {"AES256", "aws:kms"},
        )
        block = props["PublicAccessBlockConfiguration"]
        self.assertEqual(
            set(block),
            {
                "BlockPublicAcls",
                "IgnorePublicAcls",
                "BlockPublicPolicy",
                "RestrictPublicBuckets",
            },
        )
        self.assertTrue(all(v is True for v in block.values()))
        self.assertEqual(
            props["OwnershipControls"]["Rules"],
            [{"ObjectOwnership": "BucketOwnerEnforced"}],
        )
        rules = props["LifecycleConfiguration"]["Rules"]
        expiring = [r for r in rules if "ExpirationInDays" in r]
        # 하루 뒤 지우는 것은 uploads/tmp/ 뿐이다 — reviews/ 를 지우는 규칙이 있으면 붙인 사진이 사라진다
        self.assertEqual(len(expiring), 1)
        self.assertEqual(expiring[0]["Status"], "Enabled")
        self.assertEqual(expiring[0]["ExpirationInDays"], 1)
        prefix = expiring[0].get("Prefix", expiring[0].get("Filter", {}).get("Prefix"))
        self.assertEqual(prefix, "uploads/tmp/")
        noncurrent = [r for r in rules if "NoncurrentVersionExpiration" in r]
        if environment == "prd":
            # 사용자가 올린 사진은 다시 만들 수 없다 — prd 만 버전 관리, 지운 판은 30 일 뒤 영구 삭제(ops-protection.md §1)
            self.assertEqual(props["VersioningConfiguration"], {"Status": "Enabled"})
            self.assertEqual(len(noncurrent), 1)
            self.assertEqual(noncurrent[0]["Status"], "Enabled")
            self.assertEqual(
                noncurrent[0]["NoncurrentVersionExpiration"], {"NoncurrentDays": 30}
            )
            self.assertEqual(
                noncurrent[0].get(
                    "Prefix", noncurrent[0].get("Filter", {}).get("Prefix", "")
                ),
                "",
            )
        else:
            # dev 는 시험용이라 켜지 않는다(비용) — 지금과 같다
            self.assertNotIn("VersioningConfiguration", props)
            self.assertEqual(noncurrent, [])
            self.assertEqual(
                [r["Id"] for r in rules],
                ["ExpireUnattachedUploads", "AbortIncompleteUploads"],
            )

    def test_production_condition_is_environment_prd(self):
        self.assertEqual(
            self.template["Conditions"]["IsProduction"],
            {"Fn::Equals": [{"Ref": "Environment"}, "prd"]},
        )

    # --- dev 최종 스냅샷 정리(docs/project/plans/ops-protection.md §1, docs/ops/aws-deployment.md §14) ---

    def test_deployer_deletes_only_dev_final_snapshots(self):
        statement = self._sid("PruneDevFinalSnapshots")
        self.assertEqual(statement["Effect"], "Allow")
        self.assertEqual(statement["Action"], ["rds:DeleteDBSnapshot"])
        # 환경 변수가 아니라 dev 로 고정 — prd 스택이 만든 배포 역할도 prd 스냅샷은 못 지운다
        self.assertEqual(
            statement["Resource"],
            {
                "Fn::Sub": "arn:${AWS::Partition}:rds:${AWS::Region}:${AWS::AccountId}:snapshot:scenetrip-dev-final-*"
            },
        )
        self.assertNotIn("Condition", statement)

    def test_no_other_statement_can_delete_snapshots(self):
        for statement in self.statements:
            if statement["Sid"] == "PruneDevFinalSnapshots":
                continue
            actions = statement["Action"]
            actions = actions if isinstance(actions, list) else [actions]
            for action in actions:
                with self.subTest(sid=statement["Sid"], action=action):
                    self.assertNotEqual(action, "rds:DeleteDBSnapshot")
                    self.assertNotIn(action, {"rds:*", "*", "rds:Delete*"})

    def test_deployer_policy_has_no_conditional_statements(self):
        # 조건문을 권한 목록에 넣지 않는다 — 템플릿을 읽는 점검이 그대로 돈다(ops-protection.md §1)
        policy = json.dumps(self.role["Policies"])
        self.assertNotIn("Fn::If", policy)
        self.assertNotIn("AWS::NoValue", policy)

    def test_user_media_bucket_policy_denies_plain_http(self):
        policies = [
            r
            for r in self.template["Resources"].values()
            if r["Type"] == "AWS::S3::BucketPolicy"
            and r["Properties"]["Bucket"] == {"Ref": "UserMediaBucket"}
        ]
        self.assertEqual(len(policies), 1)
        statements = policies[0]["Properties"]["PolicyDocument"]["Statement"]
        tls = [
            s
            for s in statements
            if s["Effect"] == "Deny"
            and s.get("Condition", {}).get("Bool", {}).get("aws:SecureTransport")
            == "false"
        ]
        self.assertEqual(len(tls), 1)
        self.assertEqual(tls[0]["Principal"], "*")
        self.assertEqual(tls[0]["Action"], "s3:*")
        resources = {r["Fn::Sub"] for r in tls[0]["Resource"]}
        self.assertEqual(
            resources,
            {
                "arn:${AWS::Partition}:s3:::${UserMediaBucket}",
                "arn:${AWS::Partition}:s3:::${UserMediaBucket}/*",
            },
        )
        # 버킷 정책이 무언가를 허용하면(공개 등) 안 된다 — 거부만 둔다
        self.assertTrue(all(s["Effect"] == "Deny" for s in statements))

    def test_media_role_trusts_only_environment_pod_identity(self):
        role = self._media_role()
        self.assertEqual(
            role["RoleName"], {"Fn::Sub": "scenetrip-${Environment}-media"}
        )
        trust = role["AssumeRolePolicyDocument"]["Statement"]
        self.assertEqual(len(trust), 1)
        statement = trust[0]
        self.assertEqual(statement["Effect"], "Allow")
        self.assertEqual(statement["Principal"], {"Service": "pods.eks.amazonaws.com"})
        self.assertEqual(
            sorted(statement["Action"]), ["sts:AssumeRole", "sts:TagSession"]
        )
        condition = statement["Condition"]
        self.assertEqual(
            condition["StringEquals"], {"aws:SourceAccount": {"Ref": "AWS::AccountId"}}
        )
        self.assertEqual(
            condition["ArnEquals"],
            {
                "aws:SourceArn": {
                    "Fn::Sub": "arn:${AWS::Partition}:eks:${AWS::Region}:${AWS::AccountId}:cluster/scenetrip-${Environment}"
                }
            },
        )
        self.assertNotIn("StringLike", condition)
        self.assertNotIn("ArnLike", condition)

    def test_media_role_reaches_only_three_prefixes_of_the_media_bucket(self):
        # uploads/tmp(막 올린 것) · reviews(리뷰 사진) · posts(여행후기 사진, MZ2AZ-352, community-post.md §4).
        # 버킷 전체 객체(${UserMediaBucket}/*)나 다른 접두사에는 닿지 않는다.
        role = self._media_role()
        self.assertNotIn("ManagedPolicyArns", role)
        self.assertNotIn("PermissionsBoundary", role)
        statements = [
            s
            for policy in role["Policies"]
            for s in policy["PolicyDocument"]["Statement"]
        ]
        self.assertTrue(all(s["Effect"] == "Allow" for s in statements))
        by_action = {}
        for s in statements:
            actions = s["Action"] if isinstance(s["Action"], list) else [s["Action"]]
            resources = (
                s["Resource"] if isinstance(s["Resource"], list) else [s["Resource"]]
            )
            for action in actions:
                by_action.setdefault(action, set()).update(
                    r["Fn::Sub"] for r in resources
                )
        objects = {
            "arn:${AWS::Partition}:s3:::${UserMediaBucket}/uploads/tmp/*",
            "arn:${AWS::Partition}:s3:::${UserMediaBucket}/reviews/*",
            "arn:${AWS::Partition}:s3:::${UserMediaBucket}/posts/*",
        }
        every_resource = set().union(*by_action.values())
        self.assertNotIn(
            "arn:${AWS::Partition}:s3:::${UserMediaBucket}/*", every_resource
        )
        self.assertFalse(any("*" in r.split("/", 1)[0] for r in every_resource))
        self.assertEqual(
            by_action,
            {
                "s3:GetObject": objects,
                "s3:PutObject": objects,
                "s3:DeleteObject": objects,
                "s3:ListBucket": {"arn:${AWS::Partition}:s3:::${UserMediaBucket}"},
            },
        )

    def test_media_outputs(self):
        outputs = self.template["Outputs"]
        self.assertEqual(
            outputs["UserMediaBucket"]["Value"], {"Ref": "UserMediaBucket"}
        )
        self.assertEqual(
            outputs["MediaRoleArn"]["Value"], {"Fn::GetAtt": ["MediaRole", "Arn"]}
        )

    def test_deployer_manages_pod_identity_only_in_environment_cluster(self):
        actions = {
            "eks:CreatePodIdentityAssociation",
            "eks:DescribePodIdentityAssociation",
            "eks:UpdatePodIdentityAssociation",
            "eks:DeletePodIdentityAssociation",
        }
        holders = [
            s
            for s in self.statements
            if actions.intersection(
                s["Action"] if isinstance(s["Action"], list) else [s["Action"]]
            )
        ]
        self.assertEqual(len(holders), 1)
        statement = holders[0]
        self.assertTrue(actions.issubset(statement["Action"]))
        resources = {r["Fn::Sub"] for r in statement["Resource"]}
        prefix = "arn:${AWS::Partition}:eks:${AWS::Region}:${AWS::AccountId}:"
        self.assertIn(prefix + "cluster/scenetrip-${Environment}", resources)
        self.assertIn(
            prefix + "podidentityassociation/scenetrip-${Environment}/*", resources
        )
        for resource in resources:
            with self.subTest(resource=resource):
                self.assertTrue(resource.startswith(prefix), resource)
                self.assertIn("scenetrip-${Environment}", resource)
                self.assertNotEqual(resource.split(":")[-1], "*")

    def test_deployer_passes_and_reads_only_environment_roles(self):
        media = "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/scenetrip-${Environment}-media"
        bedrock = "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/scenetrip-${Environment}-bedrock"
        allowed = {
            "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/scenetrip-${Environment}-eks-cluster",
            "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/scenetrip-${Environment}-eks-node",
            media,
            bedrock,
        }
        passing = [s for s in self.statements if "iam:PassRole" in s["Action"]]
        self.assertEqual(len(passing), 1)
        self.assertEqual(passing[0]["Action"], ["iam:PassRole"])
        # PassRole 은 역할 이름을 하나하나 — 와일드카드로 넓히지 않는다
        self.assertIsInstance(passing[0]["Resource"], list)
        passed = [r["Fn::Sub"] for r in passing[0]["Resource"]]
        self.assertEqual(len(passed), len(set(passed)))
        self.assertEqual(set(passed), allowed)
        self.assertFalse(any("*" in arn for arn in passed), passed)
        self.assertEqual(
            passing[0]["Condition"],
            {
                "StringEquals": {
                    "iam:PassedToService": [
                        "eks.amazonaws.com",
                        "ec2.amazonaws.com",
                        "pods.eks.amazonaws.com",
                    ]
                }
            },
        )
        # Terraform 의 data "aws_iam_role" "media"·"bedrock" 이 역할을 읽는다 — 읽기만, 이 환경의 역할만.
        # 정책 크기 한도(10240) 때문에 역할을 나열하지 않고 환경 접두사 하나로 묶는다.
        reading = [s for s in self.statements if "iam:GetRole" in s["Action"]]
        self.assertEqual(len(reading), 1)
        read = reading[0]
        self.assertEqual(read["Sid"], "ReadClusterRoles")
        self.assertEqual(read["Effect"], "Allow")
        self.assertNotIn("Condition", read)
        self.assertEqual(
            set(read["Action"]),
            {
                "iam:GetRole",
                "iam:ListRoleTags",
                "iam:ListAttachedRolePolicies",
                "iam:ListRolePolicies",
                "iam:GetRolePolicy",
            },
        )
        self.assertEqual(
            read["Resource"],
            {
                "Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/scenetrip-${Environment}-*"
            },
        )
        # 환경 역할 와일드카드(scenetrip-${Environment}-*)는 이 읽기 문장에만 있다
        for statement in self.statements:
            if statement is read:
                continue
            resources = statement["Resource"]
            resources = resources if isinstance(resources, list) else [resources]
            for r in resources:
                text = (
                    r["Fn::Sub"] if isinstance(r, dict) and "Fn::Sub" in r else str(r)
                )
                with self.subTest(sid=statement.get("Sid"), resource=text):
                    self.assertFalse(
                        text.endswith(":role/scenetrip-${Environment}-*"),
                        "환경 역할 와일드카드는 읽기 문장에만 허용한다",
                    )
        # 배포 역할은 사진 버킷 자체에 손대지 않는다(파드의 역할만 쓴다)
        for statement in self.statements:
            resources = statement["Resource"]
            resources = resources if isinstance(resources, list) else [resources]
            for r in resources:
                text = (
                    r["Fn::Sub"] if isinstance(r, dict) and "Fn::Sub" in r else str(r)
                )
                self.assertNotIn("user-media", text)
                self.assertNotIn("UserMediaBucket", text)

    def test_terraform_associates_scene_api_service_account_with_media_role(self):
        source = (ROOT / "platform/terraform/aws/eks.tf").read_text()
        data = re.search(
            r'data\s+"aws_iam_role"\s+"media"\s*\{(.*?)\n\}', source, re.DOTALL
        )
        self.assertIsNotNone(data)
        self.assertRegex(data.group(1), r'name\s*=\s*"\$\{local\.name\}-media"')
        block = re.search(
            r'resource\s+"aws_eks_pod_identity_association"\s+"(\w+)"\s*\{(.*?)\n\}',
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(block)
        body = block.group(2)
        self.assertRegex(body, r'namespace\s*=\s*"scenetrip"')
        self.assertRegex(body, r'service_account\s*=\s*"scene-api"')
        self.assertRegex(body, r"role_arn\s*=\s*data\.aws_iam_role\.media\.arn")
        self.assertRegex(body, r"cluster_name\s*=\s*aws_eks_cluster\.this\.name")
        locals_source = "\n".join(
            p.read_text() for p in (ROOT / "platform/terraform/aws").glob("*.tf")
        )
        self.assertRegex(
            locals_source, r'name\s*=\s*"scenetrip-\$\{var\.environment\}"'
        )
        outputs = (ROOT / "platform/terraform/aws/outputs.tf").read_text()
        self.assertRegex(
            outputs,
            r'output\s+"user_media_bucket"\s*\{\s*value\s*=\s*"scenetrip-user-media-\$\{var\.aws_account_id\}-\$\{var\.aws_region\}-\$\{var\.environment\}"',
        )

    # --- trip-guide 의 Bedrock 역할(MZ2AZ-317, docs/project/plans/bedrock-iam-role.md) ---

    def _bedrock_role(self):
        return self.template["Resources"]["BedrockRole"]["Properties"]

    def test_bedrock_role_trusts_only_environment_pod_identity(self):
        role = self._bedrock_role()
        self.assertEqual(
            role["RoleName"], {"Fn::Sub": "scenetrip-${Environment}-bedrock"}
        )
        trust = role["AssumeRolePolicyDocument"]["Statement"]
        self.assertEqual(len(trust), 1)
        # 사진 버킷 역할과 같은 모양 — 이 계정·이 환경의 클러스터의 Pod Identity 만
        self.assertEqual(
            trust, self._media_role()["AssumeRolePolicyDocument"]["Statement"]
        )
        statement = trust[0]
        self.assertEqual(statement["Effect"], "Allow")
        self.assertEqual(statement["Principal"], {"Service": "pods.eks.amazonaws.com"})
        self.assertEqual(
            sorted(statement["Action"]), ["sts:AssumeRole", "sts:TagSession"]
        )
        condition = statement["Condition"]
        self.assertEqual(
            condition["StringEquals"], {"aws:SourceAccount": {"Ref": "AWS::AccountId"}}
        )
        self.assertEqual(
            condition["ArnEquals"],
            {
                "aws:SourceArn": {
                    "Fn::Sub": "arn:${AWS::Partition}:eks:${AWS::Region}:${AWS::AccountId}:cluster/scenetrip-${Environment}"
                }
            },
        )
        self.assertNotIn("StringLike", condition)
        self.assertNotIn("ArnLike", condition)

    def test_bedrock_role_invokes_only_the_configured_model(self):
        config = json.loads((ROOT / "agents/trip-guide/config/model.json").read_text())
        role = self._bedrock_role()
        self.assertNotIn("ManagedPolicyArns", role)
        self.assertNotIn("PermissionsBoundary", role)
        statements = [
            s
            for policy in role["Policies"]
            for s in policy["PolicyDocument"]["Statement"]
        ]
        self.assertTrue(statements)
        actions = set()
        resources = set()
        for s in statements:
            self.assertEqual(s["Effect"], "Allow")
            self.assertNotIn("NotAction", s)
            self.assertNotIn("NotResource", s)
            items = s["Action"] if isinstance(s["Action"], list) else [s["Action"]]
            actions.update(items)
            targets = (
                s["Resource"] if isinstance(s["Resource"], list) else [s["Resource"]]
            )
            for r in targets:
                resources.add(r["Fn::Sub"] if isinstance(r, dict) else r)
        self.assertEqual(
            actions, {"bedrock:InvokeModel", "bedrock:InvokeModelWithResponseStream"}
        )
        self.assertEqual(config["region"], "us-west-2")
        self.assertEqual(
            resources,
            {
                "arn:${AWS::Partition}:bedrock:"
                + config["region"]
                + "::foundation-model/"
                + config["model"]
            },
        )

    def test_bedrock_role_output(self):
        self.assertEqual(
            self.template["Outputs"]["BedrockRoleArn"]["Value"],
            {"Fn::GetAtt": ["BedrockRole", "Arn"]},
        )

    def test_terraform_associates_trip_guide_service_account_with_bedrock_role(self):
        source = (ROOT / "platform/terraform/aws/eks.tf").read_text()
        data = re.search(
            r'data\s+"aws_iam_role"\s+"bedrock"\s*\{(.*?)\n\}', source, re.DOTALL
        )
        self.assertIsNotNone(data)
        self.assertRegex(data.group(1), r'name\s*=\s*"\$\{local\.name\}-bedrock"')
        block = re.search(
            r'resource\s+"aws_eks_pod_identity_association"\s+"trip_guide_bedrock"\s*\{(.*?)\n\}',
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(block)
        body = block.group(1)
        self.assertRegex(body, r'namespace\s*=\s*"scenetrip"')
        self.assertRegex(body, r'service_account\s*=\s*"trip-guide"')
        self.assertRegex(body, r"role_arn\s*=\s*data\.aws_iam_role\.bedrock\.arn")
        self.assertRegex(body, r"cluster_name\s*=\s*aws_eks_cluster\.this\.name")
        # 연결은 정확히 두 개 — scene-api↔media, trip-guide↔bedrock
        associations = re.findall(
            r'resource\s+"aws_eks_pod_identity_association"\s+"(\w+)"', source
        )
        self.assertEqual(
            sorted(associations), ["scene_api_media", "trip_guide_bedrock"]
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

    def test_app_secrets_are_scene_api_database_auth_signoz_without_trip_guide(self):
        # MZ2AZ-317 후속 — trip-guide 는 Bedrock 을 Pod Identity 역할로 부르므로 비밀값이 없다.
        # ADR 0025 — signoz(SigNoz 관리자 비밀번호)가 더해졌다.
        # 비밀값 이름은 /scenetrip/<env>/<키, _ 는 -> 이다. 이미지 저장소는 앱 넷과 SigNoz 미러 일곱이다.
        source = (ROOT / "platform/terraform/aws/registry-secrets.tf").read_text()

        def local_map(name):
            match = re.search(rf"\n\s*{name}\s*=\s*\{{(.*?)\n\s*\}}", source, re.DOTALL)
            self.assertIsNotNone(match, name)
            return re.findall(r"^\s*(\w+)\s*=", match.group(1), re.MULTILINE)

        self.assertEqual(
            sorted(local_map("app_secret_descriptions")),
            sorted(["scene_api", "database", "auth", "signoz"]),
        )
        repositories = re.search(r"repositories\s*=\s*\{([^}]*)\}", source)
        self.assertIsNotNone(repositories)
        self.assertEqual(
            sorted(re.findall(r"(\w+)\s*=", repositories.group(1))),
            sorted(
                [
                    "scene_api",
                    "trip_guide",
                    "migration",
                    "data_load",
                    "mirror_signoz",
                    "mirror_otel_collector",
                    "mirror_clickhouse",
                    "mirror_alpine",
                    "mirror_clickhouse_operator",
                    "mirror_metrics_exporter",
                    "mirror_zookeeper",
                ]
            ),
        )
        secret = re.search(
            r'resource\s+"aws_secretsmanager_secret"\s+"app"\s*\{(.*?)\n\}',
            source,
            re.DOTALL,
        )
        self.assertIsNotNone(secret)
        self.assertRegex(
            secret.group(1), r"for_each\s*=\s*local\.app_secret_descriptions"
        )
        self.assertIn(
            '"/scenetrip/${var.environment}/${replace(each.key, "_", "-")}"',
            secret.group(1),
        )
        outputs = (ROOT / "platform/terraform/aws/outputs.tf").read_text()
        self.assertRegex(
            outputs,
            r'output\s+"app_secret_arns"\s*\{\s*value\s*=\s*\{\s*for\s+name,\s*secret\s+in\s+'
            r"aws_secretsmanager_secret\.app\s*:",
        )


if __name__ == "__main__":
    unittest.main()
