"""SigNoz on EKS(ADR 0025) — 설정 대조·이미지 복사·배포·ClickHouse 비밀번호·삭제 정리 시험.

명세: docs/project/plans/observability-aws.md. AWS·Kubernetes 를 호출하지 않는다.
"""

import base64
import itertools
import json
import os
import re
import tempfile
import unittest
from pathlib import Path
from typing import ClassVar
from unittest.mock import Mock, patch

from tools.aws.tests.fixtures import (
    SIGNOZ_CHART,
    SIGNOZ_OTLP,
    ecr_registry,
    is_scenetrip_helm,
    is_signoz_helm,
    mirror_urls,
    valid_settings,
)

MIRROR_KEYS = (
    "mirror_signoz",
    "mirror_otel_collector",
    "mirror_clickhouse",
    "mirror_alpine",
    "mirror_clickhouse_operator",
    "mirror_metrics_exporter",
    "mirror_zookeeper",
)
HEX64 = re.compile(r"[0-9a-f]{64}")
# SigNoz 관리자 비밀번호 — token_urlsafe(24)(32 자) 뒤에 대·소문자·숫자·기호 하나씩.
ROOT_PASSWORD = re.compile(r"\A[A-Za-z0-9_-]{32}Aa1!\Z")


def workspace():
    return Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]


def prd_settings():
    return valid_settings(
        environment="prd",
        role="arn:aws:iam::123456789012:role/scenetrip-prd-deploy",
        domain="api.example.com",
    )


def app_urls(settings):
    base = f"{ecr_registry(settings)}/scenetrip-{settings.environment}"
    return {
        "scene_api": f"{base}/scene-api",
        "trip_guide": f"{base}/trip-guide",
        "migration": f"{base}/migration",
        "data_load": f"{base}/data-load",
    }


# --- A. 설정 -------------------------------------------------------------------------------------------------


class SignozConfigTest(unittest.TestCase):
    def test_mirror_images_and_tags(self):
        from tools.aws.config import SIGNOZ_MIRROR

        self.assertEqual(
            SIGNOZ_MIRROR,
            {
                "mirror_signoz": ("signoz/signoz", "v0.145.0"),
                "mirror_otel_collector": ("signoz/signoz-otel-collector", "v0.144.12"),
                "mirror_clickhouse": ("clickhouse/clickhouse-server", "25.12.5"),
                "mirror_alpine": ("alpine", "3.18.2"),
                "mirror_clickhouse_operator": (
                    "altinity/clickhouse-operator",
                    "0.21.2",
                ),
                "mirror_metrics_exporter": ("altinity/metrics-exporter", "0.21.2"),
                "mirror_zookeeper": ("signoz/zookeeper", "3.7.1"),
            },
        )

    def test_repositories_are_apps_plus_mirrors(self):
        from tools.aws.config import REPOSITORIES, SIGNOZ_MIRROR

        expected = {
            "scene_api": "scene-api",
            "trip_guide": "trip-guide",
            "migration": "migration",
            "data_load": "data-load",
            **{key: f"mirror/{path}" for key, (path, _) in SIGNOZ_MIRROR.items()},
        }
        self.assertEqual(REPOSITORIES, expected)
        self.assertEqual(REPOSITORIES["mirror_alpine"], "mirror/alpine")
        self.assertEqual(
            REPOSITORIES["mirror_clickhouse"], "mirror/clickhouse/clickhouse-server"
        )

    def test_signoz_runs_on_dev_and_prd(self):
        from tools.aws.config import SIGNOZ_ENVIRONMENTS

        self.assertEqual(SIGNOZ_ENVIRONMENTS, ("dev", "prd"))

    def test_repositories_match_terraform_locals(self):
        from tools.aws.config import REPOSITORIES

        source = (
            workspace() / "platform/terraform/aws/registry-secrets.tf"
        ).read_text()
        block = re.search(r"\brepositories\s*=\s*\{(.*?)\n\s*\}", source, re.DOTALL)
        self.assertIsNotNone(block, "local.repositories 블록을 찾지 못했습니다")
        body = re.sub(r"#[^\n]*", "", block.group(1))
        pairs = re.findall(r'([a-z_]+)\s*=\s*"([^"]*)"', body)
        self.assertEqual(len(pairs), len(dict(pairs)), "Terraform 키가 중복됩니다")
        self.assertEqual(dict(pairs), REPOSITORIES)

    def test_helm_signoz_build_defines_push_for_every_mirror(self):
        from tools.aws.config import SIGNOZ_MIRROR

        path = workspace() / "platform/helm/signoz/BUILD.bazel"
        if not path.exists():
            self.skipTest("platform/helm/signoz/BUILD.bazel 이 runfiles 에 없습니다")
        source = path.read_text()
        self.assertRegex(source, r'name\s*=\s*key\s*\+\s*"_push"')
        self.assertRegex(source, r'image\s*=\s*"@"\s*\+\s*key')
        keys = re.search(r"for\s+key\s+in\s+\[(.*?)\]", source, re.DOTALL)
        self.assertIsNotNone(keys)
        self.assertEqual(
            set(re.findall(r'"([a-z_]+)"', keys.group(1))), set(SIGNOZ_MIRROR)
        )

    def test_module_bazel_pulls_every_mirror(self):
        from tools.aws.config import SIGNOZ_MIRROR

        path = workspace() / "MODULE.bazel"
        if not path.exists():
            self.skipTest("MODULE.bazel 이 runfiles 에 없습니다")
        source = path.read_text()
        pulled = set(re.findall(r'oci\.pull\(\s*name\s*=\s*"([A-Za-z0-9_]+)"', source))
        for key in SIGNOZ_MIRROR:
            with self.subTest(key=key):
                self.assertIn(key, pulled)
                self.assertRegex(source, rf'use_repo\(oci,[^)]*"{key}"')

    def test_values_files_exist_for_every_signoz_environment(self):
        from tools.aws.config import SIGNOZ_ENVIRONMENTS

        root = workspace() / "platform/helm/signoz"
        self.assertTrue((root / "values.yaml").is_file())
        for environment in SIGNOZ_ENVIRONMENTS:
            with self.subTest(environment=environment):
                self.assertTrue((root / f"values-{environment}.yaml").is_file())


# --- B. 삭제 -------------------------------------------------------------------------------------------------


class SignozTeardownTest(unittest.TestCase):
    TAGS: ClassVar[dict[str, str]] = {
        "Project": "scenetrip",
        "Environment": "dev",
        "ManagedBy": "terraform",
    }

    def resources(self, settings):
        from tools.aws.config import REPOSITORIES

        resources = {}
        for key, name in REPOSITORIES.items():
            resources[f'aws_ecr_repository.app["{key}"]'] = {
                "name": f"scenetrip-{settings.environment}/{name}",
                "registry_id": settings.account,
                "arn": f"arn:aws:ecr:{settings.region}:{settings.account}:repository/"
                + f"scenetrip-{settings.environment}/{name}",
                "tags_all": self.TAGS,
            }
            resources[f'aws_ecr_lifecycle_policy.app["{key}"]'] = {
                "repository": f"scenetrip-{settings.environment}/{name}"
            }
        return resources

    def test_preparation_force_deletes_every_repository_including_mirrors(self):
        from tools.aws.config import REPOSITORIES
        from tools.aws.destroy_plan import preparation_targets, write_override

        addresses = [f'aws_ecr_repository.app["{key}"]' for key in REPOSITORIES]
        for key in MIRROR_KEYS:
            self.assertIn(f'aws_ecr_repository.app["{key}"]', addresses)
        state = {address: {} for address in addresses}
        self.assertEqual(sorted(preparation_targets(state)), sorted(addresses))
        with tempfile.TemporaryDirectory() as temporary:
            expected = write_override(Path(temporary), valid_settings(), "retain")
        for address in addresses:
            with self.subTest(address=address):
                self.assertEqual(expected.get(address), {"force_delete": True})

    def test_mirror_repositories_and_policies_are_owned_and_identified(self):
        from tools.aws.destroy import validate_resource_identity
        from tools.aws.destroy_identity import validate_owned_state

        settings = valid_settings()
        resources = self.resources(settings)
        validate_owned_state(resources, settings)
        validate_resource_identity(resources, settings)

    def test_mirror_repository_with_wrong_identity_is_rejected(self):
        from tools.aws.destroy import validate_resource_identity

        settings = valid_settings()
        resources = self.resources(settings)
        repository = 'aws_ecr_repository.app["mirror_signoz"]'
        for changes in (
            {"name": "scenetrip-dev/signoz/signoz"},
            {"name": "scenetrip-dev/mirror/signoz/zookeeper"},
            {"name": "scenetrip-prd/mirror/signoz/signoz"},
            {"registry_id": "999999999999"},
        ):
            changed = {
                **resources,
                repository: {**resources[repository], **changes},
                # 정책은 바뀐 이름을 따라가게 둬서, 이름 검사 자체가 거절하는지 본다.
                'aws_ecr_lifecycle_policy.app["mirror_signoz"]': {
                    "repository": changes.get("name", resources[repository]["name"])
                },
            }
            with (
                self.subTest(changes=changes),
                self.assertRaisesRegex(ValueError, "ECR"),
            ):
                validate_resource_identity(changed, settings)

    def test_mirror_policy_and_tags_are_checked(self):
        from tools.aws.destroy_identity import validate_owned_state

        settings = valid_settings()
        resources = self.resources(settings)
        repository = 'aws_ecr_repository.app["mirror_zookeeper"]'
        policy = 'aws_ecr_lifecycle_policy.app["mirror_zookeeper"]'
        for address, changes in (
            (repository, {"tags_all": {**self.TAGS, "Environment": "prd"}}),
            (policy, {"repository": "scenetrip-dev/mirror/signoz/signoz"}),
            (policy, {"repository": ""}),
        ):
            changed = {**resources, address: {**resources[address], **changes}}
            with (
                self.subTest(address=address, changes=changes),
                self.assertRaises(ValueError),
            ):
                validate_owned_state(changed, settings)


# --- C. 이미지 복사 ------------------------------------------------------------------------------------------


class SignozPublishTest(unittest.TestCase):
    def outputs(self, settings):
        return {"ecr_repository_urls": app_urls(settings) | mirror_urls(settings)}

    def publish(self, outputs, settings, listed=None, **kwargs):
        """listed: 저장소 이름 → 이미 있는 태그 목록. 없으면 빈 저장소."""
        from tools.aws.aws import publish

        registry = ecr_registry(settings)
        listed = listed or {}
        auth = json.dumps(
            {
                "authorizationData": [
                    {
                        "proxyEndpoint": "https://" + registry,
                        "authorizationToken": "fixture-token",
                    }
                ]
            }
        )

        def execute(command, **unused):
            if "get-authorization-token" in command:
                return auth
            if "list-images" in command:
                name = command[command.index("--repository-name") + 1]
                return json.dumps(
                    {
                        "imageIds": [
                            {"imageDigest": "sha256:" + "0" * 64, "imageTag": tag}
                            for tag in listed.get(name, [])
                        ]
                    }
                )
            return ""

        run = Mock(side_effect=execute)
        with tempfile.TemporaryDirectory() as temp:
            publish(run, outputs, settings, Path(temp), **kwargs)
            docker = str(Path(temp) / "docker")
        return run, docker

    def test_dev_mirrors_every_missing_image_after_app_pushes(self):
        from tools.aws.config import REPOSITORIES, SIGNOZ_MIRROR

        settings = valid_settings()
        outputs = self.outputs(settings)
        run, docker = self.publish(outputs, settings)
        commands = [call.args[0] for call in run.call_args_list]
        lists = [i for i, command in enumerate(commands) if "list-images" in command]
        app = [
            i
            for i, command in enumerate(commands)
            if command[:2] == ["just", "run"]
            and not command[2].startswith("//platform/helm/signoz:")
        ]
        self.assertEqual(len(app), 3)
        self.assertEqual(len(lists), len(SIGNOZ_MIRROR))
        self.assertLess(max(app), min(lists))
        for key, (unused, tag) in SIGNOZ_MIRROR.items():
            name = f"scenetrip-dev/{REPOSITORIES[key]}"
            with self.subTest(key=key):
                self.assertIn(
                    [
                        "aws",
                        "ecr",
                        "list-images",
                        "--repository-name",
                        name,
                        "--filter",
                        "tagStatus=TAGGED",
                        "--output",
                        "json",
                    ],
                    commands,
                )
                push = next(
                    call
                    for call in run.call_args_list
                    if call.args[0][:3]
                    == ["just", "run", f"//platform/helm/signoz:{key}_push"]
                )
                self.assertEqual(
                    push.args[0],
                    [
                        "just",
                        "run",
                        f"//platform/helm/signoz:{key}_push",
                        "--",
                        "--repository",
                        outputs["ecr_repository_urls"][key],
                        "--tag",
                        tag,
                    ],
                )
                self.assertEqual(push.kwargs["env"], {"DOCKER_CONFIG": docker})
        # 앱 이미지와 같은 임시 Docker 설정을 쓴다.
        for index in app:
            self.assertEqual(
                run.call_args_list[index].kwargs["env"], {"DOCKER_CONFIG": docker}
            )
        for command in commands:
            self.assertNotIn("fixture-token", " ".join(command))

    def test_existing_tags_are_not_pushed_again(self):
        from tools.aws.config import REPOSITORIES, SIGNOZ_MIRROR

        settings = valid_settings()
        name = lambda key: f"scenetrip-dev/{REPOSITORIES[key]}"
        listed = {
            name(key): [tag, "other"]
            for key, (unused, tag) in SIGNOZ_MIRROR.items()
            if key != "mirror_zookeeper"
        }
        # 다른 태그만 있으면 아직 복사하지 않은 것이다.
        listed[name("mirror_zookeeper")] = ["3.7.0"]
        run, _unused = self.publish(self.outputs(settings), settings, listed)
        mirrors = [
            call.args[0][2]
            for call in run.call_args_list
            if call.args[0][:2] == ["just", "run"]
            and call.args[0][2].startswith("//platform/helm/signoz:")
        ]
        self.assertEqual(mirrors, ["//platform/helm/signoz:mirror_zookeeper_push"])

    def test_all_tags_present_pushes_no_mirror(self):
        from tools.aws.config import REPOSITORIES, SIGNOZ_MIRROR

        settings = valid_settings()
        listed = {
            f"scenetrip-dev/{REPOSITORIES[key]}": [tag]
            for key, (unused, tag) in SIGNOZ_MIRROR.items()
        }
        run, _unused = self.publish(self.outputs(settings), settings, listed)
        self.assertFalse(
            any(
                "//platform/helm/signoz:" in part
                for call in run.call_args_list
                for part in call.args[0]
            )
        )

    def test_mirror_repository_must_belong_to_selected_account_and_environment(self):
        settings = valid_settings()
        registry = ecr_registry(settings)
        for wrong in (
            f"{registry}/scenetrip-prd/mirror/signoz/signoz",
            "999999999999.dkr.ecr.ap-northeast-2.amazonaws.com/scenetrip-dev/mirror/signoz/signoz",
            f"{registry}/scenetrip-dev/signoz/signoz",
            f"{registry}/scenetrip-dev/mirror/signoz/zookeeper",
        ):
            outputs = self.outputs(settings)
            outputs["ecr_repository_urls"]["mirror_signoz"] = wrong
            with self.subTest(url=wrong), self.assertRaises(ValueError):
                self.publish(outputs, settings)

    def test_prd_mirrors_every_missing_image_into_prd_repositories(self):
        from tools.aws.config import REPOSITORIES, SIGNOZ_MIRROR

        settings = prd_settings()
        outputs = self.outputs(settings)
        run, _unused = self.publish(outputs, settings)
        commands = [call.args[0] for call in run.call_args_list]
        listed = [
            command[command.index("--repository-name") + 1]
            for command in commands
            if "list-images" in command
        ]
        self.assertEqual(
            listed, [f"scenetrip-prd/{REPOSITORIES[key]}" for key in SIGNOZ_MIRROR]
        )
        pushes = {
            command[2]: command[command.index("--repository") + 1]
            for command in commands
            if command[:2] == ["just", "run"]
            and command[2].startswith("//platform/helm/signoz:")
        }
        self.assertEqual(
            pushes,
            {
                f"//platform/helm/signoz:{key}_push": outputs["ecr_repository_urls"][
                    key
                ]
                for key in SIGNOZ_MIRROR
            },
        )
        for repository in pushes.values():
            self.assertIn("/scenetrip-prd/mirror/", repository)

    def test_environment_outside_signoz_environments_never_lists_or_mirrors(self):
        settings = prd_settings()
        with patch("tools.aws.aws.SIGNOZ_ENVIRONMENTS", ("dev",)):
            run, _unused = self.publish(
                {"ecr_repository_urls": app_urls(settings)}, settings
            )
        for call in run.call_args_list:
            self.assertNotIn("list-images", call.args[0])
            self.assertFalse(
                any("//platform/helm/signoz:" in part for part in call.args[0])
            )

    def test_explicit_images_never_mirror(self):
        settings = valid_settings()
        run, _unused = self.publish(
            self.outputs(settings),
            settings,
            images=(("data_load", "//services/scene-api:data_load_push"),),
        )
        commands = [call.args[0] for call in run.call_args_list]
        self.assertFalse(any("list-images" in command for command in commands))
        pushes = [command for command in commands if command[:2] == ["just", "run"]]
        self.assertEqual(
            [command[2] for command in pushes], ["//services/scene-api:data_load_push"]
        )


# --- D. 배포 ------------------------------------------------------------------------------------------------


class SignozDeployTest(unittest.TestCase):
    def outputs(self, settings):
        from tools.aws.tests.test_auth_secret import deploy_outputs

        outputs = deploy_outputs(settings)
        outputs["environment"] = settings.environment
        outputs["user_media_bucket"] = (
            f"scenetrip-user-media-123456789012-ap-northeast-2-{settings.environment}"
        )
        if settings.environment == "prd":
            outputs["ingress_public"] = True
        return outputs

    def deploy(
        self,
        settings,
        outputs,
        chart=Path(SIGNOZ_CHART),
        stored=None,
        root_store=None,
    ):
        """stored: 클러스터에 이미 있는 SigNoz ClickHouse 비밀번호(없으면 첫 배포).

        root_store: Secrets Manager 흉내(FakeSecretStore). 주면 SigNoz 관리자 비밀번호 칸 호출을 거기로 보낸다.
        안 주면 cluster_commands 가 「이미 저장된 비밀번호」 로 답한다."""
        from tools.aws.deploy import deploy
        from tools.aws.tests.test_auth_secret import cluster_commands

        events = []

        def execute(command, **kwargs):
            events.append(("run", command, kwargs))
            if root_store is not None and "secretsmanager" in command:
                return root_store(command, **kwargs)
            if "get" in command and "signoz-clickhouse" in command:
                if stored is None:
                    return ""
                return json.dumps(
                    {"data": {"password": base64.b64encode(stored.encode()).decode()}}
                )
            return cluster_commands(command, **kwargs)

        def mark(name, result=None):
            def perform(*unused, **unused_kwargs):
                events.append((name,))
                return result

            return perform

        run = Mock(side_effect=execute)
        with (
            patch("tools.aws.deploy.verify_nodeclass"),
            patch(
                "tools.aws.deploy.wait_for_alb",
                return_value="fixture.ap-northeast-2.elb.amazonaws.com",
            ),
            patch(
                "tools.aws.deploy.database_credentials",
                side_effect=mark(
                    "database_credentials",
                    {
                        "username": "app_runtime",
                        "password": "fixture",
                        "migration_username": "app_migrate",
                        "migration_password": "fixture",
                    },
                ),
            ),
            patch(
                "tools.aws.deploy.auth_secret",
                side_effect=mark(
                    "auth_secret",
                    {
                        "SCENETRIP_AUTH_JWT_SECRET": "fixture-signing-key",
                        "SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY": "fixture-encryption-key",
                        "SCENETRIP_SERVICE_TOKEN": "fixture-service-token",
                    },
                ),
            ),
            patch(
                "tools.aws.deploy.secret_value",
                side_effect=mark("secret_value", {"KAKAO_REST_KEY": "fixture"}),
            ),
            patch("tools.aws.deploy.migrate_database"),
            patch("tools.aws.deploy.verify_network_deny"),
        ):
            deploy(run, Path("/repo"), settings, outputs, chart)
        return run, events

    @staticmethod
    def applied(run):
        manifests = []
        for call in run.call_args_list:
            if call.args[0] == ["kubectl", "apply", "-f", "-"]:
                manifests.append(json.loads(call.kwargs["stdin"]))
        return manifests

    def test_dev_installs_signoz_before_secrets_and_app(self):
        settings = valid_settings()
        _run, events = self.deploy(settings, self.outputs(settings))
        signoz = next(
            i for i, e in enumerate(events) if e[0] == "run" and is_signoz_helm(e[1])
        )
        app = next(
            i for i, e in enumerate(events) if e[0] == "run" and is_scenetrip_helm(e[1])
        )
        namespace = next(
            i
            for i, e in enumerate(events)
            if e[0] == "run"
            and e[1] == ["kubectl", "apply", "-f", "-"]
            and json.loads(e[2]["stdin"]).get("kind") == "Namespace"
            and json.loads(e[2]["stdin"])["metadata"]["name"] == "signoz"
        )
        first_secret = min(
            i
            for i, e in enumerate(events)
            if e[0] in {"database_credentials", "auth_secret", "secret_value"}
            or (
                e[0] == "run"
                and e[1] == ["kubectl", "apply", "-f", "-"]
                and json.loads(e[2]["stdin"]).get("metadata", {}).get("namespace")
                != "signoz"
                and json.loads(e[2]["stdin"]).get("kind") == "Secret"
            )
        )
        self.assertLess(namespace, signoz)
        self.assertLess(signoz, first_secret)
        self.assertLess(signoz, app)

    def test_dev_signoz_namespace_storage_class_and_helm_command(self):
        settings = valid_settings()
        run, _unused = self.deploy(settings, self.outputs(settings))
        manifests = self.applied(run)
        namespace = next(
            m
            for m in manifests
            if m.get("kind") == "Namespace" and m["metadata"]["name"] == "signoz"
        )
        labels = namespace["metadata"]["labels"]
        self.assertEqual(labels["pod-security.kubernetes.io/enforce"], "baseline")
        self.assertEqual(labels["pod-security.kubernetes.io/warn"], "restricted")
        storage = next(m for m in manifests if m.get("kind") == "StorageClass")
        self.assertEqual(storage["metadata"]["name"], "signoz-gp3")
        self.assertEqual(storage["provisioner"], "ebs.csi.eks.amazonaws.com")
        self.assertEqual(storage["reclaimPolicy"], "Delete")
        self.assertEqual(storage["volumeBindingMode"], "WaitForFirstConsumer")
        self.assertEqual(storage["parameters"]["type"], "gp3")
        self.assertEqual(storage["parameters"]["encrypted"], "true")

        helm = [call for call in run.call_args_list if is_signoz_helm(call.args[0])]
        self.assertEqual(len(helm), 1)
        self.assertEqual(
            helm[0].args[0],
            [
                "helm",
                "upgrade",
                "--install",
                "signoz",
                SIGNOZ_CHART,
                "--namespace",
                "signoz",
                "--values",
                "/repo/platform/helm/signoz/values.yaml",
                "--values",
                "/repo/platform/helm/signoz/values-dev.yaml",
                "--values",
                "-",
                "--atomic",
                "--wait",
                "--timeout",
                "15m",
                "--history-max",
                "5",
            ],
        )
        values = json.loads(helm[0].kwargs["stdin"])
        self.assertEqual(
            values["global"],
            {
                "imageRegistry": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
                "/scenetrip-dev/mirror"
            },
        )
        self.assertRegex(values["clickhouse"]["password"], HEX64)

    def test_first_deploy_password_only_travels_on_stdin(self):
        settings = valid_settings()
        run, _unused = self.deploy(settings, self.outputs(settings))
        secret = next(
            m
            for m in self.applied(run)
            if m.get("kind") == "Secret"
            and m["metadata"] == {"name": "signoz-clickhouse", "namespace": "signoz"}
        )
        password = secret["stringData"]["password"]
        self.assertRegex(password, HEX64)
        helm = next(call for call in run.call_args_list if is_signoz_helm(call.args[0]))
        self.assertEqual(
            json.loads(helm.kwargs["stdin"])["clickhouse"]["password"], password
        )
        for call in run.call_args_list:
            self.assertNotIn(password, " ".join(call.args[0]))

    def test_stored_password_is_reused(self):
        settings = valid_settings()
        stored = "ab" * 32
        run, _unused = self.deploy(settings, self.outputs(settings), stored=stored)
        helm = next(call for call in run.call_args_list if is_signoz_helm(call.args[0]))
        self.assertEqual(
            json.loads(helm.kwargs["stdin"])["clickhouse"]["password"], stored
        )
        self.assertFalse(
            any(
                m.get("kind") == "Secret"
                and m["metadata"].get("name") == "signoz-clickhouse"
                for m in self.applied(run)
            )
        )
        for call in run.call_args_list:
            self.assertNotIn(stored, " ".join(call.args[0]))

    # --- SigNoz 관리자(ADR 0025) ---

    def root_secret(self, run):
        return next(
            m
            for m in self.applied(run)
            if m.get("kind") == "Secret"
            and m["metadata"] == {"name": "signoz-root", "namespace": "signoz"}
        )

    def test_dev_applies_root_secret_from_stored_password_before_signoz_helm(self):
        from tools.aws.tests.test_auth_secret import (
            SIGNOZ_ARN,
            SIGNOZ_ROOT_PASSWORD,
            FakeSecretStore,
        )

        settings = valid_settings()
        store = FakeSecretStore(
            {SIGNOZ_ARN: json.dumps({"SIGNOZ_ROOT_PASSWORD": SIGNOZ_ROOT_PASSWORD})}
        )
        run, events = self.deploy(settings, self.outputs(settings), root_store=store)
        secret = self.root_secret(run)
        self.assertEqual(secret["stringData"], {"password": SIGNOZ_ROOT_PASSWORD})
        # 저장된 값을 그대로 쓴다 — 다시 쓰지 않는다.
        self.assertEqual(store.puts(), [])
        # 읽은 대상은 Terraform 출력의 signoz 칸이다.
        self.assertTrue(any(SIGNOZ_ARN in command for command, unused in store.calls))
        applied = next(
            i
            for i, e in enumerate(events)
            if e[0] == "run"
            and e[1] == ["kubectl", "apply", "-f", "-"]
            and json.loads(e[2]["stdin"]).get("metadata", {}).get("name")
            == "signoz-root"
        )
        helm = next(
            i for i, e in enumerate(events) if e[0] == "run" and is_signoz_helm(e[1])
        )
        self.assertLess(applied, helm)

    def test_dev_signoz_helm_values_create_root_user_from_secret(self):
        settings = valid_settings()
        run, _unused = self.deploy(settings, self.outputs(settings))
        helm = next(call for call in run.call_args_list if is_signoz_helm(call.args[0]))
        values = json.loads(helm.kwargs["stdin"])
        self.assertEqual(
            values["signoz"],
            {
                "env": {
                    "signoz_user_root_enabled": "true",
                    "signoz_user_root_email": "admin@scenetrip.io",
                    "signoz_user_root_org_name": "scenetrip",
                    "signoz_user_root_password": {
                        "valueFrom": {
                            "secretKeyRef": {"name": "signoz-root", "key": "password"}
                        }
                    },
                }
            },
        )

    def test_root_constants(self):
        from tools.aws import deploy

        self.assertEqual(deploy.SIGNOZ_ROOT_EMAIL, "admin@scenetrip.io")
        self.assertEqual(deploy.SIGNOZ_ROOT_SECRET, "signoz-root")

    def test_first_deploy_generates_root_password_only_on_stdin(self):
        from tools.aws.tests.test_auth_secret import SIGNOZ_ARN, FakeSecretStore

        settings = valid_settings()
        store = FakeSecretStore()
        run, _unused = self.deploy(settings, self.outputs(settings), root_store=store)
        password = self.root_secret(run)["stringData"]["password"]
        self.assertRegex(password, ROOT_PASSWORD)
        puts = store.puts()
        self.assertEqual(len(puts), 1)
        body = json.loads(puts[0][1]["stdin"])
        self.assertEqual(body["SecretId"], SIGNOZ_ARN)
        self.assertEqual(
            json.loads(body["SecretString"]), {"SIGNOZ_ROOT_PASSWORD": password}
        )
        helm = next(call for call in run.call_args_list if is_signoz_helm(call.args[0]))
        self.assertNotIn(password, helm.kwargs["stdin"])
        for call in run.call_args_list:
            self.assertNotIn(password, " ".join(call.args[0]))
        # 비밀번호가 실리는 stdin 은 Secret 명세와 Secrets Manager 저장 본문 둘뿐이다.
        carriers = [
            call
            for call in run.call_args_list
            if password in (call.kwargs.get("stdin") or "")
        ]
        self.assertEqual(len(carriers), 2)
        for call in carriers:
            self.assertIn(
                call.args[0],
                (
                    ["kubectl", "apply", "-f", "-"],
                    [
                        "aws",
                        "secretsmanager",
                        "put-secret-value",
                        "--cli-input-json",
                        "file:///dev/stdin",
                    ],
                ),
            )

        # 두 번째 배포는 처음 만든 비밀번호를 다시 쓴다.
        again, _unused = self.deploy(settings, self.outputs(settings), root_store=store)
        self.assertEqual(self.root_secret(again)["stringData"]["password"], password)
        self.assertEqual(len(store.puts()), 1)

    def test_stored_root_password_never_appears_in_argv_or_helm_values(self):
        from tools.aws.tests.test_auth_secret import SIGNOZ_ROOT_PASSWORD

        settings = valid_settings()
        run, _unused = self.deploy(settings, self.outputs(settings))
        for call in run.call_args_list:
            self.assertNotIn(SIGNOZ_ROOT_PASSWORD, " ".join(call.args[0]))
            if is_signoz_helm(call.args[0]) or is_scenetrip_helm(call.args[0]):
                self.assertNotIn(SIGNOZ_ROOT_PASSWORD, call.kwargs["stdin"])

    def test_dev_without_signoz_secret_output_fails_before_signoz_helm(self):
        settings = valid_settings()
        outputs = self.outputs(settings)
        del outputs["app_secret_arns"]["signoz"]
        with self.assertRaises((KeyError, ValueError)):
            self.deploy(settings, outputs)

    def test_dev_app_values_point_otel_at_signoz_collector(self):
        settings = valid_settings()
        run, _unused = self.deploy(settings, self.outputs(settings))
        helm = next(
            call for call in run.call_args_list if is_scenetrip_helm(call.args[0])
        )
        values = json.loads(helm.kwargs["stdin"])
        self.assertEqual(values["otel"], {"endpoint": SIGNOZ_OTLP})

    def test_dev_without_chart_fails_before_app_release(self):
        settings = valid_settings()
        for chart in (None, ""):
            with self.subTest(chart=chart), self.assertRaises(ValueError):
                self.deploy(settings, self.outputs(settings), chart=chart)

    def test_dev_without_chart_mutates_no_helm_release(self):
        from tools.aws.deploy import deploy
        from tools.aws.tests.test_auth_secret import cluster_commands

        settings = valid_settings()
        run = Mock(side_effect=cluster_commands)
        with (
            patch("tools.aws.deploy.verify_nodeclass"),
            patch("tools.aws.deploy.database_credentials") as credentials,
            self.assertRaises(ValueError),
        ):
            deploy(run, Path("/repo"), settings, self.outputs(settings), None)
        credentials.assert_not_called()
        self.assertFalse(any(call.args[0][0] == "helm" for call in run.call_args_list))

    def test_dev_mirror_output_must_match_account_and_environment(self):
        settings = valid_settings()
        registry = ecr_registry(settings)
        for wrong in (
            f"{registry}/scenetrip-prd/mirror/signoz/signoz",
            "999999999999.dkr.ecr.ap-northeast-2.amazonaws.com/scenetrip-dev/mirror/signoz/signoz",
            f"{registry}/scenetrip-dev/signoz/signoz",
        ):
            outputs = self.outputs(settings)
            outputs["ecr_repository_urls"]["mirror_signoz"] = wrong
            with self.subTest(url=wrong), self.assertRaises(ValueError):
                self.deploy(settings, outputs)

    def test_prd_installs_signoz_with_prd_values_and_enables_otel(self):
        settings = prd_settings()
        run, events = self.deploy(settings, self.outputs(settings))
        helm = [call for call in run.call_args_list if is_signoz_helm(call.args[0])]
        self.assertEqual(len(helm), 1)
        command = helm[0].args[0]
        self.assertIn("/repo/platform/helm/signoz/values-prd.yaml", command)
        self.assertNotIn("/repo/platform/helm/signoz/values-dev.yaml", command)
        values = json.loads(helm[0].kwargs["stdin"])
        self.assertEqual(
            values["global"],
            {
                "imageRegistry": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"
                "/scenetrip-prd/mirror"
            },
        )
        signoz = next(
            i for i, e in enumerate(events) if e[0] == "run" and is_signoz_helm(e[1])
        )
        app = next(
            i for i, e in enumerate(events) if e[0] == "run" and is_scenetrip_helm(e[1])
        )
        self.assertLess(signoz, app)
        app_helm = next(
            call for call in run.call_args_list if is_scenetrip_helm(call.args[0])
        )
        self.assertEqual(
            json.loads(app_helm.kwargs["stdin"])["otel"], {"endpoint": SIGNOZ_OTLP}
        )

    def test_prd_without_chart_fails_before_app_release(self):
        settings = prd_settings()
        for chart in (None, ""):
            with self.subTest(chart=chart), self.assertRaises(ValueError):
                self.deploy(settings, self.outputs(settings), chart=chart)

    def test_environment_outside_signoz_environments_installs_no_signoz(self):
        settings = prd_settings()
        outputs = self.outputs(settings)
        for key in MIRROR_KEYS:
            outputs["ecr_repository_urls"].pop(key, None)
        with patch("tools.aws.deploy.SIGNOZ_ENVIRONMENTS", ("dev",)):
            run, _unused = self.deploy(settings, outputs, chart=None)
        for call in run.call_args_list:
            self.assertFalse(is_signoz_helm(call.args[0]))
            self.assertNotIn("signoz", call.args[0])
            self.assertNotIn("signoz-clickhouse", call.args[0])
        for manifest in self.applied(run):
            self.assertNotEqual(manifest.get("metadata", {}).get("name"), "signoz")
            self.assertNotEqual(manifest.get("metadata", {}).get("name"), "signoz-root")
            self.assertNotEqual(manifest.get("kind"), "StorageClass")
        helm = next(
            call for call in run.call_args_list if is_scenetrip_helm(call.args[0])
        )
        self.assertEqual(json.loads(helm.kwargs["stdin"])["otel"], {"endpoint": ""})


class SignozRootPasswordTest(unittest.TestCase):
    """deploy.signoz_root_password — generated_once 로 환경당 한 번 만들고 다시 쓴다(ADR 0025)."""

    def test_empty_slot_generates_once_and_value_only_reaches_stdin(self):
        from tools.aws.deploy import signoz_root_password
        from tools.aws.tests.test_auth_secret import SIGNOZ_ARN, FakeSecretStore

        store = FakeSecretStore()
        password = signoz_root_password(Mock(side_effect=store), SIGNOZ_ARN)
        self.assertIsInstance(password, str)
        self.assertRegex(password, ROOT_PASSWORD)
        puts = store.puts()
        self.assertEqual(len(puts), 1)
        body = json.loads(puts[0][1]["stdin"])
        self.assertEqual(body["SecretId"], SIGNOZ_ARN)
        self.assertEqual(
            json.loads(body["SecretString"]), {"SIGNOZ_ROOT_PASSWORD": password}
        )
        for command, _unused in store.calls:
            self.assertNotIn(password, " ".join(command))

        again = signoz_root_password(Mock(side_effect=store), SIGNOZ_ARN)
        self.assertEqual(again, password)
        self.assertEqual(len(store.puts()), 1)

    def test_stored_password_is_returned_without_put(self):
        from tools.aws.deploy import signoz_root_password
        from tools.aws.tests.test_auth_secret import SIGNOZ_ARN, FakeSecretStore

        stored = "stored-signoz-root-passwordAa1!"
        store = FakeSecretStore(
            {SIGNOZ_ARN: json.dumps({"SIGNOZ_ROOT_PASSWORD": stored})}
        )
        self.assertEqual(
            signoz_root_password(Mock(side_effect=store), SIGNOZ_ARN), stored
        )
        self.assertEqual(store.puts(), [])

    def test_generated_passwords_differ(self):
        from tools.aws.deploy import signoz_root_password
        from tools.aws.tests.test_auth_secret import SIGNOZ_ARN, FakeSecretStore

        first = signoz_root_password(Mock(side_effect=FakeSecretStore()), SIGNOZ_ARN)
        second = signoz_root_password(Mock(side_effect=FakeSecretStore()), SIGNOZ_ARN)
        self.assertNotEqual(first, second)

    def test_signoz_secret_kind_requires_root_password(self):
        from tools.aws.config import SECRET_KEYS, validate_secret

        self.assertEqual(SECRET_KEYS["signoz"], {"SIGNOZ_ROOT_PASSWORD"})
        validate_secret("signoz", {"SIGNOZ_ROOT_PASSWORD": "x" * 32 + "Aa1!"})
        with self.assertRaises(ValueError):
            validate_secret("signoz", {})


# --- E. ClickHouse 비밀번호 ---------------------------------------------------------------------------------


class ClickhousePasswordTest(unittest.TestCase):
    GET: ClassVar[list[str]] = [
        "kubectl",
        "-n",
        "signoz",
        "get",
        "secret",
        "signoz-clickhouse",
        "--ignore-not-found",
        "-o",
        "json",
    ]

    @staticmethod
    def stored(password):
        return json.dumps(
            {
                "apiVersion": "v1",
                "kind": "Secret",
                "data": {"password": base64.b64encode(password.encode()).decode()},
            }
        )

    def test_existing_secret_is_returned_and_never_rewritten(self):
        from tools.aws.deploy import clickhouse_password

        value = "0123456789abcdef" * 4
        run = Mock(return_value=self.stored(value))
        self.assertEqual(clickhouse_password(run), value)
        self.assertEqual(clickhouse_password(run), value)
        self.assertEqual([call.args[0] for call in run.call_args_list], [self.GET] * 2)
        for call in run.call_args_list:
            self.assertNotIn("stdin", call.kwargs)

    def test_malformed_stored_password_is_rejected(self):
        from tools.aws.deploy import clickhouse_password

        for value in (
            "",
            "short",
            "0123456789ABCDEF" * 4,
            "g" * 64,
            "a" * 63,
            "a" * 65,
        ):
            with self.subTest(value=value), self.assertRaises(ValueError):
                clickhouse_password(Mock(return_value=self.stored(value)))

    def test_missing_secret_is_generated_and_applied_through_stdin(self):
        from tools.aws.deploy import clickhouse_password

        run = Mock(return_value="")
        password = clickhouse_password(run)
        self.assertRegex(password, HEX64)
        self.assertEqual(run.call_args_list[0].args[0], self.GET)
        apply = run.call_args_list[1]
        self.assertEqual(apply.args[0], ["kubectl", "apply", "-f", "-"])
        manifest = json.loads(apply.kwargs["stdin"])
        self.assertEqual(manifest["kind"], "Secret")
        self.assertEqual(
            manifest["metadata"], {"name": "signoz-clickhouse", "namespace": "signoz"}
        )
        self.assertEqual(manifest["stringData"], {"password": password})
        self.assertEqual(len(run.call_args_list), 2)
        for call in run.call_args_list:
            self.assertNotIn(password, " ".join(call.args[0]))
        # 매번 새 난수다.
        self.assertNotEqual(password, clickhouse_password(Mock(return_value="")))


# --- F. 삭제 시 SigNoz 정리 ---------------------------------------------------------------------------------


class RemoveSignozTest(unittest.TestCase):
    HELM_LIST: ClassVar[list[str]] = [
        "helm",
        "list",
        "--namespace",
        "signoz",
        "--all",
        "--filter",
        "^signoz$",
        "--output",
        "json",
    ]
    CHI_DELETE: ClassVar[list[str]] = [
        "kubectl",
        "-n",
        "signoz",
        "delete",
        "clickhouseinstallations.clickhouse.altinity.com",
        "--all",
        "--ignore-not-found",
        "--wait",
        "--timeout=5m",
    ]
    UNINSTALL: ClassVar[list[str]] = [
        "helm",
        "uninstall",
        "signoz",
        "--namespace",
        "signoz",
        "--wait",
        "--timeout",
        "10m",
    ]
    PVC_DELETE: ClassVar[list[str]] = [
        "kubectl",
        "-n",
        "signoz",
        "delete",
        "pvc",
        "--all",
        "--ignore-not-found",
        "--wait",
        "--timeout=5m",
    ]
    PV_LIST: ClassVar[list[str]] = ["kubectl", "get", "pv", "--output", "json"]

    @staticmethod
    def volumes(*classes):
        return json.dumps(
            {
                "items": [
                    {
                        "metadata": {"name": f"pv-{i}"},
                        "spec": {"storageClassName": name},
                    }
                    for i, name in enumerate(classes)
                ]
            }
        )

    def remove(self, releases, pv_answers, **kwargs):
        from tools.aws.destroy_gateway import remove_signoz

        answers = iter(pv_answers)

        def execute(command, **unused):
            if command == self.HELM_LIST:
                return releases
            if command == self.PV_LIST:
                return next(answers)
            return ""

        run = Mock(side_effect=execute)
        clock = Mock()
        clock.monotonic.side_effect = itertools.count(0, 100)
        with patch("tools.aws.destroy_gateway.time", clock):
            remove_signoz(run, **kwargs)
        return run, clock

    def test_release_is_removed_chi_first_then_disks_are_awaited(self):
        run, clock = self.remove(
            json.dumps([{"name": "signoz", "namespace": "signoz"}]),
            [
                self.volumes("signoz-gp3", "gp2"),
                self.volumes("signoz-gp3"),
                self.volumes("gp2", "scenetrip"),
            ],
        )
        self.assertEqual(
            [call.args[0] for call in run.call_args_list],
            [
                self.HELM_LIST,
                self.CHI_DELETE,
                self.UNINSTALL,
                self.PVC_DELETE,
                self.PV_LIST,
                self.PV_LIST,
                self.PV_LIST,
            ],
        )
        self.assertEqual(clock.sleep.call_count, 2)

    def test_missing_release_still_cleans_leftover_disks(self):
        run, _unused = self.remove("[]", [self.volumes("signoz-gp3"), self.volumes()])
        commands = [call.args[0] for call in run.call_args_list]
        self.assertEqual(
            commands, [self.HELM_LIST, self.PVC_DELETE, self.PV_LIST, self.PV_LIST]
        )
        self.assertNotIn(self.CHI_DELETE, commands)
        self.assertNotIn(self.UNINSTALL, commands)

    def test_ambiguous_release_list_fails_before_any_delete(self):
        for releases in (
            json.dumps([{"name": "signoz"}, {"name": "signoz"}]),
            json.dumps({"name": "signoz"}),
            json.dumps("signoz"),
        ):
            with self.subTest(releases=releases):
                from tools.aws.destroy_gateway import remove_signoz

                run = Mock(return_value=releases)
                with self.assertRaises(ValueError):
                    remove_signoz(run)
                self.assertEqual(
                    [call.args[0] for call in run.call_args_list], [self.HELM_LIST]
                )

    def test_disks_that_never_disappear_fail_after_timeout(self):
        with self.assertRaises(RuntimeError):
            self.remove(
                json.dumps([{"name": "signoz"}]),
                itertools.repeat(self.volumes("signoz-gp3")),
                timeout=600,
            )


class SignozTraceVerifyTest(unittest.TestCase):
    POD = "chi-signoz-clickhouse-cluster-0-0-0"
    POD_LOOKUP: ClassVar[list[str]] = [
        "kubectl",
        "-n",
        "signoz",
        "get",
        "pods",
        "-l",
        "clickhouse.altinity.com/chi=signoz-clickhouse",
        "-o",
        "jsonpath={.items[0].metadata.name}",
    ]

    def count_command(self, pod=POD):
        from tools.aws.deploy import SIGNOZ_SPAN_QUERY

        return [
            "kubectl",
            "-n",
            "signoz",
            "exec",
            pod,
            "-c",
            "clickhouse",
            "--",
            "clickhouse-client",
            "--query",
            SIGNOZ_SPAN_QUERY,
        ]

    def verify(self, outputs, clock=(), **kwargs):
        from tools.aws.deploy import verify_signoz_traces

        run = Mock(side_effect=list(outputs))
        fake_time = Mock()
        fake_time.monotonic.side_effect = list(clock)
        with patch("tools.aws.deploy.time", fake_time):
            try:
                return verify_signoz_traces(run, **kwargs), run, fake_time
            except Exception as error:
                error.run = run
                error.time = fake_time
                raise

    def test_span_query_counts_recent_index_rows(self):
        from tools.aws.deploy import SIGNOZ_SPAN_QUERY

        self.assertRegex(
            SIGNOZ_SPAN_QUERY,
            re.compile(
                r"SELECT\s+count\(\).*FROM\s+signoz_traces\.distributed_signoz_index_v3"
                r".*INTERVAL\s+15\s+MINUTE",
                re.DOTALL,
            ),
        )

    def test_spans_found_first_time_return_count_without_sleep(self):
        spans, run, fake_time = self.verify([f"{self.POD}\n", " 42\n"], clock=[100.0])
        self.assertEqual(spans, 42)
        self.assertEqual(
            [call.args[0] for call in run.call_args_list],
            [self.POD_LOOKUP, self.count_command()],
        )
        for call in run.call_args_list:
            self.assertEqual(call.kwargs, {"quiet": True})
        fake_time.sleep.assert_not_called()

    def test_zero_spans_polls_until_spans_appear(self):
        spans, run, fake_time = self.verify(
            [self.POD, "0", "0", "7"], clock=[0.0, 10.0, 20.0], timeout=180, poll=10
        )
        self.assertEqual(spans, 7)
        self.assertEqual(
            [call.args[0] for call in run.call_args_list],
            [self.POD_LOOKUP] + [self.count_command()] * 3,
        )
        self.assertEqual(
            [call.args for call in fake_time.sleep.call_args_list], [(10,), (10,)]
        )

    def test_zero_spans_past_deadline_raise_runtime_error(self):
        with self.assertRaises(RuntimeError) as caught:
            self.verify(
                [self.POD, "0", "0", "0"],
                clock=[0.0, 5.0, 20.0, 31.0],
                timeout=30,
                poll=3,
            )
        self.assertEqual(
            [call.args[0] for call in caught.exception.run.call_args_list],
            [self.POD_LOOKUP] + [self.count_command()] * 3,
        )
        self.assertEqual(
            [call.args for call in caught.exception.time.sleep.call_args_list],
            [(3,), (3,)],
        )

    def test_invalid_pod_name_fails_before_exec(self):
        for pod in ("", "  \n", "Signoz_Pod", "pod; rm -rf /", "a" * 254):
            with self.subTest(pod=pod), self.assertRaises(ValueError) as caught:
                self.verify([pod, "1"])
            self.assertEqual(
                [call.args[0] for call in caught.exception.run.call_args_list],
                [self.POD_LOOKUP],
            )

    def test_non_numeric_count_is_rejected(self):
        for counted in ("", "abc", "-1", "1.5", "Code: 516. Authentication failed"):
            with self.subTest(counted=counted), self.assertRaises(ValueError):
                self.verify([self.POD, counted], clock=[0.0])

    def test_no_password_or_secret_in_any_command(self):
        _spans, run, _time = self.verify([self.POD, "0", "3"], clock=[0.0, 1.0])
        for call in run.call_args_list:
            joined = " ".join(call.args[0]).lower()
            for word in ("password", "secret", "--user"):
                self.assertNotIn(word, joined)
            self.assertNotIn("input", call.kwargs)


# --- F. 화면(Cloudflare Access 뒤, ADR 0025) ---------------------------------------------------------------

SIGNOZ_CERTIFICATE = "arn:aws:acm:ap-northeast-2:123456789012:certificate/" + "b" * 36
SIGNOZ_ALB_NAME = "scenetrip-dev-signoz-alb"
SIGNOZ_ALB_HOST = "signoz.ap-northeast-2.elb.amazonaws.com"
KUBECTL_APPLY = ["kubectl", "apply", "-f", "-"]


def ui_outputs(settings=None, **changes):
    """dev 배포 출력 + SigNoz 화면 인증서."""
    from tools.aws.tests.test_auth_secret import deploy_outputs

    settings = settings or valid_settings()
    return {
        **deploy_outputs(settings),
        "environment": settings.environment,
        "signoz_certificate_arn": SIGNOZ_CERTIFICATE,
        **changes,
    }


def describe_certificate(status="ISSUED", names=("signoz-dev.scenetrip.io",)):
    return json.dumps(
        {
            "Certificate": {
                "CertificateArn": SIGNOZ_CERTIFICATE,
                "Status": status,
                "SubjectAlternativeNames": list(names),
            }
        }
    )


class SignozUiConfigTest(unittest.TestCase):
    def test_domains_per_environment(self):
        from tools.aws.config import SIGNOZ_DOMAINS

        self.assertEqual(
            SIGNOZ_DOMAINS,
            {"dev": "signoz-dev.scenetrip.io", "prd": "signoz.scenetrip.io"},
        )

    def test_teardown_owns_signoz_alb_security_resources(self):
        from tools.aws.destroy_identity import TAGGED_ADDRESSES

        def owned(address):
            return any(re.fullmatch(pattern, address) for pattern in TAGGED_ADDRESSES)

        for address in (
            "aws_security_group.signoz_alb",
            'aws_vpc_security_group_ingress_rule.signoz_alb_https["173.245.48.0/20"]',
            'aws_vpc_security_group_ingress_rule.signoz_alb_https["104.16.0.0/13"]',
            "aws_vpc_security_group_egress_rule.signoz_alb_to_ui",
            "aws_vpc_security_group_ingress_rule.signoz_from_alb",
            # 원래 있던 API ALB 자원은 그대로 받는다.
            "aws_security_group.alb",
            "aws_security_group.postgres",
            'aws_vpc_security_group_ingress_rule.alb_https["0.0.0.0/0"]',
            "aws_vpc_security_group_ingress_rule.gateway_from_alb",
            "aws_vpc_security_group_egress_rule.alb_to_gateway",
        ):
            with self.subTest(address=address):
                self.assertTrue(owned(address))
        for address in (
            "aws_security_group.signoz",
            "aws_security_group.signoz_alb_extra",
            "aws_vpc_security_group_ingress_rule.signoz_alb_https",
            'aws_vpc_security_group_ingress_rule.signoz_alb_https["any"]',
            "aws_vpc_security_group_egress_rule.signoz_alb_to_anything",
            "aws_vpc_security_group_ingress_rule.signoz_from_internet",
        ):
            with self.subTest(address=address):
                self.assertFalse(owned(address))


class SignozIngressTest(unittest.TestCase):
    def manifests(self, settings=None, **changes):
        from tools.aws.deploy import signoz_ingress

        settings = settings or valid_settings()
        return signoz_ingress(settings, ui_outputs(settings, **changes))

    def test_three_manifests_in_apply_order(self):
        params, ingress_class, ingress = self.manifests()
        self.assertEqual(
            [params["kind"], ingress_class["kind"], ingress["kind"]],
            ["IngressClassParams", "IngressClass", "Ingress"],
        )

    def test_class_params_are_public_ipv4_on_public_subnets_with_certificate(self):
        params, ingress_class, _ingress = self.manifests()
        self.assertEqual(params["metadata"]["name"], SIGNOZ_ALB_NAME)
        spec = params["spec"]
        self.assertEqual(spec["scheme"], "internet-facing")
        self.assertEqual(spec["ipAddressType"], "ipv4")
        self.assertEqual(
            spec["namespaceSelector"],
            {"matchLabels": {"kubernetes.io/metadata.name": "signoz"}},
        )
        self.assertEqual(spec["subnets"], {"ids": ui_outputs()["public_subnet_ids"]})
        self.assertEqual(spec["certificateARNs"], [SIGNOZ_CERTIFICATE])
        self.assertEqual(ingress_class["metadata"]["name"], SIGNOZ_ALB_NAME)
        self.assertEqual(ingress_class["spec"]["parameters"]["name"], SIGNOZ_ALB_NAME)
        self.assertEqual(
            ingress_class["spec"]["parameters"]["kind"], "IngressClassParams"
        )

    def test_ingress_routes_dev_domain_to_signoz_8080_through_terraform_group(self):
        *_unused, ingress = self.manifests()
        self.assertEqual(ingress["metadata"]["name"], "signoz-ui")
        self.assertEqual(ingress["metadata"]["namespace"], "signoz")
        annotations = ingress["metadata"]["annotations"]
        prefix = "alb.ingress.kubernetes.io/"
        self.assertEqual(
            json.loads(annotations[prefix + "listen-ports"]), [{"HTTPS": 443}]
        )
        self.assertEqual(annotations[prefix + "certificate-arn"], SIGNOZ_CERTIFICATE)
        self.assertEqual(
            annotations[prefix + "security-groups"], "sg-ddddddddddddddddd"
        )
        self.assertEqual(
            annotations[prefix + "manage-backend-security-group-rules"], "false"
        )
        self.assertEqual(annotations[prefix + "target-type"], "ip")
        self.assertEqual(annotations[prefix + "healthcheck-path"], "/api/v1/health")
        spec = ingress["spec"]
        self.assertEqual(spec["ingressClassName"], SIGNOZ_ALB_NAME)
        self.assertEqual(len(spec["rules"]), 1)
        rule = spec["rules"][0]
        self.assertEqual(rule["host"], "signoz-dev.scenetrip.io")
        self.assertEqual(
            rule["http"]["paths"],
            [
                {
                    "path": "/",
                    "pathType": "Prefix",
                    "backend": {
                        "service": {"name": "signoz", "port": {"number": 8080}}
                    },
                }
            ],
        )

    def test_prd_uses_prd_names(self):
        settings = prd_settings()
        params, _class, ingress = self.manifests(settings)
        self.assertEqual(params["metadata"]["name"], "scenetrip-prd-signoz-alb")
        self.assertEqual(
            ingress["spec"]["ingressClassName"], "scenetrip-prd-signoz-alb"
        )
        self.assertEqual(ingress["spec"]["rules"][0]["host"], "signoz.scenetrip.io")

    def test_rejects_malformed_security_group_and_subnets(self):
        for changes in (
            {"signoz_alb_security_group_id": "sg-malicious;"},
            {"signoz_alb_security_group_id": ""},
            {"signoz_alb_security_group_id": "sg-DDDDDDDD"},
            {"public_subnet_ids": ["subnet-aaaaaaaaaaaaaaaaa"]},
            {"public_subnet_cidrs": ["0.0.0.0/0", "10.40.1.0/24"]},
        ):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.manifests(**changes)


class ExposeSignozTest(unittest.TestCase):
    def expose(self, outputs, answers=(), wait=SIGNOZ_ALB_HOST):
        from tools.aws.deploy import expose_signoz

        run = Mock(side_effect=list(answers))
        with patch(
            "tools.aws.deploy.wait_for_signoz_alb", return_value=wait
        ) as waiting:
            result = expose_signoz(run, valid_settings(), outputs)
        return result, run, waiting

    def test_without_certificate_nothing_runs(self):
        with patch("builtins.print") as printed:
            result, run, waiting = self.expose(ui_outputs(signoz_certificate_arn=""))
        self.assertIsNone(result)
        run.assert_not_called()
        waiting.assert_not_called()
        printed.assert_called()

    def test_certificate_is_checked_then_three_manifests_applied_then_alb_awaited(self):
        from tools.aws.deploy import signoz_ingress

        outputs = ui_outputs()
        result, run, waiting = self.expose(
            outputs, answers=[describe_certificate(), "", "", ""]
        )
        self.assertEqual(result, SIGNOZ_ALB_HOST)
        commands = [entry.args[0] for entry in run.call_args_list]
        self.assertEqual(
            commands[0],
            [
                "aws",
                "acm",
                "describe-certificate",
                "--certificate-arn",
                SIGNOZ_CERTIFICATE,
                "--output",
                "json",
            ],
        )
        self.assertEqual(commands[1:], [KUBECTL_APPLY] * 3)
        self.assertEqual(
            [json.loads(entry.kwargs["stdin"]) for entry in run.call_args_list[1:]],
            signoz_ingress(valid_settings(), outputs),
        )
        waiting.assert_called_once_with(run, valid_settings(), outputs)

    def test_certificate_not_issued_or_not_covering_domain_stops_before_apply(self):
        for answer in (
            describe_certificate(status="PENDING_VALIDATION"),
            describe_certificate(status="EXPIRED"),
            describe_certificate(names=("signoz.scenetrip.io",)),
            describe_certificate(names=("*.scenetrip.io",)),
            describe_certificate(names=()),
        ):
            with self.subTest(answer=answer):
                from tools.aws.deploy import expose_signoz

                run = Mock(side_effect=[answer])
                with (
                    patch("tools.aws.deploy.wait_for_signoz_alb") as waiting,
                    self.assertRaises(ValueError),
                ):
                    expose_signoz(run, valid_settings(), ui_outputs())
                self.assertEqual(run.call_count, 1)
                self.assertNotIn(
                    KUBECTL_APPLY, [entry.args[0] for entry in run.call_args_list]
                )
                waiting.assert_not_called()


class SignozExposeWiringTest(unittest.TestCase):
    """deploy() 가 SigNoz 환경(dev·prd)에서 install_signoz 바로 뒤에 화면을 열고, 그 밖의 환경에서는 열지 않는다."""

    @staticmethod
    def marker(name):
        # 배포기가 넘긴 run 으로 표지를 남긴다 — 다른 명령·비밀값 조회와의 순서를 같은 기록에서 본다.
        return lambda run, *unused, **kw: run(["test-marker", name])

    def deploy(self, settings, outputs, chart):
        with (
            patch(
                "tools.aws.deploy.install_signoz", side_effect=self.marker("install")
            ) as install,
            patch(
                "tools.aws.deploy.expose_signoz", side_effect=self.marker("expose")
            ) as expose,
        ):
            run, events = SignozDeployTest.deploy(self, settings, outputs, chart)
        order = [
            event[1][1]
            if event[0] == "run" and event[1][0] == "test-marker"
            else event[0]
            for event in events
            if event[0] != "run" or event[1][0] == "test-marker"
        ]
        return run, order, install, expose

    def test_dev_exposes_right_after_install(self):
        settings = valid_settings()
        outputs = SignozDeployTest.outputs(self, settings)
        run, order, install, expose = self.deploy(settings, outputs, Path(SIGNOZ_CHART))
        self.assertEqual(order[:3], ["install", "expose", "database_credentials"])
        install.assert_called_once()
        expose.assert_called_once_with(run, settings, outputs)

    def test_prd_exposes_right_after_install(self):
        settings = prd_settings()
        outputs = SignozDeployTest.outputs(self, settings)
        run, order, install, expose = self.deploy(settings, outputs, Path(SIGNOZ_CHART))
        self.assertEqual(order[:3], ["install", "expose", "database_credentials"])
        install.assert_called_once()
        expose.assert_called_once_with(run, settings, outputs)

    def test_environment_outside_signoz_environments_never_exposes(self):
        settings = prd_settings()
        outputs = SignozDeployTest.outputs(self, settings)
        for key in MIRROR_KEYS:
            outputs["ecr_repository_urls"].pop(key, None)
        with patch("tools.aws.deploy.SIGNOZ_ENVIRONMENTS", ("dev",)):
            _run, order, install, expose = self.deploy(settings, outputs, None)
        install.assert_not_called()
        expose.assert_not_called()
        self.assertNotIn("expose", order)


if __name__ == "__main__":
    unittest.main()
