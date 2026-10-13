"""원격 데이터 적재(MZ2AZ-414) — Job 모양·실행 순서·정리·로그 걸러내기·CLI 경로·네트워크 정책 시험.

명세: docs/project/plans/remote-data-load.md, docs/ops/aws-deployment.md §6. AWS·Kubernetes 를 호출하지 않는다.
"""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from tools.aws.config import SIGNOZ_MIRROR
from tools.aws.tests.fixtures import mirror_urls, valid_settings

HOST = "db.example.internal"
IMAGE = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/scenetrip-dev/data-load:t"
COMMIT = "a" * 40
CREDENTIALS = {
    "username": "app_runtime",
    "password": "runtime-fixture",
    "migration_username": "app_migrate",
    "migration_password": "migration-fixture",
}
REGISTRY = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com"


def repositories(environment="dev"):
    return {
        key: f"{REGISTRY}/scenetrip-{environment}/{name}"
        for key, name in (
            ("scene_api", "scene-api"),
            ("trip_guide", "trip-guide"),
            ("migration", "migration"),
            ("data_load", "data-load"),
        )
    } | mirror_urls(valid_settings(environment=environment))


def is_delete(command, kind, name):
    return (
        command[:1] == ["kubectl"]
        and "delete" in command
        and kind in command
        and name in command
    )


class FakeKubectl:
    """적재 Job 실행 중 부르는 명령을 기록하고, 정해 둔 로그·대기 결과를 돌려준다."""

    def __init__(self, logs="", wait_fails=False, logs_fail=False):
        self.logs = logs
        self.wait_fails = wait_fails
        self.logs_fail = logs_fail
        self.calls = []

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if command[:1] == ["kubectl"] and "wait" in command:
            if self.wait_fails:
                raise RuntimeError("timed out waiting for the condition")
            return ""
        if command[:1] == ["kubectl"] and "logs" in command:
            if self.logs_fail:
                raise RuntimeError("no pods")
            return self.logs
        return ""

    def manifests(self):
        return [
            json.loads(kwargs["stdin"])
            for _, kwargs in self.calls
            if kwargs.get("stdin")
        ]

    def commands(self):
        return [command for command, _ in self.calls]


class DataLoadJobTest(unittest.TestCase):
    def job(self):
        from tools.aws.deploy import data_load_job

        return data_load_job(HOST, IMAGE)

    def test_job_identity_label_and_limits(self):
        job = self.job()
        self.assertEqual(job["kind"], "Job")
        self.assertEqual(job["metadata"]["name"], "data-load")
        self.assertEqual(job["metadata"]["namespace"], "scenetrip")
        spec = job["spec"]
        self.assertEqual(spec["backoffLimit"], 0)
        self.assertEqual(spec["activeDeadlineSeconds"], 1800)
        self.assertEqual(spec["template"]["metadata"]["labels"]["app"], "data-load")
        pod = spec["template"]["spec"]
        self.assertEqual(len(pod["containers"]), 1)
        container = pod["containers"][0]
        self.assertEqual(container["image"], IMAGE)
        self.assertEqual(container["resources"]["limits"]["memory"], "512Mi")

    def test_job_has_restricted_security_posture_of_bootstrap_job(self):
        pod = self.job()["spec"]["template"]["spec"]
        self.assertEqual(pod["restartPolicy"], "Never")
        self.assertIs(pod["automountServiceAccountToken"], False)
        self.assertEqual(pod["nodeSelector"], {"kubernetes.io/arch": "amd64"})
        self.assertEqual(
            pod["securityContext"]["seccompProfile"], {"type": "RuntimeDefault"}
        )
        security = pod["containers"][0]["securityContext"]
        self.assertEqual(security["runAsUser"], 10001)
        self.assertIs(security["runAsNonRoot"], True)
        self.assertIs(security["allowPrivilegeEscalation"], False)
        self.assertIs(security["readOnlyRootFilesystem"], True)
        self.assertEqual(security["capabilities"], {"drop": ["ALL"]})
        self.assertFalse(security.get("privileged", False))

    def test_job_uses_image_entrypoint_and_transient_owner_secret(self):
        container = self.job()["spec"]["template"]["spec"]["containers"][0]
        self.assertNotIn("command", container)
        self.assertNotIn("args", container)
        env = {item["name"]: item.get("value") for item in container["env"]}
        self.assertEqual(env["PGHOST"], HOST)
        self.assertEqual(env["PGDATABASE"], "scenetrip")
        self.assertEqual(env["PGSSLMODE"], "require")
        # 자격 증명은 값이 아니라 Secret 참조로만 들어간다.
        self.assertNotIn("PGPASSWORD", env)
        self.assertNotIn("PGUSER", env)
        self.assertEqual(
            container["envFrom"], [{"secretRef": {"name": "database-load-transient"}}]
        )
        self.assertNotIn("database-admin-transient", json.dumps(self.job()))
        self.assertNotIn("stringData", json.dumps(self.job()))

    def test_tmp_is_a_bounded_empty_dir(self):
        pod = self.job()["spec"]["template"]["spec"]
        mounts = pod["containers"][0]["volumeMounts"]
        tmp = [mount for mount in mounts if mount["mountPath"] == "/tmp"]
        self.assertEqual(len(tmp), 1)
        volumes = {volume["name"]: volume for volume in pod["volumes"]}
        self.assertEqual(volumes[tmp[0]["name"]]["emptyDir"], {"sizeLimit": "1Gi"})

    def test_host_is_validated(self):
        from tools.aws.deploy import data_load_job

        for host in ("db.example; rm -rf /", "$(id)", "db example"):
            with self.subTest(host=host), self.assertRaises(ValueError):
                data_load_job(host, IMAGE)


class DataLoadRunTest(unittest.TestCase):
    def load(self, fake, commit=COMMIT):
        from tools.aws.deploy import load_data

        with patch("builtins.print") as printed:
            try:
                load_data(fake, {"database_host": HOST}, CREDENTIALS, IMAGE, commit)
            finally:
                self.printed = [
                    " ".join(str(arg) for arg in call.args)
                    for call in printed.call_args_list
                ]

    def assert_cleaned_up_last(self, fake):
        commands = fake.commands()
        # 마지막 두 명령은 Job·임시 Secret 삭제다.
        tail = commands[-2:]
        self.assertTrue(
            any(is_delete(command, "job", "data-load") for command in tail), tail
        )
        self.assertTrue(
            any(
                is_delete(command, "secret", "database-load-transient")
                for command in tail
            ),
            tail,
        )

    def test_success_orders_secret_job_wait_logs_and_cleanup(self):
        fake = FakeKubectl(logs="==> 촬영지\nDATA_LOAD_SUMMARY places=10 poi=20\n")
        self.load(fake)
        secrets = [m for m in fake.manifests() if m["kind"] == "Secret"]
        jobs = [m for m in fake.manifests() if m["kind"] == "Job"]
        self.assertEqual(len(secrets), 1)
        self.assertEqual(len(jobs), 1)
        secret = secrets[0]
        self.assertEqual(secret["metadata"]["name"], "database-load-transient")
        self.assertEqual(
            secret["stringData"],
            {
                "PGUSER": "app_migrate",
                "PGPASSWORD": "migration-fixture",
                "SCENETRIP_COMMIT": COMMIT,
            },
        )
        self.assertEqual(jobs[0]["metadata"]["name"], "data-load")
        self.assertEqual(
            jobs[0]["spec"]["template"]["spec"]["containers"][0]["image"], IMAGE
        )
        commands = fake.commands()
        index = {
            "secret": next(
                i
                for i, (_, kw) in enumerate(fake.calls)
                if kw.get("stdin") and json.loads(kw["stdin"])["kind"] == "Secret"
            ),
            "job": next(
                i
                for i, (_, kw) in enumerate(fake.calls)
                if kw.get("stdin") and json.loads(kw["stdin"])["kind"] == "Job"
            ),
            "wait": next(i for i, c in enumerate(commands) if "wait" in c),
            "logs": next(i for i, c in enumerate(commands) if "logs" in c),
        }
        self.assertLess(index["secret"], index["job"])
        self.assertLess(index["job"], index["wait"])
        self.assertLess(index["wait"], index["logs"])
        wait = commands[index["wait"]]
        self.assertIn("--for=condition=complete", wait)
        self.assertIn("job/data-load", wait)
        self.assert_cleaned_up_last(fake)

    def test_credentials_never_appear_in_command_arguments(self):
        fake = FakeKubectl(logs="DATA_LOAD_SUMMARY ok\n")
        self.load(fake)
        for command in fake.commands():
            joined = " ".join(command)
            self.assertNotIn("migration-fixture", joined)
            self.assertNotIn("runtime-fixture", joined)
        # 런타임 계정은 적재에 쓰지 않는다.
        self.assertNotIn(
            "runtime-fixture", json.dumps(fake.manifests(), ensure_ascii=False)
        )

    def test_only_progress_summary_and_failure_lines_are_printed(self):
        logs = (
            "==> 편의시설 적재\n"
            "COPY 912345\n"
            "PGPASSWORD=migration-fixture\n"
            "NOTICE:  relation exists\n"
            "  ==> 들여쓴 줄은 단계 줄이 아니다\n"
            "DATA_LOAD_SUMMARY places=10\n"
            "DATA_LOAD_FAILED nothing\n"
            "DATA_LOAD_SUMMARYX not a summary\n"
        )
        fake = FakeKubectl(logs=logs)
        self.load(fake)
        self.assertEqual(
            self.printed,
            [
                "==> 편의시설 적재",
                "DATA_LOAD_SUMMARY places=10",
                "DATA_LOAD_FAILED nothing",
            ],
        )

    def test_missing_summary_fails_and_still_cleans_up(self):
        fake = FakeKubectl(logs="==> 촬영지\nCOPY 10\n")
        with self.assertRaises(RuntimeError):
            self.load(fake)
        self.assertEqual(self.printed, ["==> 촬영지"])
        self.assert_cleaned_up_last(fake)

    def test_incomplete_job_fails_prints_filtered_lines_and_cleans_up(self):
        fake = FakeKubectl(
            logs="==> 촬영지\nERROR: secret detail\nDATA_LOAD_FAILED step=poi\n",
            wait_fails=True,
        )
        with self.assertRaises(RuntimeError):
            self.load(fake)
        self.assertEqual(self.printed, ["==> 촬영지", "DATA_LOAD_FAILED step=poi"])
        self.assertFalse(any("secret detail" in line for line in self.printed))
        self.assert_cleaned_up_last(fake)

    def test_incomplete_job_without_logs_still_fails_and_cleans_up(self):
        fake = FakeKubectl(wait_fails=True, logs_fail=True)
        with self.assertRaises(RuntimeError):
            self.load(fake)
        self.assert_cleaned_up_last(fake)

    def test_failure_applying_job_still_cleans_up(self):
        fake = FakeKubectl()
        original = fake.__call__

        def failing(command, **kwargs):
            stdin = kwargs.get("stdin")
            if stdin and json.loads(stdin)["kind"] == "Job":
                fake.calls.append((command, kwargs))
                raise RuntimeError("apply refused")
            return original(command, **kwargs)

        with self.assertRaisesRegex(RuntimeError, "apply refused"):
            self.load(failing)
        self.assert_cleaned_up_last(fake)

    def test_commit_must_look_like_a_hex_sha_before_any_mutation(self):
        for commit in ("main", "", "g" * 40, "abc; rm", "A" * 40 + "\n"):
            fake = FakeKubectl(logs="DATA_LOAD_SUMMARY ok\n")
            with self.subTest(commit=commit), self.assertRaises(ValueError):
                self.load(fake, commit=commit)
            self.assertEqual(fake.manifests(), [])

    def test_delete_data_load_removes_job_and_secret_idempotently(self):
        from tools.aws.deploy import delete_data_load

        run = Mock(return_value="")
        delete_data_load(run)
        commands = [call.args[0] for call in run.call_args_list]
        self.assertTrue(any(is_delete(c, "job", "data-load") for c in commands))
        self.assertTrue(
            any(is_delete(c, "secret", "database-load-transient") for c in commands)
        )
        for command in commands:
            self.assertIn("--ignore-not-found", command)
            self.assertIn("scenetrip", command)


class DataLoadPublishTest(unittest.TestCase):
    def publish(self, outputs, **kwargs):
        from tools.aws.aws import publish

        auth = json.dumps(
            {
                "authorizationData": [
                    {
                        "proxyEndpoint": "https://" + REGISTRY,
                        "authorizationToken": "fixture-token",
                    }
                ]
            }
        )
        # SigNoz 복사본(ADR 0025)은 이미 ECR 에 있다 — 이 시험은 앱 이미지 push 만 본다.
        mirrored = json.dumps(
            {"imageIds": [{"imageTag": tag} for unused, tag in SIGNOZ_MIRROR.values()]}
        )

        def execute(command, **unused):
            if "list-images" in command:
                return mirrored
            return auth if "ecr" in command else ""

        run = Mock(side_effect=execute)
        with tempfile.TemporaryDirectory() as temp:
            publish(run, outputs, valid_settings(), Path(temp), **kwargs)
        return [
            call.args[0]
            for call in run.call_args_list
            if call.args[0][:2] == ["just", "run"]
        ]

    def test_normal_publish_never_pushes_data_load_image(self):
        pushes = self.publish({"ecr_repository_urls": repositories()})
        targets = [command[2] for command in pushes]
        self.assertEqual(
            targets,
            [
                "//services/scene-api:push",
                "//agents/trip-guide:push",
                "//services/scene-api:migration_push",
            ],
        )
        for command in pushes:
            self.assertNotIn("data-load", " ".join(command))

    def test_data_load_publish_pushes_only_data_load_under_run_tag(self):
        pushes = self.publish(
            {"ecr_repository_urls": repositories()},
            images=(("data_load", "//services/scene-api:data_load_push"),),
        )
        self.assertEqual(len(pushes), 1)
        command = pushes[0]
        self.assertEqual(command[2], "//services/scene-api:data_load_push")
        self.assertIn(repositories()["data_load"], command)
        self.assertIn(valid_settings().tag, command)

    def test_data_load_repository_must_belong_to_selected_environment(self):
        for wrong in (
            f"{REGISTRY}/scenetrip-prd/data-load",
            "999999999999.dkr.ecr.ap-northeast-2.amazonaws.com/scenetrip-dev/data-load",
            f"{REGISTRY}/scenetrip-dev/migration",
        ):
            urls = repositories() | {"data_load": wrong}
            with self.subTest(url=wrong), self.assertRaises(ValueError):
                self.publish(
                    {"ecr_repository_urls": urls},
                    images=(("data_load", "//services/scene-api:data_load_push"),),
                )


class DataLoadEntryPointTest(unittest.TestCase):
    def test_data_load_operation_orders_push_kubeconfig_credentials_and_job(self):
        from contextlib import ExitStack

        from tools.aws import aws

        settings = valid_settings()
        outputs = {
            "environment": "dev",
            "ecr_repository_urls": repositories(),
            "app_secret_arns": {"database": "arn:database"},
            "database_host": HOST,
        }
        events = []

        def runner(command, **unused):
            events.append(("run", tuple(command)))
            if command[:2] == ["terraform", "output"]:
                return "{}"
            return ""

        def record(name, result=None):
            def perform(*args, **kwargs):
                events.append((name, args, kwargs))
                return result

            return perform

        with ExitStack() as stack:
            stack.enter_context(
                patch(
                    "sys.argv",
                    ["aws", "--provider-runfile", "fixture", "data-load", "dev"],
                )
            )
            stack.enter_context(
                patch.object(aws.Settings, "from_environment", return_value=settings)
            )
            stack.enter_context(patch.object(aws, "Runner", return_value=runner))
            for name, result in (
                ("validate_source", None),
                ("validate_identity", None),
                ("configure_tools", None),
                ("initialize_terraform", Path("/nonexistent")),
                ("output_values", outputs),
                ("validate_environment_outputs", None),
                ("publish", None),
                ("database_credentials", CREDENTIALS),
                ("load_data", None),
            ):
                stack.enter_context(
                    patch.object(aws, name, side_effect=record(name, result))
                )
            for forbidden in (
                "terraform",
                "deploy",
                "verify_https",
                "migrate_database",
            ):
                if hasattr(aws, forbidden):
                    stack.enter_context(
                        patch.object(
                            aws,
                            forbidden,
                            side_effect=AssertionError(f"{forbidden} 호출 금지"),
                        )
                    )
            aws.main()

        names = [event[0] for event in events if event[0] != "run"]
        for name in ("publish", "database_credentials", "load_data"):
            self.assertIn(name, names)
        self.assertLess(names.index("output_values"), names.index("publish"))
        self.assertLess(names.index("publish"), names.index("load_data"))
        self.assertLess(names.index("database_credentials"), names.index("load_data"))
        # terraform output 만 읽고 plan/apply 는 하지 않는다.
        commands = [event[1] for event in events if event[0] == "run"]
        for command in commands:
            if command[:1] == ("terraform",):
                self.assertNotIn("apply", command)
                self.assertNotIn("plan", command)
        self.assertIn(("terraform", "output", "-json"), commands)
        kubeconfig = [
            index
            for index, event in enumerate(events)
            if event[0] == "run" and event[1][:3] == ("aws", "eks", "update-kubeconfig")
        ]
        self.assertEqual(len(kubeconfig), 1)
        load_index = next(
            i for i, event in enumerate(events) if event[0] == "load_data"
        )
        self.assertLess(kubeconfig[0], load_index)

        publish_call = next(event for event in events if event[0] == "publish")
        images = publish_call[2].get("images")
        if images is None and len(publish_call[1]) > 4:
            images = publish_call[1][4]
        self.assertEqual(
            tuple(images), (("data_load", "//services/scene-api:data_load_push"),)
        )

        credentials_call = next(
            event for event in events if event[0] == "database_credentials"
        )
        self.assertIn("arn:database", credentials_call[1])
        load_call = next(event for event in events if event[0] == "load_data")
        args = load_call[1]
        self.assertIn(CREDENTIALS, args)
        self.assertIn(repositories()["data_load"] + ":" + settings.tag, args)
        self.assertIn(settings.sha, args)

    def test_cleanup_operation_also_removes_data_load_job_and_secret(self):
        from tools.aws import aws

        with (
            patch(
                "sys.argv", ["aws", "--provider-runfile", "fixture", "cleanup", "dev"]
            ),
            patch.object(
                aws.Settings, "from_environment", return_value=valid_settings()
            ),
            patch.object(aws, "validate_source"),
            patch.object(aws, "validate_identity"),
            patch.object(aws, "configure_tools"),
            patch.object(aws, "Runner", return_value=Mock(return_value="")),
            patch.object(aws, "delete_transient") as transient,
            patch.object(aws, "delete_data_load") as data_load,
        ):
            aws.main()
        transient.assert_called_once()
        data_load.assert_called_once()


class DataLoadNetworkPolicyTest(unittest.TestCase):
    def test_database_jobs_policy_selects_data_load_and_allows_only_5432(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        values = {
            "database": {"host": HOST},
            "sceneApi": {"image": "fixture:api"},
            "tripGuide": {"image": "fixture:guide"},
            "network": {"dnsCidr": "172.20.0.10/32"},
            "gateway": {
                "host": "api.example.com",
                "certificateArn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/"
                + "a" * 36,
                "allowedCidrs": ["192.0.2.1/32"],
                "albSubnetIds": [
                    "subnet-aaaaaaaaaaaaaaaaa",
                    "subnet-bbbbbbbbbbbbbbbbb",
                ],
                "trustedProxyCidrs": ["10.40.0.0/24", "10.40.1.0/24"],
                "securityGroupId": "sg-aaaaaaaaaaaaaaaaa",
            },
        }
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                process = subprocess.run(
                    [
                        str(root / "tools/bazel/cloud/helm"),
                        "template",
                        "scenetrip",
                        str(root / "platform/helm/scenetrip"),
                        "--namespace",
                        "scenetrip",
                        "--values",
                        str(
                            root / f"platform/helm/scenetrip/values-{environment}.yaml"
                        ),
                        "--values",
                        "-",
                    ],
                    input=json.dumps(values),
                    text=True,
                    capture_output=True,
                    check=False,
                )
                self.assertEqual(process.returncode, 0, process.stderr)
                documents = [
                    document
                    for document in process.stdout.split("\n---\n")
                    if "kind: NetworkPolicy" in document
                    and "name: database-jobs\n" in document
                ]
                self.assertEqual(len(documents), 1)
                policy = documents[0]
                selector = policy.split("podSelector:", 1)[1].split("policyTypes:", 1)[
                    0
                ]
                for label in ("database-bootstrap", "database-migrate", "data-load"):
                    self.assertIn(label, selector)
                egress = policy.split("egress:", 1)[1]
                self.assertIn("port: 5432", egress)
                self.assertNotIn("443", egress)
                self.assertNotIn("to:", egress)
                self.assertIn("policyTypes: [Egress]", policy)
                # 적재 Job 라벨은 다른 정책(예: 443 을 여는 정책)에 걸리지 않는다.
                others = [
                    document
                    for document in process.stdout.split("\n---\n")
                    if "kind: NetworkPolicy" in document
                    and "name: database-jobs\n" not in document
                ]
                for document in others:
                    self.assertNotIn("data-load", document)


class ScriptedKubectl:
    """폴링마다 다른 대기 결과·로그를 돌려준다. waits[i] 가 False 면 i 번째 대기는 시간 초과(RuntimeError),
    logs[i] 가 None 이면 i 번째 로그 읽기는 실패(파드가 지워진 경우)다. 목록을 넘어가면 마지막 값을 쓴다."""

    def __init__(self, waits, logs):
        self.waits = waits
        self.logs = logs
        self.calls = []
        self.wait_count = 0
        self.logs_count = 0

    @staticmethod
    def pick(values, index):
        return values[min(index, len(values) - 1)]

    def __call__(self, command, **kwargs):
        self.calls.append((command, kwargs))
        if command[:1] == ["kubectl"] and "wait" in command:
            ok = self.pick(self.waits, self.wait_count)
            self.wait_count += 1
            if not ok:
                raise RuntimeError("timed out waiting for the condition")
            return ""
        if command[:1] == ["kubectl"] and "logs" in command:
            text = self.pick(self.logs, self.logs_count)
            self.logs_count += 1
            if text is None:
                raise RuntimeError("no pods")
            return text
        return ""

    def commands(self):
        return [command for command, _ in self.calls]

    def waits_run(self):
        return [c for c in self.commands() if c[:1] == ["kubectl"] and "wait" in c]


class DataLoadPollingTest(unittest.TestCase):
    """적재 Job 을 1 분씩 기다리며 단계 줄을 한 번씩만 찍는 동작(remote-data-load 명세)."""

    def load(self, fake):
        from tools.aws.deploy import load_data

        # 대기는 kubectl --timeout 이 맡는다 — 시험 중 실제로 잠들면 안 된다.
        with (
            patch("builtins.print") as printed,
            patch(
                "tools.aws.deploy.time.sleep",
                side_effect=AssertionError("시험 중 sleep 호출"),
            ),
        ):
            try:
                load_data(fake, {"database_host": HOST}, CREDENTIALS, IMAGE, COMMIT)
            finally:
                self.printed = [
                    " ".join(str(arg) for arg in call.args)
                    for call in printed.call_args_list
                ]

    def assert_deleted_at_end(self, fake):
        tail = fake.commands()[-2:]
        self.assertTrue(any(is_delete(c, "job", "data-load") for c in tail), tail)
        self.assertTrue(
            any(is_delete(c, "secret", "database-load-transient") for c in tail), tail
        )

    def test_each_wait_is_a_quiet_one_poll_complete_wait(self):
        fake = ScriptedKubectl(
            waits=[False, True], logs=["==> a\n", "==> a\nDATA_LOAD_SUMMARY ok\n"]
        )
        self.load(fake)
        waits = [(c, kw) for c, kw in fake.calls if "wait" in c]
        self.assertEqual(len(waits), 2)
        for command, kwargs in waits:
            self.assertEqual(
                command,
                [
                    "kubectl",
                    "-n",
                    "scenetrip",
                    "wait",
                    "--for=condition=complete",
                    "job/data-load",
                    "--timeout=60s",
                ],
            )
            self.assertTrue(kwargs.get("quiet"))

    def test_progress_is_read_after_every_wait(self):
        fake = ScriptedKubectl(
            waits=[False, False, True],
            logs=["==> a\n", "==> a\n", "==> a\nDATA_LOAD_SUMMARY ok\n"],
        )
        self.load(fake)
        kinds = [
            "wait" if "wait" in c else "logs"
            for c in fake.commands()
            if c[:1] == ["kubectl"] and ("wait" in c or "logs" in c)
        ]
        self.assertEqual(kinds, ["wait", "logs"] * 3)
        logs = [c for c in fake.commands() if "logs" in c]
        for command in logs:
            self.assertEqual(
                command,
                ["kubectl", "-n", "scenetrip", "logs", "job/data-load", "--tail=2000"],
            )

    def test_lines_are_printed_once_across_polls(self):
        fake = ScriptedKubectl(
            waits=[False, False, False, True],
            logs=[
                "==> [+0s] ① 촬영지\n",
                "==> [+0s] ① 촬영지\nCOPY 10\n==> [+61s] ② 편의시설\n",
                "==> [+0s] ① 촬영지\nCOPY 10\n==> [+61s] ② 편의시설\n",
                (
                    "==> [+0s] ① 촬영지\nCOPY 10\n==> [+61s] ② 편의시설\n"
                    "==> [+130s] ⑥ 건수 검사\nDATA_LOAD_SUMMARY places=1\n"
                ),
            ],
        )
        self.load(fake)
        self.assertEqual(
            self.printed,
            [
                "==> [+0s] ① 촬영지",
                "==> [+61s] ② 편의시설",
                "==> [+130s] ⑥ 건수 검사",
                "DATA_LOAD_SUMMARY places=1",
            ],
        )
        self.assertEqual(len(fake.waits_run()), 4)
        self.assert_deleted_at_end(fake)

    def test_new_lines_are_printed_when_tail_window_shifts(self):
        # 로그 꼬리(--tail)가 밀려 앞 줄이 빠져도, 처음 보는 줄은 모두 순서대로 찍고 이미 찍은 줄은 다시 찍지 않는다.
        fake = ScriptedKubectl(
            waits=[False, True],
            logs=[
                "==> [+0s] A\n==> [+30s] B\n",
                "==> [+61s] C\n==> [+90s] D\nDATA_LOAD_SUMMARY ok\n",
            ],
        )
        self.load(fake)
        self.assertEqual(
            self.printed,
            [
                "==> [+0s] A",
                "==> [+30s] B",
                "==> [+61s] C",
                "==> [+90s] D",
                "DATA_LOAD_SUMMARY ok",
            ],
        )
        self.assertEqual(len(fake.waits_run()), 2)
        self.assert_deleted_at_end(fake)

    def test_shifted_window_keeping_some_seen_lines_prints_only_new_ones(self):
        fake = ScriptedKubectl(
            waits=[False, False, True],
            logs=[
                "==> [+0s] A\n==> [+30s] B\n",
                "==> [+30s] B\n==> [+61s] C\n",
                "==> [+61s] C\n==> [+90s] D\nDATA_LOAD_SUMMARY ok\n",
            ],
        )
        self.load(fake)
        self.assertEqual(
            self.printed,
            [
                "==> [+0s] A",
                "==> [+30s] B",
                "==> [+61s] C",
                "==> [+90s] D",
                "DATA_LOAD_SUMMARY ok",
            ],
        )

    def test_progress_is_kept_when_logs_disappear(self):
        # 두 번째 폴링부터 파드가 사라져 로그를 못 읽는다 — 이미 본 줄은 다시 찍지 않고, 요약도 잃지 않는다.
        fake = ScriptedKubectl(
            waits=[False, False, True],
            logs=["==> a\n==> b\nDATA_LOAD_SUMMARY ok\n", None, None],
        )
        self.load(fake)
        self.assertEqual(self.printed, ["==> a", "==> b", "DATA_LOAD_SUMMARY ok"])
        self.assert_deleted_at_end(fake)

    def test_lines_after_logs_return_are_printed_once(self):
        fake = ScriptedKubectl(
            waits=[False, False, True],
            logs=["==> a\n", None, "==> a\n==> b\nDATA_LOAD_SUMMARY ok\n"],
        )
        self.load(fake)
        self.assertEqual(self.printed, ["==> a", "==> b", "DATA_LOAD_SUMMARY ok"])

    def test_stops_polling_once_failure_line_is_seen(self):
        fake = ScriptedKubectl(
            waits=[False],
            logs=["==> a\n", "==> a\nDATA_LOAD_FAILED step=poi\n"],
        )
        with self.assertRaisesRegex(
            RuntimeError, "데이터 적재 Job 이 끝나지 못했습니다"
        ):
            self.load(fake)
        self.assertEqual(len(fake.waits_run()), 2)
        self.assertEqual(self.printed, ["==> a", "DATA_LOAD_FAILED step=poi"])
        self.assert_deleted_at_end(fake)

    def test_never_complete_times_out_after_max_polls(self):
        from tools.aws.deploy import DATA_LOAD_DEADLINE, DATA_LOAD_POLL_SECONDS

        self.assertEqual((DATA_LOAD_DEADLINE, DATA_LOAD_POLL_SECONDS), (1800, 60))
        fake = ScriptedKubectl(waits=[False], logs=["==> a\n"])
        with self.assertRaisesRegex(
            RuntimeError, "데이터 적재 Job 이 끝나지 못했습니다"
        ):
            self.load(fake)
        self.assertEqual(
            len(fake.waits_run()), DATA_LOAD_DEADLINE // DATA_LOAD_POLL_SECONDS + 2
        )
        self.assertEqual(len(fake.waits_run()), 32)
        self.assertEqual(self.printed, ["==> a"])
        self.assert_deleted_at_end(fake)

    def test_complete_without_summary_fails(self):
        fake = ScriptedKubectl(waits=[False, True], logs=["==> a\n", "==> a\n"])
        with self.assertRaisesRegex(RuntimeError, "데이터 적재 요약이 없습니다"):
            self.load(fake)
        self.assert_deleted_at_end(fake)

    def test_complete_with_summary_succeeds_on_first_poll(self):
        fake = ScriptedKubectl(waits=[True], logs=["==> a\nDATA_LOAD_SUMMARY ok\n"])
        self.load(fake)
        self.assertEqual(len(fake.waits_run()), 1)
        self.assertEqual(self.printed, ["==> a", "DATA_LOAD_SUMMARY ok"])
        self.assert_deleted_at_end(fake)

    def test_unexpected_error_while_reading_logs_still_deletes(self):
        fake = ScriptedKubectl(waits=[True], logs=["DATA_LOAD_SUMMARY ok\n"])
        original = fake.__call__

        def broken(command, **kwargs):
            if "logs" in command:
                fake.calls.append((command, kwargs))
                raise KeyError("boom")
            return original(command, **kwargs)

        with self.assertRaises(KeyError):
            self.load(broken)
        self.assert_deleted_at_end(fake)


class RemoteLoadScriptTest(unittest.TestCase):
    """remote-load.sh 의 say 줄 모양 — 적재 Job 로그에서 load_data 가 고르는 ==> 줄."""

    def test_say_prints_elapsed_seconds_since_start(self):
        relative = "services/scene-api/seed/remote-load.sh"
        srcdir = os.environ.get("TEST_SRCDIR")
        workspace = os.environ.get("TEST_WORKSPACE", "_main")
        script = Path(srcdir, workspace, relative) if srcdir else None
        if script is None or not script.is_file():
            self.skipTest(
                f"{relative} 가 //tools/aws:unit_test 의 data 에 없어 runfiles 로 읽을 수 없다"
            )
        text = script.read_text()
        self.assertRegex(
            text,
            r"""say\(\) \{ printf '==> \[\+%ss\] %s\\n' "\$\(\(\$\(date \+%s\) - started\)\)" "\$\*"; \}""",
        )
        self.assertRegex(text, r"(?m)^started=\$\(date \+%s\)$")


if __name__ == "__main__":
    unittest.main()
