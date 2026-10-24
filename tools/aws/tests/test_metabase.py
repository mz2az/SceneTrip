"""Metabase on EKS(ADR 0027) 2 단계 — 띄우기 시험. SigNoz 화면(ADR 0025)과 같은 모양이다.

명세: docs/project/plans/metabase-prd.md 「3-5. 2 단계 명세 — 띄우기」 한 절만 본다. AWS·Kubernetes 를 호출하지 않는다.
차트는 Bazel 이 받은 helm 으로 실제로 그리고, 그린 YAML 은 Bazel 의 kubectl(--local, 서버 없이)로 JSON 으로 바꿔 읽는다.
"""

import itertools
import json
import os
import re
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from typing import ClassVar
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import (
    SIGNOZ_CHART,
    ecr_registry,
    is_metabase_helm,
    mirror_urls,
    valid_settings,
)

METABASE_IMAGE = "metabase/metabase"
METABASE_TAG = "v0.64.3.4"
METABASE_DIGEST = (
    "sha256:7804ce4edfa217dd08a896821f57b12a9a392d944bd3797a55520d51c76232e3"
)
METABASE_REPOSITORY = "mirror/metabase/metabase"
METABASE_PUSH = "//platform/helm/metabase:mirror_metabase_push"
METABASE_KEYS = {
    "METABASE_APP_PASSWORD",
    "METABASE_RO_PASSWORD",
    "MB_ENCRYPTION_SECRET_KEY",
}
# 이미 저장된 Metabase 칸(시험용 고정 값). 세 값이 서로 달라 어느 값이 어디로 갔는지 가린다.
STORED_METABASE = {
    "METABASE_APP_PASSWORD": "fixture-metabase-app-password-0123456789",
    "METABASE_RO_PASSWORD": "fixture-metabase-ro-password-0123456789",
    "MB_ENCRYPTION_SECRET_KEY": "fixture-metabase-encryption-key-0123456789",
}
MASTER_ARN = (
    "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds!db-fixture-AbCdEf"
)
MASTER_PASSWORD = "fixture-rds-master-password"
DATABASE_HOST = "db.example"
DNS_CIDR = "172.20.0.10/32"
METABASE_CERTIFICATE = "arn:aws:acm:ap-northeast-2:123456789012:certificate/" + "c" * 36
METABASE_ALB_NAME = "scenetrip-dev-metabase-alb"
METABASE_ALB_HOST = "metabase.ap-northeast-2.elb.amazonaws.com"
KUBECTL_APPLY = ["kubectl", "apply", "-f", "-"]
REPO = Path("/repo")


def workspace():
    return Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]


def prd_settings():
    return valid_settings(
        environment="prd",
        role="arn:aws:iam::123456789012:role/scenetrip-prd-deploy",
        domain="api.example.com",
    )


def deploy_outputs(settings, **changes):
    """배포가 받는 Terraform 출력 — Metabase 칸·복사 저장소·화면 SG·인증서 출력 포함."""
    from tools.aws.tests.test_auth_secret import deploy_outputs as base

    outputs = {
        **base(settings),
        "environment": settings.environment,
        "user_media_bucket": (
            f"scenetrip-user-media-123456789012-ap-northeast-2-{settings.environment}"
        ),
        "database_host": DATABASE_HOST,
        "database_master_secret_arn": MASTER_ARN,
    }
    if settings.environment == "prd":
        outputs["ingress_public"] = True
    return {**outputs, **changes}


def leaves(value):
    """values 안의 모든 문자열(목록 원소 포함)."""
    if isinstance(value, dict):
        for item in value.values():
            yield from leaves(item)
    elif isinstance(value, list):
        for item in value:
            yield from leaves(item)
    elif isinstance(value, str):
        yield value


class Cloud:
    """Secrets Manager·EKS 흉내. put 한 값을 기억해 다음 배포에 돌려준다. 그 밖의 명령은 빈 답(성공)."""

    def __init__(self, metabase=None):
        from tools.aws.tests.test_apple_secret import APPLE_KEY, apple_key
        from tools.aws.tests.test_auth_secret import (
            AUTH_ARN,
            DATABASE_ARN,
            METABASE_ARN,
            SCENE_ARN,
            SIGNOZ_ARN,
            SIGNOZ_ROOT_PASSWORD,
            auth_slot,
        )

        self.metabase_arn = METABASE_ARN
        self.values = {
            # PRD 는 애플 개인 키가 필수다 — 두 환경 모두 같은 칸을 쓴다.
            SCENE_ARN: json.dumps(
                {"KAKAO_REST_KEY": "fixture-kakao", APPLE_KEY: apple_key()}
            ),
            DATABASE_ARN: json.dumps(
                {
                    "username": "app_runtime",
                    "password": "fixture-runtime-password",
                    "migration_username": "app_migrate",
                    "migration_password": "fixture-migration-password",
                }
            ),
            AUTH_ARN: json.dumps(auth_slot()),
            SIGNOZ_ARN: json.dumps({"SIGNOZ_ROOT_PASSWORD": SIGNOZ_ROOT_PASSWORD}),
            MASTER_ARN: json.dumps({"username": "admin", "password": MASTER_PASSWORD}),
        }
        if metabase is not None:
            self.values[METABASE_ARN] = json.dumps(metabase)
        self.calls = []

    def __call__(self, command, **kwargs):
        self.calls.append((list(command), kwargs))
        if command[:2] == ["aws", "secretsmanager"]:
            stdin = kwargs.get("stdin")
            if "put-secret-value" in command:
                body = json.loads(stdin)
                self.values[body["SecretId"]] = body["SecretString"]
                return ""
            arn = next((value for value in command if value in self.values), None)
            if arn is None:
                arn = next(
                    (value for value in command if value.startswith("arn:aws:")), ""
                )
            if "get-secret-value" in command:
                if arn not in self.values:
                    raise RuntimeError("ResourceNotFoundException")
                return json.dumps({"SecretString": self.values[arn]})
            present = arn in self.values
            return json.dumps(
                {
                    "Versions": [{"VersionId": "1"}] if present else [],
                    "VersionIdsToStages": {"1": ["AWSCURRENT"]} if present else {},
                }
            )
        if "describe-cluster" in command:
            return json.dumps(
                {
                    "cluster": {
                        "kubernetesNetworkConfig": {"serviceIpv4Cidr": "172.20.0.0/16"}
                    }
                }
            )
        return ""

    def metabase_puts(self):
        return [
            json.loads(kwargs["stdin"])
            for command, kwargs in self.calls
            if "put-secret-value" in command
            and json.loads(kwargs["stdin"])["SecretId"] == self.metabase_arn
        ]

    def stored_metabase(self):
        return json.loads(self.values[self.metabase_arn])


def run_deploy(settings, outputs, cloud, extra=()):
    """deploy() 를 끝까지 돌린다. SigNoz 설치·화면은 test_signoz 몫이라 표지만 남긴다. DB 준비(migrate_database)는 진짜다."""
    from tools.aws.deploy import deploy

    run = Mock(side_effect=cloud)
    patches = [
        patch("tools.aws.deploy.verify_nodeclass"),
        patch(
            "tools.aws.deploy.wait_for_alb",
            return_value="fixture.ap-northeast-2.elb.amazonaws.com",
        ),
        patch(
            "tools.aws.deploy.install_signoz",
            side_effect=lambda run, *unused, **kw: run(["test-marker", "signoz"]),
        ),
        patch("tools.aws.deploy.expose_signoz"),
        patch("tools.aws.deploy.verify_network_deny"),
        patch("builtins.print"),
        *extra,
    ]
    with ExitStack() as stack:
        for item in patches:
            stack.enter_context(item)
        deploy(run, REPO, settings, outputs, Path(SIGNOZ_CHART))
    return run


def applied(run):
    manifests = []
    for call in run.call_args_list:
        if call.args[0] == KUBECTL_APPLY:
            manifests.append(json.loads(call.kwargs["stdin"]))
    return manifests


def secret_data(manifest):
    from tools.aws.tests.test_auth_secret import secret_data as decode

    return decode(manifest)


def transient_admin_secret(run):
    found = [
        m
        for m in applied(run)
        if m.get("kind") == "Secret"
        and m["metadata"].get("name") == "database-admin-transient"
    ]
    if len(found) != 1:
        raise AssertionError(
            f"database-admin-transient Secret 이 {len(found)} 번 적용됐습니다"
        )
    return secret_data(found[0])


def metabase_helm(run):
    found = [call for call in run.call_args_list if is_metabase_helm(call.args[0])]
    if len(found) != 1:
        raise AssertionError(f"metabase helm 설치가 {len(found)} 번 불렸습니다")
    return found[0]


# --- A. 설정 -------------------------------------------------------------------------------------------------


class MetabaseConfigTest(unittest.TestCase):
    def test_mirror_image_and_tag(self):
        from tools.aws.config import METABASE_MIRROR

        self.assertEqual(
            METABASE_MIRROR, {"mirror_metabase": (METABASE_IMAGE, METABASE_TAG)}
        )

    def test_repositories_include_metabase_mirror(self):
        from tools.aws.config import REPOSITORIES

        self.assertEqual(REPOSITORIES["mirror_metabase"], METABASE_REPOSITORY)

    def test_domains_and_environments(self):
        from tools.aws.config import METABASE_DOMAINS, METABASE_ENVIRONMENTS

        self.assertEqual(
            METABASE_DOMAINS,
            {"dev": "metabase-dev.scenetrip.io", "prd": "metabase.scenetrip.io"},
        )
        # 먼저 DEV 만. PRD 는 앱 심사가 끝난 뒤 따로 확인을 받고 더한다.
        self.assertEqual(METABASE_ENVIRONMENTS, ("dev",))

    def test_metabase_secret_kind_has_three_keys(self):
        from tools.aws.config import SECRET_KEYS, validate_secret

        self.assertEqual(SECRET_KEYS["metabase"], METABASE_KEYS)
        self.assertEqual(validate_secret("metabase", STORED_METABASE), STORED_METABASE)
        for key in METABASE_KEYS:
            missing = {k: v for k, v in STORED_METABASE.items() if k != key}
            with self.subTest(missing=key), self.assertRaises(ValueError):
                validate_secret("metabase", missing)
        with self.assertRaises(ValueError):
            validate_secret("metabase", {**STORED_METABASE, "EXTRA": "x"})

    def test_app_secrets_include_metabase(self):
        from tools.aws import aws, config

        self.assertIn("metabase", config.APP_SECRETS)
        self.assertEqual(tuple(aws.APP_SECRETS), config.APP_SECRETS)

    def test_terraform_repositories_and_secret_include_metabase(self):
        source = (
            workspace() / "platform/terraform/aws/registry-secrets.tf"
        ).read_text()
        block = re.search(r"\brepositories\s*=\s*\{(.*?)\n\s*\}", source, re.DOTALL)
        self.assertIsNotNone(block)
        self.assertRegex(
            block.group(1), r'\bmirror_metabase\s*=\s*"mirror/metabase/metabase"'
        )
        secrets = re.search(
            r"\bapp_secret_descriptions\s*=\s*\{(.*?)\n\s*\}", source, re.DOTALL
        )
        self.assertIsNotNone(secrets)
        self.assertRegex(secrets.group(1), r"(?m)^\s*metabase\s*=")

    def test_terraform_metabase_file_variable_and_outputs(self):
        root = workspace() / "platform/terraform/aws"
        metabase = root / "metabase.tf"
        self.assertTrue(
            metabase.is_file(), "platform/terraform/aws/metabase.tf 가 없습니다"
        )
        source = metabase.read_text()
        self.assertRegex(source, r'resource\s+"aws_security_group"\s+"metabase_alb"')
        # 443 은 SigNoz 와 같은 Cloudflare 대역 목록만 — 대역 자체는 metabase.tftest.hcl 이 대조한다.
        self.assertNotIn("0.0.0.0/0", source)
        variables = (root / "variables.tf").read_text()
        self.assertRegex(variables, r'variable\s+"metabase_certificate_arn"')
        outputs = (root / "outputs.tf").read_text()
        self.assertRegex(outputs, r'output\s+"metabase_alb_security_group_id"')
        self.assertRegex(outputs, r'output\s+"metabase_certificate_arn"')

    def test_module_bazel_pulls_pinned_amd64_image(self):
        source = (workspace() / "MODULE.bazel").read_text()
        block = re.search(
            r'oci\.pull\(\s*name\s*=\s*"mirror_metabase"\s*,(.*?)\)', source, re.DOTALL
        )
        self.assertIsNotNone(
            block, 'MODULE.bazel 에 oci.pull(name = "mirror_metabase") 가 없습니다'
        )
        body = block.group(1)
        self.assertRegex(body, rf'digest\s*=\s*"{re.escape(METABASE_DIGEST)}"')
        self.assertRegex(body, r'image\s*=\s*"(?:[a-z0-9.-]+/)?metabase/metabase"')
        self.assertRegex(body, r'platforms\s*=\s*\[\s*"linux/amd64"\s*,?\s*\]')
        self.assertRegex(source, r'use_repo\(oci,[^)]*"mirror_metabase_linux_amd64"')

    def test_helm_metabase_build_defines_mirror_push(self):
        path = workspace() / "platform/helm/metabase/BUILD.bazel"
        self.assertTrue(
            path.exists(),
            "platform/helm/metabase/BUILD.bazel 이 runfiles 에 없습니다 — "
            "//tools/aws:unit_test data 에 //platform/helm/metabase:BUILD.bazel 을 더하세요",
        )
        source = path.read_text()
        self.assertIn("oci_push(", source)
        explicit = re.search(r'name\s*=\s*"mirror_metabase_push"', source)
        generated = re.search(r'name\s*=\s*key\s*\+\s*"_push"', source) and re.search(
            r'"mirror_metabase"', source
        )
        self.assertTrue(explicit or generated, "mirror_metabase_push 대상이 없습니다")
        self.assertTrue(
            re.search(r'"@mirror_metabase_linux_amd64"', source)
            or (
                generated
                and re.search(
                    r'image\s*=\s*"@"\s*\+\s*key\s*\+\s*"_linux_amd64"', source
                )
            ),
            "push 이미지는 @mirror_metabase_linux_amd64 여야 합니다",
        )


# --- B. 이미지 복사 ------------------------------------------------------------------------------------------


class MetabasePublishTest(unittest.TestCase):
    def outputs(self, settings):
        from tools.aws.tests.test_signoz import app_urls

        return {"ecr_repository_urls": app_urls(settings) | mirror_urls(settings)}

    def publish(self, outputs, settings, listed=None):
        from tools.aws.tests.test_signoz import SignozPublishTest

        return SignozPublishTest.publish(self, outputs, settings, listed)

    def test_dev_mirrors_missing_tag_into_dev_repository(self):
        settings = valid_settings()
        outputs = self.outputs(settings)
        run, docker = self.publish(outputs, settings)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertIn(
            [
                "aws",
                "ecr",
                "list-images",
                "--repository-name",
                f"scenetrip-dev/{METABASE_REPOSITORY}",
                "--filter",
                "tagStatus=TAGGED",
                "--output",
                "json",
            ],
            commands,
        )
        pushes = [
            call
            for call in run.call_args_list
            if call.args[0][:3] == ["just", "run", METABASE_PUSH]
        ]
        self.assertEqual(len(pushes), 1)
        self.assertEqual(
            pushes[0].args[0],
            [
                "just",
                "run",
                METABASE_PUSH,
                "--",
                "--repository",
                outputs["ecr_repository_urls"]["mirror_metabase"],
                "--tag",
                METABASE_TAG,
            ],
        )
        self.assertEqual(pushes[0].kwargs["env"], {"DOCKER_CONFIG": docker})

    def test_existing_tag_is_not_pushed_again_and_prd_never_mirrors(self):
        settings = valid_settings()
        name = f"scenetrip-dev/{METABASE_REPOSITORY}"
        # 다른 태그만 있으면 아직 복사하지 않은 것이다 — 이 경우는 위 시험이 본다. 같은 태그가 있으면 올리지 않는다.
        run, _unused = self.publish(
            self.outputs(settings), settings, {name: [METABASE_TAG, "other"]}
        )
        self.assertIn(
            name,
            [
                command[command.index("--repository-name") + 1]
                for command in (call.args[0] for call in run.call_args_list)
                if "list-images" in command
            ],
        )
        self.assertFalse(
            any(METABASE_PUSH in call.args[0] for call in run.call_args_list)
        )
        # PRD 는 METABASE_ENVIRONMENTS 밖 — 목록도 복사도 하지 않는다.
        prd = prd_settings()
        run, _unused = self.publish(self.outputs(prd), prd)
        for call in run.call_args_list:
            joined = " ".join(call.args[0])
            self.assertNotIn("mirror/metabase", joined)
            self.assertNotIn("//platform/helm/metabase:", joined)


# --- C. 배포 ------------------------------------------------------------------------------------------------


class MetabaseDeployTest(unittest.TestCase):
    def test_first_deploy_generates_secret_once_and_reuses_it(self):
        settings = valid_settings()
        cloud = Cloud()
        run_deploy(settings, deploy_outputs(settings), cloud)
        puts = cloud.metabase_puts()
        self.assertEqual(len(puts), 1, "Metabase 칸은 처음 한 번만 저장해야 합니다")
        stored = json.loads(puts[0]["SecretString"])
        self.assertEqual(set(stored), METABASE_KEYS)
        self.assertEqual(len(set(stored.values())), 3, "세 값은 서로 달라야 합니다")
        for value in stored.values():
            self.assertIsInstance(value, str)
            self.assertTrue(value.strip())
        # Metabase 는 연결 암호화 키로 16 자 이상을 요구한다.
        self.assertGreaterEqual(len(stored["MB_ENCRYPTION_SECRET_KEY"]), 16)

        again = run_deploy(settings, deploy_outputs(settings), cloud)
        self.assertEqual(
            len(cloud.metabase_puts()), 1, "있는 칸은 다시 쓴다 — 새로 만들지 않는다"
        )
        self.assertEqual(cloud.stored_metabase(), stored)
        secret = self.metabase_secret(again)
        self.assertEqual(secret["MB_DB_PASS"], stored["METABASE_APP_PASSWORD"])
        self.assertEqual(
            secret["MB_ENCRYPTION_SECRET_KEY"], stored["MB_ENCRYPTION_SECRET_KEY"]
        )

    def test_stored_secret_is_used_without_writing(self):
        settings = valid_settings()
        cloud = Cloud(STORED_METABASE)
        run = run_deploy(settings, deploy_outputs(settings), cloud)
        self.assertEqual(cloud.metabase_puts(), [])
        secret = self.metabase_secret(run)
        self.assertEqual(secret["MB_DB_PASS"], STORED_METABASE["METABASE_APP_PASSWORD"])

    def test_bootstrap_transient_secret_carries_metabase_passwords_only_in_metabase_environments(
        self,
    ):
        settings = valid_settings()
        run = run_deploy(settings, deploy_outputs(settings), Cloud(STORED_METABASE))
        transient = transient_admin_secret(run)
        self.assertIn("METABASE_RO_PASSWORD", transient)
        self.assertIn("METABASE_APP_PASSWORD", transient)
        self.assertEqual(
            transient["METABASE_RO_PASSWORD"], STORED_METABASE["METABASE_RO_PASSWORD"]
        )
        self.assertEqual(
            transient["METABASE_APP_PASSWORD"], STORED_METABASE["METABASE_APP_PASSWORD"]
        )
        # 원래 키는 그대로다.
        self.assertEqual(transient["PGPASSWORD"], MASTER_PASSWORD)
        self.assertIn("RUNTIME_PASSWORD", transient)
        self.assertIn("MIGRATION_PASSWORD", transient)
        self.assertNotIn("MB_ENCRYPTION_SECRET_KEY", transient)
        self.assertNotIn(
            STORED_METABASE["MB_ENCRYPTION_SECRET_KEY"], json.dumps(transient)
        )

        # Metabase 를 올리지 않는 환경은 bootstrap 이 계정 만들기를 건너뛰게 키를 넣지 않는다.
        for label, env_settings, extra in (
            ("prd", prd_settings(), ()),
            (
                "dev-off",
                settings,
                (patch("tools.aws.deploy.METABASE_ENVIRONMENTS", ()),),
            ),
        ):
            with self.subTest(case=label):
                cloud = Cloud(STORED_METABASE)
                other = run_deploy(
                    env_settings, deploy_outputs(env_settings), cloud, extra
                )
                transient = transient_admin_secret(other)
                self.assertNotIn("METABASE_RO_PASSWORD", transient)
                self.assertNotIn("METABASE_APP_PASSWORD", transient)
                self.assertFalse(
                    any(is_metabase_helm(call.args[0]) for call in other.call_args_list)
                )
                self.assertFalse(
                    any(
                        cloud.metabase_arn in " ".join(command)
                        for command, _ in cloud.calls
                    )
                )

    def test_install_happens_after_database_migration(self):
        settings = valid_settings()
        run = run_deploy(settings, deploy_outputs(settings), Cloud(STORED_METABASE))
        helm = run.call_args_list.index(metabase_helm(run))
        jobs = [
            i
            for i, call in enumerate(run.call_args_list)
            if call.args[0] == KUBECTL_APPLY
            and json.loads(call.kwargs["stdin"]).get("kind") == "Job"
            and json.loads(call.kwargs["stdin"])["metadata"]["name"]
            in {"database-bootstrap", "database-migrate", "database-finalize"}
        ]
        self.assertEqual(len(jobs), 3)
        self.assertLess(max(jobs), helm)
        # Metabase 쪽 Kubernetes 변경(네임스페이스·Secret)도 마이그레이션 뒤다.
        metabase_applies = [
            i
            for i, call in enumerate(run.call_args_list)
            if call.args[0] == KUBECTL_APPLY
            and "metabase"
            in json.dumps(json.loads(call.kwargs["stdin"]).get("metadata", {}))
        ]
        self.assertTrue(metabase_applies)
        self.assertLess(max(jobs), min(metabase_applies))

    def test_namespace_is_restricted(self):
        settings = valid_settings()
        run = run_deploy(settings, deploy_outputs(settings), Cloud(STORED_METABASE))
        namespace = [
            m
            for m in applied(run)
            if m.get("kind") == "Namespace" and m["metadata"]["name"] == "metabase"
        ]
        self.assertEqual(len(namespace), 1)
        labels = namespace[0]["metadata"]["labels"]
        self.assertEqual(labels["pod-security.kubernetes.io/enforce"], "restricted")

    @staticmethod
    def metabase_secret(run):
        found = [
            m
            for m in applied(run)
            if m.get("kind") == "Secret"
            and m["metadata"].get("name") == "metabase-secrets"
            and m["metadata"].get("namespace") == "metabase"
        ]
        if len(found) != 1:
            raise AssertionError(
                f"metabase/metabase-secrets 가 {len(found)} 번 적용됐습니다"
            )
        return secret_data(found[0])

    def test_secret_holds_db_password_and_encryption_key_via_stdin_only(self):
        settings = valid_settings()
        run = run_deploy(settings, deploy_outputs(settings), Cloud(STORED_METABASE))
        secret = self.metabase_secret(run)
        self.assertEqual(set(secret), {"MB_DB_PASS", "MB_ENCRYPTION_SECRET_KEY"})
        self.assertEqual(secret["MB_DB_PASS"], STORED_METABASE["METABASE_APP_PASSWORD"])
        self.assertEqual(
            secret["MB_ENCRYPTION_SECRET_KEY"],
            STORED_METABASE["MB_ENCRYPTION_SECRET_KEY"],
        )
        # 읽기 전용 계정 암호는 Metabase 파드에 가지 않는다(사람이 첫 화면에서 연결할 때 Secrets Manager 에서 꺼낸다).
        self.assertNotIn(STORED_METABASE["METABASE_RO_PASSWORD"], json.dumps(secret))
        for call in run.call_args_list:
            joined = " ".join(call.args[0])
            for value in STORED_METABASE.values():
                self.assertNotIn(value, joined)
        helm = metabase_helm(run)
        for value in STORED_METABASE.values():
            self.assertNotIn(value, helm.kwargs["stdin"])

    def test_helm_command_shape(self):
        settings = valid_settings()
        run = run_deploy(settings, deploy_outputs(settings), Cloud(STORED_METABASE))
        command = metabase_helm(run).args[0]
        self.assertEqual(
            command[:5],
            [
                "helm",
                "upgrade",
                "--install",
                "metabase",
                str(REPO / "platform/helm/metabase"),
            ],
        )
        pairs = list(itertools.pairwise(command))
        self.assertIn(("--namespace", "metabase"), pairs)
        self.assertIn(("--values", "-"), pairs)
        self.assertIn("--atomic", command)
        self.assertIn("--wait", command)

    def test_helm_values_carry_image_database_site_dns_and_alb_cidrs(self):
        settings = valid_settings()
        outputs = deploy_outputs(settings)
        run = run_deploy(settings, outputs, Cloud(STORED_METABASE))
        values = json.loads(metabase_helm(run).kwargs["stdin"])
        strings = set(leaves(values))
        url = outputs["ecr_repository_urls"]["mirror_metabase"]
        self.assertEqual(
            url, f"{ecr_registry(settings)}/scenetrip-dev/{METABASE_REPOSITORY}"
        )
        self.assertTrue(
            any(item == url or item.startswith(url + ":") for item in strings),
            f"이미지 주소 {url} 가 values 에 없습니다: {values}",
        )
        self.assertTrue(
            any(
                item == METABASE_TAG or item.endswith(":" + METABASE_TAG)
                for item in strings
            ),
            f"이미지 태그 {METABASE_TAG} 가 values 에 없습니다: {values}",
        )
        self.assertIn(DATABASE_HOST, strings)
        self.assertTrue(
            any(item.endswith("metabase-dev.scenetrip.io") for item in strings),
            f"화면 주소가 values 에 없습니다: {values}",
        )
        self.assertIn(DNS_CIDR, strings)
        for cidr in outputs["public_subnet_cidrs"]:
            self.assertIn(cidr, strings)


# --- D. 배포 흐름의 화면 열기 ----------------------------------------------------------------------------------


class MetabaseExposeWiringTest(unittest.TestCase):
    """deploy() 가 Metabase 환경에서 마이그레이션 뒤 설치하고 화면을 연다. 그 밖의 환경에서는 둘 다 하지 않는다."""

    def deploy(self, settings, extra=()):
        marker = lambda name: lambda run, *unused, **kw: run(["test-marker", name])
        with (
            patch(
                "tools.aws.deploy.install_metabase", side_effect=marker("install")
            ) as install,
            patch(
                "tools.aws.deploy.expose_metabase", side_effect=marker("expose")
            ) as expose,
        ):
            run = run_deploy(
                settings,
                deploy_outputs(settings),
                Cloud(STORED_METABASE),
                (
                    patch(
                        "tools.aws.deploy.migrate_database",
                        side_effect=lambda run, *unused, **kw: run(
                            ["test-marker", "migrate"]
                        ),
                    ),
                    *extra,
                ),
            )
        order = [
            call.args[0][1]
            for call in run.call_args_list
            if call.args[0][0] == "test-marker"
        ]
        return run, order, install, expose

    def test_dev_installs_after_migration_then_exposes(self):
        settings = valid_settings()
        run, order, install, expose = self.deploy(settings)
        self.assertIn("migrate", order)
        self.assertLess(order.index("migrate"), order.index("install"))
        self.assertLess(order.index("install"), order.index("expose"))
        install.assert_called_once()
        expose.assert_called_once_with(run, settings, deploy_outputs(settings))

    def test_environment_outside_metabase_environments_never_installs(self):
        for label, settings, extra in (
            ("prd", prd_settings(), ()),
            (
                "dev-off",
                valid_settings(),
                (patch("tools.aws.deploy.METABASE_ENVIRONMENTS", ()),),
            ),
        ):
            with self.subTest(case=label):
                _run, order, install, expose = self.deploy(settings, extra)
                install.assert_not_called()
                expose.assert_not_called()
                self.assertNotIn("install", order)


# --- E. 화면(Cloudflare Access 뒤) ---------------------------------------------------------------------------


def ui_outputs(settings=None, **changes):
    settings = settings or valid_settings()
    return deploy_outputs(
        settings, **{"metabase_certificate_arn": METABASE_CERTIFICATE, **changes}
    )


def describe_certificate(status="ISSUED", names=("metabase-dev.scenetrip.io",)):
    return json.dumps(
        {
            "Certificate": {
                "CertificateArn": METABASE_CERTIFICATE,
                "Status": status,
                "SubjectAlternativeNames": list(names),
            }
        }
    )


class MetabaseIngressTest(unittest.TestCase):
    def manifests(self, settings=None, **changes):
        from tools.aws.deploy import metabase_ingress

        settings = settings or valid_settings()
        return metabase_ingress(settings, ui_outputs(settings, **changes))

    def test_three_manifests_in_apply_order(self):
        params, ingress_class, ingress = self.manifests()
        self.assertEqual(
            [params["kind"], ingress_class["kind"], ingress["kind"]],
            ["IngressClassParams", "IngressClass", "Ingress"],
        )

    def test_class_params_and_class(self):
        params, ingress_class, _ingress = self.manifests()
        self.assertEqual(params["metadata"]["name"], METABASE_ALB_NAME)
        spec = params["spec"]
        self.assertEqual(spec["scheme"], "internet-facing")
        self.assertEqual(
            spec["namespaceSelector"],
            {"matchLabels": {"kubernetes.io/metadata.name": "metabase"}},
        )
        self.assertEqual(spec["subnets"], {"ids": ui_outputs()["public_subnet_ids"]})
        self.assertEqual(spec["certificateARNs"], [METABASE_CERTIFICATE])
        self.assertEqual(ingress_class["metadata"]["name"], METABASE_ALB_NAME)
        self.assertEqual(ingress_class["spec"]["controller"], "eks.amazonaws.com/alb")
        self.assertEqual(
            ingress_class["spec"]["parameters"]["kind"], "IngressClassParams"
        )
        self.assertEqual(ingress_class["spec"]["parameters"]["name"], METABASE_ALB_NAME)

    def test_ingress_routes_dev_domain_to_metabase_3000(self):
        *_unused, ingress = self.manifests()
        self.assertEqual(ingress["metadata"]["name"], "metabase-ui")
        self.assertEqual(ingress["metadata"]["namespace"], "metabase")
        annotations = ingress["metadata"]["annotations"]
        prefix = "alb.ingress.kubernetes.io/"
        self.assertEqual(
            json.loads(annotations[prefix + "listen-ports"]), [{"HTTPS": 443}]
        )
        self.assertEqual(annotations[prefix + "certificate-arn"], METABASE_CERTIFICATE)
        self.assertIn("TLS13", annotations[prefix + "ssl-policy"])
        self.assertEqual(annotations[prefix + "target-type"], "ip")
        self.assertEqual(
            annotations[prefix + "security-groups"],
            ui_outputs()["metabase_alb_security_group_id"],
        )
        self.assertEqual(
            annotations[prefix + "manage-backend-security-group-rules"], "false"
        )
        self.assertEqual(annotations[prefix + "healthcheck-path"], "/api/health")
        spec = ingress["spec"]
        self.assertEqual(spec["ingressClassName"], METABASE_ALB_NAME)
        self.assertEqual(len(spec["rules"]), 1)
        rule = spec["rules"][0]
        self.assertEqual(rule["host"], "metabase-dev.scenetrip.io")
        self.assertEqual(
            rule["http"]["paths"],
            [
                {
                    "path": "/",
                    "pathType": "Prefix",
                    "backend": {
                        "service": {"name": "metabase", "port": {"number": 3000}}
                    },
                }
            ],
        )

    def test_prd_uses_prd_names(self):
        params, _class, ingress = self.manifests(prd_settings())
        self.assertEqual(params["metadata"]["name"], "scenetrip-prd-metabase-alb")
        self.assertEqual(
            ingress["spec"]["ingressClassName"], "scenetrip-prd-metabase-alb"
        )
        self.assertEqual(ingress["spec"]["rules"][0]["host"], "metabase.scenetrip.io")

    def test_rejects_malformed_security_group(self):
        for value in ("sg-malicious;", "", "sg-FFFFFFFF"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.manifests(metabase_alb_security_group_id=value)


class ExposeMetabaseTest(unittest.TestCase):
    def expose(self, outputs, run):
        from tools.aws.deploy import expose_metabase

        with (
            patch(
                "tools.aws.deploy.wait_for_metabase_alb", return_value=METABASE_ALB_HOST
            ) as waiting,
            patch("builtins.print"),
        ):
            result = expose_metabase(run, valid_settings(), outputs)
        return result, waiting

    def test_without_certificate_nothing_runs(self):
        run = Mock()
        result, waiting = self.expose(ui_outputs(metabase_certificate_arn=""), run)
        self.assertIsNone(result)
        run.assert_not_called()
        waiting.assert_not_called()

    def test_applies_three_manifests_then_waits_for_alb(self):
        from tools.aws.deploy import metabase_ingress

        outputs = ui_outputs()
        run = Mock(
            side_effect=lambda command, **kw: (
                describe_certificate() if "describe-certificate" in command else ""
            )
        )
        result, waiting = self.expose(outputs, run)
        self.assertEqual(result, METABASE_ALB_HOST)
        applies = [call for call in run.call_args_list if call.args[0] == KUBECTL_APPLY]
        self.assertEqual(
            [json.loads(call.kwargs["stdin"]) for call in applies],
            metabase_ingress(valid_settings(), outputs),
        )
        waiting.assert_called_once_with(run, valid_settings(), outputs)

    def test_certificate_not_issued_or_not_covering_domain_stops_before_apply(self):
        # SigNoz 화면과 같은 모양 — 인증서가 화면 주소를 덮는지 먼저 본다.
        from tools.aws.deploy import expose_metabase

        for answer in (
            describe_certificate(status="PENDING_VALIDATION"),
            describe_certificate(names=("metabase.scenetrip.io",)),
            describe_certificate(names=("signoz-dev.scenetrip.io",)),
        ):
            with self.subTest(answer=answer):
                run = Mock(side_effect=lambda command, answer=answer, **kw: answer)
                with (
                    patch("tools.aws.deploy.wait_for_metabase_alb") as waiting,
                    self.assertRaises(ValueError),
                ):
                    expose_metabase(run, valid_settings(), ui_outputs())
                self.assertNotIn(
                    KUBECTL_APPLY, [call.args[0] for call in run.call_args_list]
                )
                waiting.assert_not_called()


def metabase_load_balancer():
    from tools.aws.tests.test_alb import alb_outputs, load_balancer

    return {
        **load_balancer(),
        "DNSName": METABASE_ALB_HOST,
        "SecurityGroups": [alb_outputs()["metabase_alb_security_group_id"]],
        "LoadBalancerArn": "arn:aws:elasticloadbalancing:ap-northeast-2:123456789012:loadbalancer/app/metabase/789",
    }


class MetabaseAlbTest(unittest.TestCase):
    """alb.wait_for_metabase_alb — Ingress metabase/metabase-ui 의 ALB 가 active 이고 대상이 healthy 일 때까지."""

    def test_waits_until_targets_healthy(self):
        from tools.aws.alb import wait_for_metabase_alb
        from tools.aws.tests.test_alb import alb_outputs, ip_target_group

        def target_health(state):
            # Metabase 대상은 Ingress 백엔드 metabase:3000 의 파드 포트다.
            return {
                "TargetHealthDescriptions": [
                    {
                        "Target": {"Id": "10.40.26.215", "Port": 3000},
                        "TargetHealth": {"State": state},
                    }
                ]
            }

        address = json.dumps(
            {"status": {"loadBalancer": {"ingress": [{"hostname": METABASE_ALB_HOST}]}}}
        )
        run = Mock(
            side_effect=[
                json.dumps({"status": {}}),
                address,
                json.dumps({"LoadBalancers": [metabase_load_balancer()]}),
                json.dumps({"TargetGroups": [ip_target_group()]}),
                json.dumps(target_health("initial")),
                address,
                json.dumps({"LoadBalancers": [metabase_load_balancer()]}),
                json.dumps({"TargetGroups": [ip_target_group()]}),
                json.dumps(target_health("healthy")),
            ]
        )
        with patch("tools.aws.alb.time.sleep") as sleep:
            self.assertEqual(
                wait_for_metabase_alb(run, valid_settings(), alb_outputs(), timeout=30),
                METABASE_ALB_HOST,
            )
        self.assertEqual(sleep.call_count, 2)
        reads = [
            call.args[0] for call in run.call_args_list if call.args[0][0] == "kubectl"
        ]
        # 주소 없음 → 주소 있음(대상 initial) → 주소 있음(대상 healthy) — 세 번 읽는다.
        self.assertEqual(len(reads), 3)
        for read in reads:
            self.assertEqual(
                read[:6], ["kubectl", "-n", "metabase", "get", "ingress", "metabase-ui"]
            )

    def test_rejects_other_security_group_on_metabase_address(self):
        from tools.aws.alb import wait_for_metabase_alb
        from tools.aws.tests.test_alb import alb_outputs, signoz_load_balancer

        for balancer in (
            signoz_load_balancer(),
            {
                **metabase_load_balancer(),
                "SecurityGroups": [alb_outputs()["alb_security_group_id"]],
            },
        ):
            run = Mock(
                side_effect=[
                    json.dumps(
                        {
                            "status": {
                                "loadBalancer": {
                                    "ingress": [{"hostname": balancer["DNSName"]}]
                                }
                            }
                        }
                    ),
                    json.dumps({"LoadBalancers": [balancer]}),
                ]
            )
            with (
                self.subTest(groups=balancer["SecurityGroups"]),
                self.assertRaises(ValueError),
            ):
                wait_for_metabase_alb(run, valid_settings(), alb_outputs(), timeout=30)

    def test_times_out_without_address(self):
        from tools.aws.alb import wait_for_metabase_alb
        from tools.aws.tests.test_alb import alb_outputs

        run = Mock(return_value=json.dumps({"status": {}}))
        with (
            patch("tools.aws.alb.time.monotonic", side_effect=[0, 0, 2]),
            patch("tools.aws.alb.time.sleep"),
            self.assertRaises(TimeoutError),
        ):
            wait_for_metabase_alb(run, valid_settings(), alb_outputs(), timeout=1)


# --- F. 차트(platform/helm/metabase, 저장소 소유) ------------------------------------------------------------


class MetabaseChartTest(unittest.TestCase):
    """배포가 helm 에 넘기는 values 그대로 차트를 그려 본다 — values 의 키 이름은 구현이 정한다."""

    rendered: ClassVar[list] = []
    values: ClassVar[dict] = {}
    outputs: ClassVar[dict] = {}
    failure: ClassVar[str] = ""

    @classmethod
    def setUpClass(cls):
        try:
            settings = valid_settings()
            cls.outputs = deploy_outputs(settings)
            run = run_deploy(settings, cls.outputs, Cloud(STORED_METABASE))
            cls.values = json.loads(metabase_helm(run).kwargs["stdin"])
            chart = workspace() / "platform/helm/metabase"
            if not (chart / "Chart.yaml").is_file():
                raise AssertionError(
                    "platform/helm/metabase/Chart.yaml 이 runfiles 에 없습니다 — 차트를 만들고 "
                    "//tools/aws:unit_test data 에 차트 파일을 더하세요"
                )
            process = subprocess.run(
                [
                    str(workspace() / "tools/bazel/cloud/helm"),
                    "template",
                    "metabase",
                    str(chart),
                    "--namespace",
                    "metabase",
                    "--values",
                    "-",
                ],
                input=json.dumps(cls.values),
                text=True,
                capture_output=True,
                check=False,
            )
            if process.returncode:
                raise AssertionError(f"helm template 실패: {process.stderr}")
            cls.rendered = to_objects(process.stdout)
        except Exception as error:  # noqa: BLE001 — 각 시험이 같은 이유로 실패하게 남긴다.
            cls.failure = f"{type(error).__name__}: {error}"

    def setUp(self):
        if self.failure:
            self.fail(self.failure)

    def kind(self, kind):
        return [item for item in self.rendered if item.get("kind") == kind]

    def deployment(self):
        found = self.kind("Deployment")
        self.assertEqual(len(found), 1, "Deployment 는 하나여야 합니다")
        return found[0]

    def container(self):
        containers = self.deployment()["spec"]["template"]["spec"]["containers"]
        self.assertEqual(len(containers), 1)
        return containers[0]

    def test_single_recreate_deployment_with_mirrored_image(self):
        spec = self.deployment()["spec"]
        self.assertEqual(spec.get("replicas", 1), 1)
        self.assertEqual(spec["strategy"]["type"], "Recreate")
        url = self.outputs["ecr_repository_urls"]["mirror_metabase"]
        self.assertRegex(
            self.container()["image"],
            rf"^{re.escape(url)}:{re.escape(METABASE_TAG)}(@sha256:[0-9a-f]{{64}})?$",
        )

    def test_security_context_is_restricted(self):
        pod = self.deployment()["spec"]["template"]["spec"]
        container = self.container()
        pod_context = pod.get("securityContext", {})
        context = container.get("securityContext", {})

        def either(key):
            return context.get(key, pod_context.get(key))

        self.assertEqual(either("runAsUser"), 2000)
        self.assertIs(either("runAsNonRoot"), True)
        self.assertIs(context.get("allowPrivilegeEscalation"), False)
        self.assertEqual(context.get("capabilities", {}).get("drop"), ["ALL"])
        self.assertEqual((either("seccompProfile") or {}).get("type"), "RuntimeDefault")
        self.assertIs(context.get("readOnlyRootFilesystem"), True)

    def test_tmp_is_an_empty_dir(self):
        pod = self.deployment()["spec"]["template"]["spec"]
        mounts = {
            m["mountPath"]: m["name"] for m in self.container().get("volumeMounts", [])
        }
        self.assertIn("/tmp", mounts)
        volume = next(v for v in pod.get("volumes", []) if v["name"] == mounts["/tmp"])
        self.assertIn("emptyDir", volume)

    def env(self):
        return {item["name"]: item for item in self.container().get("env", [])}

    def test_environment_variables(self):
        env = self.env()
        literal = {name: item.get("value") for name, item in env.items()}
        self.assertEqual(literal.get("MB_DB_TYPE"), "postgres")
        self.assertEqual(literal.get("MB_DB_USER"), "metabase_app")
        self.assertEqual(literal.get("MB_JETTY_PORT"), "3000")
        self.assertEqual(
            literal.get("MB_SITE_URL"), "https://metabase-dev.scenetrip.io"
        )
        self.assertEqual(literal.get("MB_PLUGINS_DIR"), "/tmp/plugins")
        uri = literal.get("MB_DB_CONNECTION_URI") or ""
        self.assertRegex(
            uri,
            rf"^(jdbc:)?postgres(ql)?://{re.escape(DATABASE_HOST)}:5432/metabase\?(.*&)?sslmode=require(&.*)?$",
        )
        self.assertNotIn("@", uri)
        self.assertNotIn("password", uri.lower())

    def test_secret_values_come_from_metabase_secrets(self):
        env = self.env()
        sources = self.container().get("envFrom", [])
        from_secret = any(
            item.get("secretRef", {}).get("name") == "metabase-secrets"
            for item in sources
        )
        for key in ("MB_DB_PASS", "MB_ENCRYPTION_SECRET_KEY"):
            with self.subTest(key=key):
                if key in env:
                    self.assertNotIn("value", env[key])
                    self.assertEqual(
                        env[key]["valueFrom"]["secretKeyRef"],
                        {"name": "metabase-secrets", "key": key},
                    )
                else:
                    self.assertTrue(
                        from_secret, f"{key} 가 metabase-secrets 에서 오지 않습니다"
                    )
        rendered = json.dumps(self.rendered)
        for value in STORED_METABASE.values():
            self.assertNotIn(value, rendered)

    def test_resources(self):
        resources = self.container()["resources"]
        self.assertEqual(resources["requests"]["cpu"], "250m")
        self.assertEqual(resources["requests"]["memory"], "1Gi")
        self.assertEqual(resources["limits"]["memory"], "2Gi")

    def test_probes_hit_api_health(self):
        container = self.container()
        port_names = {
            p.get("name"): p["containerPort"] for p in container.get("ports", [])
        }
        self.assertIn(3000, port_names.values())
        for probe in ("startupProbe", "readinessProbe", "livenessProbe"):
            with self.subTest(probe=probe):
                http = container[probe]["httpGet"]
                self.assertEqual(http["path"], "/api/health")
                port = http["port"]
                self.assertEqual(port_names.get(port, port), 3000)

    def test_service_is_cluster_ip_on_3000(self):
        services = [
            s for s in self.kind("Service") if s["metadata"]["name"] == "metabase"
        ]
        self.assertEqual(len(services), 1)
        spec = services[0]["spec"]
        self.assertEqual(spec.get("type", "ClusterIP"), "ClusterIP")
        self.assertEqual([p["port"] for p in spec["ports"]], [3000])

    def policies(self):
        """Metabase 파드에 걸리는 NetworkPolicy(빈 선택자 포함)."""
        labels = self.deployment()["spec"]["template"]["metadata"]["labels"]
        found = []
        for policy in self.kind("NetworkPolicy"):
            selector = policy["spec"].get("podSelector", {})
            match = selector.get("matchLabels", {})
            if not selector.get("matchExpressions") and all(
                labels.get(k) == v for k, v in match.items()
            ):
                found.append(policy)
        self.assertTrue(found, "Metabase 파드에 걸리는 NetworkPolicy 가 없습니다")
        return found

    def test_default_deny(self):
        deny = [
            p
            for p in self.policies()
            if {"Ingress", "Egress"} <= set(p["spec"].get("policyTypes", []))
            and not p["spec"].get("ingress")
            and not p["spec"].get("egress")
        ]
        self.assertTrue(
            deny, "기본 거부(Ingress·Egress, 규칙 없음) NetworkPolicy 가 없습니다"
        )

    @staticmethod
    def ports(rule):
        return {
            (p.get("protocol", "TCP"), p.get("port")) for p in rule.get("ports", [])
        }

    def test_egress_only_dns_and_postgres(self):
        rules = [r for p in self.policies() for r in p["spec"].get("egress", []) or []]
        self.assertTrue(rules)
        allowed = {("UDP", 53), ("TCP", 53), ("TCP", 5432)}
        for rule in rules:
            with self.subTest(rule=rule):
                self.assertTrue(
                    rule.get("ports"), "포트 없는 나감 규칙은 전부 허용입니다"
                )
                self.assertLessEqual(self.ports(rule), allowed)
        dns = [r for r in rules if ("UDP", 53) in self.ports(r)]
        self.assertTrue(dns, "DNS(53) 나감이 없습니다")
        self.assertTrue(
            any(
                DNS_CIDR
                in [peer.get("ipBlock", {}).get("cidr") for peer in r.get("to", [])]
                for r in dns
            ),
            "DNS 나감은 배포가 넘긴 DNS CIDR 로 가야 합니다",
        )
        self.assertTrue(
            any(("TCP", 5432) in self.ports(r) for r in rules), "5432 나감이 없습니다"
        )

    def test_ingress_only_3000_from_alb_subnets(self):
        rules = [r for p in self.policies() for r in p["spec"].get("ingress", []) or []]
        self.assertTrue(rules, "3000 들어옴 규칙이 없습니다")
        expected = set(self.outputs["public_subnet_cidrs"])
        for rule in rules:
            with self.subTest(rule=rule):
                self.assertEqual(self.ports(rule), {("TCP", 3000)})
                peers = rule.get("from", [])
                self.assertTrue(peers, "출처 없는 들어옴 규칙은 전부 허용입니다")
                self.assertEqual(
                    {peer.get("ipBlock", {}).get("cidr") for peer in peers}, expected
                )
                for peer in peers:
                    self.assertEqual(set(peer), {"ipBlock"})
                    self.assertFalse(peer["ipBlock"].get("except"))


def to_objects(rendered):
    """helm 이 그린 YAML 을 Bazel 의 kubectl 로 JSON 객체 목록으로 바꾼다(서버 없이, --local)."""
    marker = "scenetrip.test/rendered"
    with tempfile.TemporaryDirectory() as home:
        process = subprocess.run(
            [
                str(workspace() / "tools/bazel/cloud/kubectl"),
                "annotate",
                "--local",
                "-f",
                "-",
                f"{marker}=1",
                "-o",
                "json",
            ],
            input=rendered,
            text=True,
            capture_output=True,
            check=False,
            env={**os.environ, "KUBECONFIG": os.devnull, "HOME": home},
        )
    if process.returncode:
        raise AssertionError(f"kubectl 변환 실패: {process.stderr}")
    decoder = json.JSONDecoder()
    objects, index, text = [], 0, process.stdout
    while True:
        while index < len(text) and text[index].isspace():
            index += 1
        if index >= len(text):
            break
        item, index = decoder.raw_decode(text, index)
        items = item.get("items", []) if item.get("kind") == "List" else [item]
        for entry in items:
            annotations = entry.get("metadata", {}).get("annotations", {})
            annotations.pop(marker, None)
            if not annotations:
                entry.get("metadata", {}).pop("annotations", None)
            objects.append(entry)
    return objects


# --- G. DNS(lifecycle up) -----------------------------------------------------------------------------------


class MetabaseDnsTest(unittest.TestCase):
    def test_up_points_metabase_after_signoz_with_proxy_only_in_metabase_environments(
        self,
    ):
        from tools.aws.lifecycle import up
        from tools.aws.tests.test_lifecycle import (
            ALB,
            METABASE_ALB,
            PRD_TARGET,
            SIGNOZ_ALB,
            TARGET,
            FakeAws,
            FakeGitHub,
            OrderedCloudflare,
            github_for,
        )

        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = OrderedCloudflare(github)
        aws = FakeAws(signoz=True, metabase=True)
        with patch("builtins.print"):
            up(aws, github_for(github), cloudflare, TARGET)
        self.assertEqual(
            cloudflare.pointed,
            [
                ("api-dev.example.com", ALB, "ap-northeast-2"),
                ("signoz-dev.scenetrip.io", SIGNOZ_ALB, "ap-northeast-2"),
                ("metabase-dev.scenetrip.io", METABASE_ALB, "ap-northeast-2"),
            ],
        )
        self.assertEqual(cloudflare.proxied, [False, True, True])
        # data-load 는 화면 주소를 바꾼 다음이다.
        self.assertEqual(cloudflare.dispatched_before, [1, 1, 1])
        self.assertTrue(
            any(
                "Name=group-name,Values=scenetrip-dev-metabase-alb" in call
                for call in aws.calls
            )
        )

        # PRD 는 METABASE_ENVIRONMENTS 밖 — 이미 떠 있는 PRD 는 사람이 넣는다.
        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = OrderedCloudflare(github)
        prd = FakeAws(signoz=True, metabase=True)
        with patch("builtins.print"):
            up(prd, github_for(github), cloudflare, PRD_TARGET)
        self.assertNotIn(
            "metabase.scenetrip.io", [domain for domain, *_ in cloudflare.pointed]
        )
        self.assertFalse(any("-metabase-alb" in " ".join(call) for call in prd.calls))

    def test_up_without_metabase_alb_changes_nothing_and_still_loads_data(self):
        from tools.aws.lifecycle import up
        from tools.aws.tests.test_lifecycle import (
            TARGET,
            FakeAws,
            FakeCloudflare,
            FakeGitHub,
            github_for,
        )

        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = FakeCloudflare()
        aws = FakeAws(signoz=True, metabase=False)
        with patch("builtins.print"):
            up(aws, github_for(github), cloudflare, TARGET)
        self.assertEqual(len(cloudflare.pointed), 2)
        self.assertEqual(
            [inputs["operation"] for _, inputs in github.dispatched],
            ["apply", "data-load"],
        )
        # Metabase ALB 를 찾아는 봤다.
        self.assertTrue(
            any(
                "Name=group-name,Values=scenetrip-dev-metabase-alb" in call
                for call in aws.calls
            )
        )


# --- H. 내리기 ----------------------------------------------------------------------------------------------

METABASE_SG = "sg-fffffffffffffffff"


def metabase_state():
    from tools.aws.tests.test_destroy_gateway import signoz_state

    return {**signoz_state(), "aws_security_group.metabase_alb": {"id": METABASE_SG}}


def listing(*balancers):
    return Mock(return_value=json.dumps({"LoadBalancers": list(balancers)}))


def metabase_ingress_object():
    return {
        "metadata": {"name": "metabase-ui", "namespace": "metabase"},
        "status": {"loadBalancer": {"ingress": [{"hostname": METABASE_ALB_HOST}]}},
    }


class MetabaseTeardownTest(unittest.TestCase):
    def test_metabase_alb_is_recognized_three_allowed_fourth_rejected(self):
        from tools.aws.destroy_gateway import matching_albs, validate_alb
        from tools.aws.tests.test_alb import load_balancer, signoz_load_balancer

        settings = valid_settings()
        validate_alb(metabase_load_balancer(), settings, metabase_state())
        everything = [load_balancer(), signoz_load_balancer(), metabase_load_balancer()]
        self.assertEqual(
            matching_albs(listing(*everything), settings, metabase_state()), everything
        )

        # 거꾸로 막는 쪽 — state 에 Metabase 보안 그룹이 없으면 남의 ALB, 넷째 ALB·같은 그룹 둘은 거절.
        from tools.aws.tests.test_destroy_gateway import signoz_state

        with self.assertRaises(ValueError):
            validate_alb(metabase_load_balancer(), settings, signoz_state())
        fourth = {**metabase_load_balancer(), "DNSName": "fourth.elb.amazonaws.com"}
        with self.assertRaisesRegex(ValueError, "많은"):
            matching_albs(
                listing(
                    load_balancer(),
                    signoz_load_balancer(),
                    metabase_load_balancer(),
                    fourth,
                ),
                settings,
                metabase_state(),
            )
        with self.assertRaises(ValueError):
            matching_albs(
                listing(metabase_load_balancer(), fourth), settings, metabase_state()
            )

    def test_metabase_ingress_read_and_delete_commands(self):
        from tools.aws.destroy_gateway import (
            delete_metabase_ingress,
            get_metabase_ingress,
        )

        run = Mock(return_value="")
        self.assertIsNone(get_metabase_ingress(run))
        read = run.call_args.args[0]
        self.assertEqual(
            read[:6], ["kubectl", "-n", "metabase", "get", "ingress", "metabase-ui"]
        )
        self.assertIn("--ignore-not-found", read)
        self.assertEqual(
            get_metabase_ingress(
                Mock(return_value=json.dumps(metabase_ingress_object()))
            ),
            metabase_ingress_object(),
        )
        run = Mock(return_value="")
        delete_metabase_ingress(run)
        run.assert_called_once()
        delete = run.call_args.args[0]
        self.assertEqual(
            delete[:6],
            ["kubectl", "-n", "metabase", "delete", "ingress", "metabase-ui"],
        )
        self.assertIn("--ignore-not-found", delete)

    def test_remove_deletes_metabase_ingress_before_waiting_then_uninstalls(self):
        from tools.aws.destroy_gateway import remove_gateway
        from tools.aws.tests.test_alb import load_balancer
        from tools.aws.tests.test_destroy_gateway import cluster, ingress

        def execute(command, **unused):
            if command[:2] == ["helm", "list"]:
                return json.dumps([{"name": "scenetrip", "namespace": "scenetrip"}])
            return ""

        run = Mock(side_effect=execute)
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch(
                "tools.aws.destroy_gateway.matching_albs",
                return_value=[load_balancer(), metabase_load_balancer()],
            ),
            patch("tools.aws.destroy_gateway.get_ingress", return_value=ingress()),
            patch(
                "tools.aws.destroy_gateway.wait_gateway_absent",
                side_effect=lambda *unused, **kw: run(["wait_gateway_absent"]),
            ),
            patch("tools.aws.destroy_gateway.remove_signoz"),
            patch(
                "tools.aws.destroy_gateway.remove_metabase",
                side_effect=lambda *unused, **kw: run(["remove_metabase"]),
            ) as remove_metabase,
        ):
            remove_gateway(run, valid_settings(), metabase_state())
        commands = [entry.args[0] for entry in run.call_args_list]
        delete = [
            i
            for i, command in enumerate(commands)
            if command[:6]
            == ["kubectl", "-n", "metabase", "delete", "ingress", "metabase-ui"]
        ]
        self.assertEqual(len(delete), 1)
        wait = commands.index(["wait_gateway_absent"])
        self.assertLess(delete[0], wait)
        remove_metabase.assert_called_once()
        self.assertLess(wait, commands.index(["remove_metabase"]))

    def test_remove_without_scenetrip_release_still_cleans_metabase(self):
        from tools.aws.destroy_gateway import remove_gateway
        from tools.aws.tests.test_destroy_gateway import cluster

        run = Mock(return_value="[]")
        with (
            patch("tools.aws.destroy_gateway.live_cluster", return_value=cluster()),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
            patch("tools.aws.destroy_gateway.wait_gateway_absent"),
            patch("tools.aws.destroy_gateway.remove_signoz"),
            patch("tools.aws.destroy_gateway.remove_metabase") as remove_metabase,
        ):
            remove_gateway(run, valid_settings(), metabase_state())
        remove_metabase.assert_called_once()
        self.assertTrue(
            any(
                entry.args[0][:6]
                == ["kubectl", "-n", "metabase", "delete", "ingress", "metabase-ui"]
                for entry in run.call_args_list
            )
        )

    def test_wait_continues_while_metabase_ingress_remains(self):
        from tools.aws.destroy_gateway import POLL_SECONDS, wait_gateway_absent

        with (
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
            patch("tools.aws.destroy_gateway.get_signoz_ingress", return_value=None),
            patch(
                "tools.aws.destroy_gateway.get_metabase_ingress",
                side_effect=[
                    metabase_ingress_object(),
                    metabase_ingress_object(),
                    None,
                ],
            ),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.time.sleep") as sleep,
        ):
            wait_gateway_absent(Mock(), valid_settings(), metabase_state())
        self.assertEqual(
            [entry.args for entry in sleep.call_args_list],
            [(POLL_SECONDS,), (POLL_SECONDS,)],
        )

    def test_wait_times_out_on_leftover_metabase_ingress(self):
        from tools.aws.destroy_gateway import wait_gateway_absent

        with (
            patch("tools.aws.destroy_gateway.get_ingress", return_value=None),
            patch("tools.aws.destroy_gateway.get_signoz_ingress", return_value=None),
            patch(
                "tools.aws.destroy_gateway.get_metabase_ingress",
                return_value=metabase_ingress_object(),
            ),
            patch("tools.aws.destroy_gateway.matching_albs", return_value=[]),
            patch("tools.aws.destroy_gateway.time.monotonic", side_effect=[0, 2]),
            patch("tools.aws.destroy_gateway.time.sleep"),
            self.assertRaises(TimeoutError),
        ):
            wait_gateway_absent(Mock(), valid_settings(), metabase_state(), timeout=1)


class RemoveMetabaseTest(unittest.TestCase):
    """destroy_gateway.remove_metabase — helm uninstall metabase. PVC 가 없어 디스크 정리는 없다."""

    def remove(self, releases):
        from tools.aws.destroy_gateway import remove_metabase

        def execute(command, **unused):
            if command[:2] == ["helm", "list"]:
                return releases
            return ""

        run = Mock(side_effect=execute)
        with patch("builtins.print"):
            remove_metabase(run)
        return [call.args[0] for call in run.call_args_list]

    def test_present_release_is_uninstalled_without_disk_cleanup(self):
        commands = self.remove(
            json.dumps([{"name": "metabase", "namespace": "metabase"}])
        )
        listed = commands[0]
        self.assertEqual(listed[:2], ["helm", "list"])
        self.assertIn("metabase", listed)
        uninstall = [c for c in commands if c[:3] == ["helm", "uninstall", "metabase"]]
        self.assertEqual(len(uninstall), 1)
        pairs = list(itertools.pairwise(uninstall[0]))
        self.assertIn(("--namespace", "metabase"), pairs)
        for command in commands:
            self.assertNotIn("pvc", command)
            self.assertNotIn("pv", command)

    def test_missing_release_does_nothing(self):
        commands = self.remove("[]")
        self.assertEqual(len(commands), 1)
        self.assertEqual(commands[0][:2], ["helm", "list"])

    def test_ambiguous_release_list_fails_before_uninstall(self):
        from tools.aws.destroy_gateway import remove_metabase

        for releases in (
            json.dumps([{"name": "metabase"}, {"name": "metabase"}]),
            json.dumps({"name": "metabase"}),
        ):
            with self.subTest(releases=releases):
                run = Mock(return_value=releases)
                with self.assertRaises(ValueError):
                    remove_metabase(run)
                self.assertFalse(
                    any("uninstall" in call.args[0] for call in run.call_args_list)
                )


class MetabaseIdentityTest(unittest.TestCase):
    """신원 정리 정규식(destroy_identity.TAGGED_ADDRESSES)이 metabase_alb 주소들을 받는다."""

    def test_metabase_alb_addresses_are_owned(self):
        from tools.aws.destroy_identity import TAGGED_ADDRESSES

        def owned(address):
            return any(re.fullmatch(pattern, address) for pattern in TAGGED_ADDRESSES)

        for address in (
            "aws_security_group.metabase_alb",
            'aws_vpc_security_group_ingress_rule.metabase_alb_https["173.245.48.0/20"]',
            'aws_vpc_security_group_ingress_rule.metabase_alb_https["104.16.0.0/13"]',
            "aws_vpc_security_group_egress_rule.metabase_alb_to_ui",
            "aws_vpc_security_group_ingress_rule.metabase_from_alb",
            'aws_ecr_repository.app["mirror_metabase"]',
            'aws_secretsmanager_secret.app["metabase"]',
            # 원래 있던 것은 그대로 받는다.
            "aws_security_group.signoz_alb",
            "aws_vpc_security_group_egress_rule.signoz_alb_to_ui",
        ):
            with self.subTest(address=address):
                self.assertTrue(owned(address))
        for address in (
            "aws_security_group.metabase",
            "aws_security_group.metabase_alb_extra",
            "aws_vpc_security_group_ingress_rule.metabase_alb_https",
            'aws_vpc_security_group_ingress_rule.metabase_alb_https["any"]',
            "aws_vpc_security_group_egress_rule.metabase_alb_to_anything",
            "aws_vpc_security_group_ingress_rule.metabase_from_internet",
        ):
            with self.subTest(address=address):
                self.assertFalse(owned(address))


if __name__ == "__main__":
    unittest.main()
