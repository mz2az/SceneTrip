"""환경 올리기·내리기 순서와 안전장치의 격리 시험. 네트워크·AWS를 호출하지 않는다."""

import json
import unittest
import unittest.mock

from tools.aws.lifecycle import (
    Cloudflare,
    GitHub,
    Target,
    down,
    environment_alb,
    point_signoz,
    restore_secrets,
    start_runner,
    stop_runner,
    up,
)

TARGET = Target(
    "dev",
    "123456789012",
    "ap-northeast-2",
    "api-dev.example.com",
    "a" * 40,
    "owner/repo",
)
ALB = "k8s-scenetri-gateway-abc-123.ap-northeast-2.elb.amazonaws.com"
SIGNOZ_ALB = "k8s-signoz-signozui-def-456.ap-northeast-2.elb.amazonaws.com"


class FakeAws:
    """명령 앞부분으로 응답을 고르고 호출 순서를 기록한다."""

    def __init__(
        self, state="stopped", instances=1, secrets=(), alb_after=0, signoz=False
    ):
        self.state = state
        self.instances = instances
        self.secrets = list(secrets)
        self.alb_after = alb_after
        # SigNoz 화면 ALB(ADR 0025) — 기본은 없다(화면 인증서를 비운 환경).
        self.signoz = signoz
        self.alb_queries = 0
        self.calls = []

    def __call__(self, command, **unused):
        self.calls.append(command)
        key = tuple(command[1:3])
        if key == ("ec2", "describe-instances"):
            items = [
                {
                    "InstanceId": f"i-0{index}23456789abcdef0",
                    "State": {"Name": self.state},
                }
                for index in range(self.instances)
            ]
            return json.dumps({"Reservations": [{"Instances": items}]})
        if key == ("ec2", "start-instances"):
            self.state = "running"
        if key == ("ec2", "stop-instances"):
            self.state = "stopped"
        if key == ("secretsmanager", "list-secrets"):
            return json.dumps({"SecretList": self.secrets})
        if key == ("ec2", "describe-security-groups"):
            if any(part.endswith("-signoz-alb") for part in command):
                groups = [{"GroupId": "sg-0signoz"}] if self.signoz else []
                return json.dumps({"SecurityGroups": groups})
            return json.dumps({"SecurityGroups": [{"GroupId": "sg-0alb"}]})
        if key == ("elbv2", "describe-load-balancers"):
            self.alb_queries += 1
            ready = self.alb_queries > self.alb_after
            balancers = [
                {"DNSName": ALB, "Type": "application", "SecurityGroups": ["sg-0alb"]},
                {
                    "DNSName": "other.elb",
                    "Type": "application",
                    "SecurityGroups": ["sg-x"],
                },
                {
                    "DNSName": SIGNOZ_ALB,
                    "Type": "application",
                    "SecurityGroups": ["sg-0signoz"],
                },
            ]
            return json.dumps({"LoadBalancers": balancers if ready else balancers[1:]})
        return ""

    def names(self):
        return [" ".join(call[1:3]) for call in self.calls]


class FakeGitHub:
    """dispatch 마다 새 run 을 만들고, 정해 둔 결론으로 완료시킨다."""

    def __init__(self, conclusions=("success",), active=()):
        self.conclusions = list(conclusions)
        self.runs = {}
        self.dispatched = []
        self.active_urls = list(active)
        self.polls = 0

    def __call__(self, method, url, token, body=None):
        path = url.split("/repos/owner/repo", 1)[1]
        if method == "POST" and path.endswith("/dispatches"):
            workflow = path.split("/")[3]
            run_id = 100 + len(self.runs)
            self.runs[run_id] = {
                "id": run_id,
                "workflow": workflow,
                "event": "workflow_dispatch",
                "status": "completed",
                "conclusion": self.conclusions.pop(0),
                "html_url": f"https://github.test/runs/{run_id}",
            }
            self.dispatched.append((workflow, body["inputs"]))
            return 204, {}
        if method == "GET" and "/runs?" in path:
            workflow = path.split("/")[3]
            runs = [run for run in self.runs.values() if run["workflow"] == workflow]
            runs += [
                {
                    "id": 1,
                    "event": "workflow_dispatch",
                    "status": "in_progress",
                    "html_url": url,
                }
                for url in self.active_urls
            ]
            return 200, {"workflow_runs": runs}
        if method == "GET" and path.startswith("/actions/runs/"):
            self.polls += 1
            run = dict(self.runs[int(path.rsplit("/", 1)[1])])
            if self.polls == 1:
                run["status"] = "in_progress"
            return 200, run
        raise AssertionError(f"예상하지 않은 GitHub 호출: {method} {path}")


class FakeCloudflare:
    def __init__(self):
        self.pointed = []
        self.proxied = []

    def point(self, domain, target, region, proxied=False):
        self.pointed.append((domain, target, region))
        self.proxied.append(proxied)


def github_for(fake):
    return GitHub("token", "owner/repo", http=fake, sleep=lambda seconds: None)


class LifecycleTest(unittest.TestCase):
    def test_target_rejects_malformed_inputs(self):
        for field, value in (
            ("environment", "stage"),
            ("account", "1234"),
            ("domain", "api dev.example.com"),
            ("sha", "main"),
            ("repository", "owner"),
        ):
            values = TARGET.__dict__ | {field: value}
            with self.subTest(field=field), self.assertRaises(ValueError):
                Target(**values)

    def test_runner_must_be_exactly_one_tagged_instance(self):
        for count in (0, 2):
            with (
                self.subTest(count=count),
                self.assertRaisesRegex(ValueError, "runner"),
            ):
                start_runner(FakeAws(instances=count), "dev")
        aws = FakeAws()
        start_runner(aws, "dev")
        filters = aws.calls[0]
        for tag in (
            "Name=tag:project,Values=scenetrip",
            "Name=tag:environment,Values=dev",
            "Name=tag:role,Values=github-runner",
        ):
            self.assertIn(tag, filters)
        self.assertEqual(
            aws.names(),
            ["ec2 describe-instances", "ec2 start-instances", "ec2 wait"],
        )

    def test_running_runner_is_not_restarted(self):
        aws = FakeAws(state="running")
        start_runner(aws, "dev")
        self.assertNotIn("ec2 start-instances", aws.names())

    def test_runner_stays_on_while_environment_run_is_active(self):
        aws = FakeAws(state="running")
        github = github_for(FakeGitHub(active=["https://github.test/runs/busy"]))
        self.assertFalse(stop_runner(aws, github, "dev"))
        self.assertEqual(aws.calls, [])
        idle = FakeAws(state="running")
        self.assertTrue(stop_runner(idle, github_for(FakeGitHub()), "dev"))
        self.assertIn("ec2 stop-instances", idle.names())

    def test_only_scheduled_deletions_in_exact_environment_path_are_restored(self):
        aws = FakeAws(
            secrets=[
                {
                    "Name": "/scenetrip/dev/database",
                    "ARN": "arn:db",
                    "DeletedDate": "2026",
                },
                {"Name": "/scenetrip/dev/scene-api", "ARN": "arn:api"},
                {
                    "Name": "/scenetrip/devx/other",
                    "ARN": "arn:x",
                    "DeletedDate": "2026",
                },
                {
                    "Name": "/scenetrip/prd/database",
                    "ARN": "arn:prd",
                    "DeletedDate": "2026",
                },
            ]
        )
        self.assertEqual(restore_secrets(aws, "dev"), ["/scenetrip/dev/database"])
        restores = [call for call in aws.calls if call[2] == "restore-secret"]
        self.assertEqual(
            restores,
            [["aws", "secretsmanager", "restore-secret", "--secret-id", "arn:db"]],
        )

    def test_only_the_three_managed_secret_names_are_restored(self):
        # MZ2AZ-317 — Terraform 이 관리하는 이름(scene-api·database·auth)만 되살린다. 없앤
        # trip-guide 처럼 같은 경로 아래 다른 비밀값, 이름이 비슷한 것, 다른 환경은 손대지 않는다.
        scheduled = "2026-10-12"
        for environment, other in (("dev", "prd"), ("prd", "dev")):
            managed = [
                f"/scenetrip/{environment}/{name}"
                for name in ("scene-api", "database", "auth")
            ]
            untouched = [
                f"/scenetrip/{environment}/trip-guide",
                f"/scenetrip/{environment}/scene_api",
                f"/scenetrip/{environment}/auth-old",
                f"/scenetrip/{environment}/database/extra",
                f"/scenetrip/{environment}/",
                f"/scenetrip/{environment}x/auth",
                f"/scenetrip/{other}/auth",
                f"/scenetrip/{other}/scene-api",
                f"/scenetrip/{other}/trip-guide",
            ]
            secrets = [
                {"Name": name, "ARN": f"arn:{name}", "DeletedDate": scheduled}
                for name in managed + untouched
            ]
            with self.subTest(environment=environment):
                aws = FakeAws(secrets=secrets)
                with unittest.mock.patch("builtins.print"):
                    restored = restore_secrets(aws, environment)
                self.assertEqual(sorted(restored), sorted(managed))
                restores = [call for call in aws.calls if call[2] == "restore-secret"]
                self.assertEqual(
                    sorted(call[-1] for call in restores),
                    sorted(f"arn:{name}" for name in managed),
                )

    def test_leftover_trip_guide_secret_alone_is_not_restored(self):
        aws = FakeAws(
            secrets=[
                {
                    "Name": "/scenetrip/dev/trip-guide",
                    "ARN": "arn:guide",
                    "DeletedDate": "2026-10-12",
                },
                # 삭제 예약이 아닌 관리 비밀값도 되살리지 않는다.
                {"Name": "/scenetrip/dev/auth", "ARN": "arn:auth"},
            ]
        )
        with unittest.mock.patch("builtins.print"):
            self.assertEqual(restore_secrets(aws, "dev"), [])
        self.assertFalse(any(call[2] == "restore-secret" for call in aws.calls))

    def test_alb_is_found_by_environment_security_group(self):
        self.assertEqual(environment_alb(FakeAws(), "dev"), ALB)
        self.assertIsNone(environment_alb(FakeAws(alb_after=1), "dev"))
        aws = FakeAws()
        environment_alb(aws, "dev")
        self.assertIn("Name=group-name,Values=scenetrip-dev-alb", aws.calls[0])

    def test_up_points_dns_before_deploy_finishes_and_requires_success(self):
        aws = FakeAws()
        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = FakeCloudflare()
        up(aws, github_for(github), cloudflare, TARGET)
        self.assertEqual(
            github.dispatched,
            [
                (
                    "aws-deploy.yml",
                    {
                        "environment": "dev",
                        "operation": "apply",
                        "commit_sha": "a" * 40,
                    },
                ),
                (
                    "aws-deploy.yml",
                    {
                        "environment": "dev",
                        "operation": "data-load",
                        "commit_sha": "a" * 40,
                    },
                ),
            ],
        )
        # 첫 폴링(in_progress) 동안 DNS 가 먼저 바뀐다.
        self.assertEqual(
            cloudflare.pointed[0], ("api-dev.example.com", ALB, "ap-northeast-2")
        )
        self.assertEqual(len(cloudflare.pointed), 1)
        names = aws.names()
        self.assertLess(
            names.index("ec2 start-instances"),
            names.index("secretsmanager list-secrets"),
        )

        failed = FakeGitHub(conclusions=["failure"])
        with self.assertRaisesRegex(RuntimeError, "배포 실패"):
            up(FakeAws(), github_for(failed), FakeCloudflare(), TARGET)
        # 배포가 실패하면 데이터 적재를 부르지 않는다.
        self.assertEqual(
            [inputs["operation"] for _, inputs in failed.dispatched], ["apply"]
        )

    def test_up_requires_data_load_success_before_reporting_done(self):
        github = FakeGitHub(conclusions=["success", "failure"])
        with (
            self.assertRaisesRegex(RuntimeError, "데이터 적재"),
            unittest.mock.patch("builtins.print") as printed,
        ):
            up(FakeAws(), github_for(github), FakeCloudflare(), TARGET)
        self.assertEqual(
            [inputs["operation"] for _, inputs in github.dispatched],
            ["apply", "data-load"],
        )
        self.assertFalse(
            any("올리기 완료" in str(call) for call in printed.call_args_list)
        )

    def test_up_loads_data_only_after_dns_points_at_alb(self):
        # ALB 를 끝내 못 찾으면 DNS 가 안 바뀌었으므로 적재도 하지 않는다.
        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = FakeCloudflare()
        with self.assertRaisesRegex(RuntimeError, "ALB"):
            up(FakeAws(alb_after=100), github_for(github), cloudflare, TARGET)
        self.assertEqual(cloudflare.pointed, [])
        self.assertEqual(
            [inputs["operation"] for _, inputs in github.dispatched], ["apply"]
        )

    def test_down_plans_then_destroys_and_retries_once(self):
        github = FakeGitHub(conclusions=["success", "failure", "success"])
        down(FakeAws(), github_for(github), TARGET, "retain")
        operations = [inputs["operation"] for _, inputs in github.dispatched]
        self.assertEqual(operations, ["plan", "destroy", "destroy"])
        destroy = github.dispatched[1][1]
        self.assertEqual(destroy["confirmation"], "DELETE dev 123456789012")
        self.assertEqual(destroy["scope"], "service")
        self.assertEqual(destroy["purge_state"], "false")
        self.assertNotIn("confirmation", github.dispatched[0][1])

        with self.assertRaisesRegex(RuntimeError, "두 번"):
            down(
                FakeAws(),
                github_for(FakeGitHub(conclusions=["success", "failure", "failure"])),
                TARGET,
                "retain",
            )
        stopped = FakeGitHub(conclusions=["failure"])
        with self.assertRaisesRegex(RuntimeError, "삭제 계획"):
            down(FakeAws(), github_for(stopped), TARGET, "retain")
        self.assertEqual(len(stopped.dispatched), 1)


class CloudflareTest(unittest.TestCase):
    def fake(self, records):
        calls = []

        def http(method, url, token, body=None):
            calls.append((method, url.split("/client/v4", 1)[1], body))
            path = url.split("/client/v4", 1)[1]
            if path.startswith("/zones?name=example.com"):
                return 200, {"success": True, "result": [{"id": "zone1"}]}
            if path.startswith("/zones?name="):
                return 200, {"success": True, "result": []}
            if method == "GET":
                return 200, {"success": True, "result": records}
            return 200, {"success": True, "result": body}

        return http, calls

    def test_existing_cname_is_updated_as_dns_only(self):
        http, calls = self.fake([{"id": "rec1", "type": "CNAME"}])
        Cloudflare("token", http).point("api-dev.example.com", ALB, "ap-northeast-2")
        method, path, body = calls[-1]
        self.assertEqual((method, path), ("PUT", "/zones/zone1/dns_records/rec1"))
        self.assertFalse(body["proxied"])
        self.assertEqual(body["ttl"], 60)
        self.assertEqual(body["content"], ALB)

    def test_missing_record_is_created(self):
        http, calls = self.fake([])
        Cloudflare("token", http).point("api-dev.example.com", ALB, "ap-northeast-2")
        self.assertEqual(calls[-1][:2], ("POST", "/zones/zone1/dns_records"))

    def test_rejects_foreign_target_and_conflicting_records(self):
        http, calls = self.fake([{"id": "a", "type": "A"}])
        client = Cloudflare("token", http)
        for target in ("evil.example.com", "x.us-east-1.elb.amazonaws.com"):
            with self.subTest(target=target), self.assertRaises(ValueError):
                client.point("api-dev.example.com", target, "ap-northeast-2")
        with self.assertRaisesRegex(ValueError, "CNAME이 아닌"):
            client.point("api-dev.example.com", ALB, "ap-northeast-2")
        self.assertFalse(any(method in {"PUT", "POST"} for method, _, _ in calls))

    def test_api_failure_and_missing_token_stop(self):
        with self.assertRaises(ValueError):
            Cloudflare("")

        def failing(method, url, token, body=None):
            return 403, {"success": False, "errors": [{"message": "denied"}]}

        with self.assertRaisesRegex(RuntimeError, "denied"):
            Cloudflare("token", failing).point(
                "api-dev.example.com", ALB, "ap-northeast-2"
            )


PRD_TARGET = Target(**(TARGET.__dict__ | {"environment": "prd"}))


class OrderedCloudflare(FakeCloudflare):
    """DNS 를 바꾼 순간 GitHub 에 이미 보낸 dispatch 수를 함께 적는다."""

    def __init__(self, github):
        super().__init__()
        self.github = github
        self.dispatched_before = []

    def point(self, domain, target, region, proxied=False):
        self.dispatched_before.append(len(self.github.dispatched))
        super().point(domain, target, region, proxied)


class SignozDnsTest(unittest.TestCase):
    """SigNoz 화면 주소(ADR 0025) — 전용 ALB 를 Cloudflare 프록시로 가리킨다. API 주소는 여전히 DNS only."""

    def fake(self, records, answer=None):
        calls = []

        def http(method, url, token, body=None):
            path = url.split("/client/v4", 1)[1]
            calls.append((method, path, body))
            if path.startswith("/zones?name=scenetrip.io"):
                return 200, {"success": True, "result": [{"id": "zone1"}]}
            if path.startswith("/zones?name="):
                return 200, {"success": True, "result": []}
            if method == "GET":
                return 200, {"success": True, "result": records}
            return 200, {"success": True, "result": {**body, **(answer or {})}}

        return http, calls

    def test_proxied_point_sends_and_verifies_proxy(self):
        http, calls = self.fake([{"id": "rec1", "type": "CNAME"}])
        with unittest.mock.patch("builtins.print"):
            Cloudflare("token", http).point(
                "signoz-dev.scenetrip.io", SIGNOZ_ALB, "ap-northeast-2", proxied=True
            )
        method, path, body = calls[-1]
        self.assertEqual((method, path), ("PUT", "/zones/zone1/dns_records/rec1"))
        self.assertIs(body["proxied"], True)
        self.assertEqual(body["content"], SIGNOZ_ALB)
        self.assertEqual(body["type"], "CNAME")

    def test_default_point_stays_dns_only(self):
        http, calls = self.fake([])
        with unittest.mock.patch("builtins.print"):
            Cloudflare("token", http).point(
                "signoz-dev.scenetrip.io", SIGNOZ_ALB, "ap-northeast-2"
            )
        self.assertIs(calls[-1][2]["proxied"], False)

    def test_result_differing_from_request_is_rejected(self):
        for proxied, answer in (
            (True, {"proxied": False}),
            (False, {"proxied": True}),
            (True, {"content": "other.ap-northeast-2.elb.amazonaws.com"}),
            (False, {"content": "other.ap-northeast-2.elb.amazonaws.com"}),
        ):
            http, _calls = self.fake([], answer)
            with (
                self.subTest(proxied=proxied, answer=answer),
                self.assertRaises(RuntimeError),
            ):
                Cloudflare("token", http).point(
                    "signoz-dev.scenetrip.io",
                    SIGNOZ_ALB,
                    "ap-northeast-2",
                    proxied=proxied,
                )

    def test_environment_alb_finds_signoz_alb_by_its_own_group(self):
        aws = FakeAws(signoz=True)
        self.assertEqual(
            environment_alb(aws, "dev", "scenetrip-dev-signoz-alb"), SIGNOZ_ALB
        )
        self.assertIn("Name=group-name,Values=scenetrip-dev-signoz-alb", aws.calls[0])
        # 기본 이름은 API ALB 다.
        self.assertEqual(environment_alb(FakeAws(signoz=True), "dev"), ALB)
        self.assertIsNone(environment_alb(FakeAws(), "dev", "scenetrip-dev-signoz-alb"))

    def test_point_signoz_proxies_dev_domain_to_signoz_alb(self):
        cloudflare = FakeCloudflare()
        point_signoz(FakeAws(signoz=True), cloudflare, TARGET)
        self.assertEqual(
            cloudflare.pointed,
            [("signoz-dev.scenetrip.io", SIGNOZ_ALB, "ap-northeast-2")],
        )
        self.assertEqual(cloudflare.proxied, [True])

    def test_point_signoz_without_alb_changes_nothing(self):
        cloudflare = FakeCloudflare()
        with unittest.mock.patch("builtins.print") as printed:
            point_signoz(FakeAws(), cloudflare, TARGET)
        self.assertEqual(cloudflare.pointed, [])
        printed.assert_called()

    def test_point_signoz_skips_environment_without_signoz(self):
        aws = FakeAws(signoz=True)
        cloudflare = FakeCloudflare()
        point_signoz(aws, cloudflare, PRD_TARGET)
        self.assertEqual(aws.calls, [])
        self.assertEqual(cloudflare.pointed, [])

    def test_up_points_signoz_after_api_and_before_data_load(self):
        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = OrderedCloudflare(github)
        with unittest.mock.patch("builtins.print"):
            up(FakeAws(signoz=True), github_for(github), cloudflare, TARGET)
        self.assertEqual(
            cloudflare.pointed,
            [
                ("api-dev.example.com", ALB, "ap-northeast-2"),
                ("signoz-dev.scenetrip.io", SIGNOZ_ALB, "ap-northeast-2"),
            ],
        )
        self.assertEqual(cloudflare.proxied, [False, True])
        # 둘 다 apply 하나만 보낸 뒤 — data-load 는 화면 주소를 바꾼 다음이다.
        self.assertEqual(cloudflare.dispatched_before, [1, 1])
        self.assertEqual(
            [inputs["operation"] for _, inputs in github.dispatched],
            ["apply", "data-load"],
        )

    def test_up_without_signoz_alb_still_loads_data(self):
        github = FakeGitHub(conclusions=["success", "success"])
        cloudflare = FakeCloudflare()
        with unittest.mock.patch("builtins.print"):
            up(FakeAws(), github_for(github), cloudflare, TARGET)
        self.assertEqual(cloudflare.proxied, [False])
        self.assertEqual(
            [inputs["operation"] for _, inputs in github.dispatched],
            ["apply", "data-load"],
        )


if __name__ == "__main__":
    unittest.main()
