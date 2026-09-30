"""환경 올리기·내리기 순서와 안전장치의 격리 시험. 네트워크·AWS를 호출하지 않는다."""

import json
import unittest

from tools.aws.lifecycle import (
    Cloudflare,
    GitHub,
    Target,
    down,
    environment_alb,
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


class FakeAws:
    """명령 앞부분으로 응답을 고르고 호출 순서를 기록한다."""

    def __init__(self, state="stopped", instances=1, secrets=(), alb_after=0):
        self.state = state
        self.instances = instances
        self.secrets = list(secrets)
        self.alb_after = alb_after
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

    def point(self, domain, target, region):
        self.pointed.append((domain, target, region))


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

    def test_alb_is_found_by_environment_security_group(self):
        self.assertEqual(environment_alb(FakeAws(), "dev"), ALB)
        self.assertIsNone(environment_alb(FakeAws(alb_after=1), "dev"))
        aws = FakeAws()
        environment_alb(aws, "dev")
        self.assertIn("Name=group-name,Values=scenetrip-dev-alb", aws.calls[0])

    def test_up_points_dns_before_deploy_finishes_and_requires_success(self):
        aws = FakeAws()
        github = FakeGitHub(conclusions=["success"])
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
                )
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


if __name__ == "__main__":
    unittest.main()
