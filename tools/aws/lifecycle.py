"""환경을 올리고 내리는 순서를 GitHub Actions 한 곳에서 실행한다.

기존 배포·삭제 workflow는 바꾸지 않고 workflow_dispatch로 호출한 뒤 결과를 기다린다.
AWS 권한은 OIDC로 받은 scenetrip-<env>-lifecycle 역할이다. 이 역할은 태그가 맞는
runner EC2의 전원, 해당 환경 Secret 복원, ALB 조회만 할 수 있다.
"""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass

from tools.aws.config import Runner, matched, workspace

DEPLOY_WORKFLOW = "aws-deploy.yml"
DESTROY_WORKFLOW = "aws-destroy.yml"
ENVIRONMENT_WORKFLOWS = (DEPLOY_WORKFLOW, DESTROY_WORKFLOW)
GITHUB_API = "https://api.github.com"
CLOUDFLARE_API = "https://api.cloudflare.com/client/v4"
DNS_TTL_SECONDS = 60


@dataclass(frozen=True)
class Target:
    environment: str
    account: str
    region: str
    domain: str
    sha: str
    repository: str

    def __post_init__(self):
        matched(r"dev|prd", self.environment, "환경")
        matched(r"[0-9]{12}", self.account, "AWS 계정")
        matched(r"[a-z]{2}-[a-z]+-[1-9]", self.region, "AWS 리전")
        matched(
            r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+",
            self.domain,
            "API 도메인",
        )
        matched(r"[0-9a-f]{40}", self.sha, "Git SHA")
        matched(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", self.repository, "GitHub 저장소")

    @classmethod
    def from_environment(cls, environment):
        return cls(
            environment,
            os.environ["AWS_ACCOUNT_ID"],
            os.environ["AWS_REGION"],
            os.environ["AWS_API_DOMAIN"],
            os.environ["AWS_COMMIT_SHA"],
            os.environ["GITHUB_REPOSITORY"],
        )


def http_json(method, url, token, body=None):
    request = urllib.request.Request(
        url,
        method=method,
        data=None if body is None else json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
            "Content-Type": "application/json",
            "User-Agent": "scenetrip-lifecycle",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw) if raw else {}
        except ValueError:
            return error.code, {}


def aws_json(run, *arguments):
    return json.loads(run(["aws", *arguments, "--output", "json"], quiet=True))


# ---------------------------------------------------------------- runner EC2


def runner_instance(run, environment):
    data = aws_json(
        run,
        "ec2",
        "describe-instances",
        "--filters",
        "Name=tag:project,Values=scenetrip",
        f"Name=tag:environment,Values={environment}",
        "Name=tag:role,Values=github-runner",
        "Name=instance-state-name,Values=pending,running,stopping,stopped",
    )
    instances = [
        instance
        for reservation in data.get("Reservations", [])
        for instance in reservation.get("Instances", [])
    ]
    if len(instances) != 1:
        raise ValueError(
            f"배포 runner EC2를 하나로 식별하지 못했습니다 ({len(instances)}대). "
            "project·environment·role 태그를 확인하세요"
        )
    instance = instances[0]
    return (
        matched(r"i-[0-9a-f]{8,17}", instance["InstanceId"], "EC2 ID"),
        instance["State"]["Name"],
    )


def start_runner(run, environment):
    instance, state = runner_instance(run, environment)
    if state == "stopping":
        run(
            ["aws", "ec2", "wait", "instance-stopped", "--instance-ids", instance],
            quiet=True,
        )
    if state != "running":
        run(["aws", "ec2", "start-instances", "--instance-ids", instance], quiet=True)
        run(
            ["aws", "ec2", "wait", "instance-running", "--instance-ids", instance],
            quiet=True,
        )
    print(f"배포 runner 켜짐: {instance}")
    return instance


def stop_runner(run, github, environment):
    busy = [
        url for workflow in ENVIRONMENT_WORKFLOWS for url in github.active(workflow)
    ]
    if busy:
        # 진행 중인 Terraform을 끊으면 state 잠금이 남는다. 끝난 뒤 다시 실행한다.
        print("배포·삭제 run이 아직 진행 중이라 runner를 끄지 않습니다:")
        for url in busy:
            print(f"  {url}")
        return False
    instance, state = runner_instance(run, environment)
    if state in {"pending", "running"}:
        run(["aws", "ec2", "stop-instances", "--instance-ids", instance], quiet=True)
        run(
            ["aws", "ec2", "wait", "instance-stopped", "--instance-ids", instance],
            quiet=True,
        )
    print(f"배포 runner 꺼짐: {instance}")
    return True


# ---------------------------------------------------------------- Secret


def restore_secrets(run, environment):
    prefix = f"/scenetrip/{environment}/"
    data = aws_json(
        run,
        "secretsmanager",
        "list-secrets",
        "--include-planned-deletion",
        "--filters",
        f"Key=name,Values={prefix}",
    )
    restored = []
    for secret in data.get("SecretList", []):
        name = secret.get("Name", "")
        # 필터는 접두 일치이므로 정확한 환경 경로인지 다시 확인한다.
        if not name.startswith(prefix) or not secret.get("DeletedDate"):
            continue
        run(
            ["aws", "secretsmanager", "restore-secret", "--secret-id", secret["ARN"]],
            quiet=True,
        )
        restored.append(name)
    print(
        "삭제 예약 Secret 복원: " + ", ".join(restored)
        if restored
        else "복원할 Secret 없음"
    )
    return restored


# ---------------------------------------------------------------- ALB·DNS


def environment_alb(run, environment):
    groups = aws_json(
        run,
        "ec2",
        "describe-security-groups",
        "--filters",
        f"Name=group-name,Values=scenetrip-{environment}-alb",
    ).get("SecurityGroups", [])
    if not groups:
        return None
    if len(groups) != 1:
        raise ValueError("ALB 보안 그룹을 하나로 식별하지 못했습니다")
    group = groups[0]["GroupId"]
    balancers = [
        item
        for item in aws_json(run, "elbv2", "describe-load-balancers").get(
            "LoadBalancers", []
        )
        if group in item.get("SecurityGroups", []) and item.get("Type") == "application"
    ]
    if not balancers:
        return None
    if len(balancers) != 1:
        raise ValueError("환경 ALB를 하나로 식별하지 못했습니다")
    return balancers[0]["DNSName"]


class Cloudflare:
    def __init__(self, token, http=http_json):
        if not token:
            raise ValueError("CLOUDFLARE_API_TOKEN이 필요합니다")
        self.token = token
        self.http = http

    def call(self, method, path, body=None):
        status, data = self.http(method, CLOUDFLARE_API + path, self.token, body)
        if status >= 300 or not data.get("success", False):
            messages = [error.get("message") for error in data.get("errors", [])]
            raise RuntimeError(f"Cloudflare API 실패 ({status}): {messages}")
        return data["result"]

    def zone(self, domain):
        labels = domain.split(".")
        for index in range(len(labels) - 1):
            name = ".".join(labels[index:])
            zones = self.call("GET", f"/zones?name={name}")
            if zones:
                return zones[0]["id"]
        raise ValueError(f"{domain}을 담는 Cloudflare 존이 토큰 범위에 없습니다")

    def point(self, domain, target, region):
        matched(
            rf"[a-zA-Z0-9.-]+\.{re.escape(region)}\.elb\.amazonaws\.com",
            target,
            "ALB DNS 이름",
        )
        zone = self.zone(domain)
        records = self.call("GET", f"/zones/{zone}/dns_records?name={domain}")
        if any(record["type"] != "CNAME" for record in records):
            raise ValueError(f"{domain}에 CNAME이 아닌 레코드가 있어 바꾸지 않습니다")
        # 프록시를 켜면 ALB가 보는 접속자가 Cloudflare가 되어 허용 CIDR·XFF 신뢰가 깨진다.
        body = {
            "type": "CNAME",
            "name": domain,
            "content": target,
            "proxied": False,
            "ttl": DNS_TTL_SECONDS,
            "comment": "SceneTrip ALB; managed by dev-lifecycle workflow",
        }
        if records:
            result = self.call(
                "PUT", f"/zones/{zone}/dns_records/{records[0]['id']}", body
            )
        else:
            result = self.call("POST", f"/zones/{zone}/dns_records", body)
        if result.get("proxied") or result.get("content") != target:
            raise RuntimeError("Cloudflare 레코드가 요청한 DNS only 값과 다릅니다")
        print(f"DNS 갱신: {domain} → {target} (DNS only)")
        return result


# ---------------------------------------------------------------- GitHub


class GitHub:
    def __init__(
        self, token, repository, http=http_json, sleep=time.sleep, clock=time.monotonic
    ):
        if not token:
            raise ValueError("GITHUB_TOKEN이 필요합니다")
        self.token = token
        self.repository = repository
        self.http = http
        self.sleep = sleep
        self.clock = clock

    def call(self, method, path, body=None, expected=(200,)):
        status, data = self.http(
            method, f"{GITHUB_API}/repos/{self.repository}{path}", self.token, body
        )
        if status not in expected:
            raise RuntimeError(f"GitHub API 실패 ({status}): {data.get('message', '')}")
        return data

    def runs(self, workflow):
        return self.call("GET", f"/actions/workflows/{workflow}/runs?per_page=20").get(
            "workflow_runs", []
        )

    def active(self, workflow):
        return [
            run["html_url"]
            for run in self.runs(workflow)
            if run["status"] != "completed"
        ]

    def dispatch(self, workflow, inputs, timeout=180):
        before = {run["id"] for run in self.runs(workflow)}
        self.call(
            "POST",
            f"/actions/workflows/{workflow}/dispatches",
            {"ref": "main", "inputs": inputs},
            expected=(204,),
        )
        deadline = self.clock() + timeout
        while self.clock() < deadline:
            created = [
                run
                for run in self.runs(workflow)
                if run["id"] not in before and run.get("event") == "workflow_dispatch"
            ]
            if created:
                run = min(created, key=lambda item: item["id"])
                print(f"{workflow} 실행: {run['html_url']}")
                return run
            self.sleep(5)
        raise RuntimeError(f"{workflow} 실행을 찾지 못했습니다")

    def wait(self, run, on_tick=None, timeout=4 * 3600, interval=30):
        deadline = self.clock() + timeout
        while True:
            current = self.call("GET", f"/actions/runs/{run['id']}")
            if current["status"] == "completed":
                return current
            if self.clock() > deadline:
                raise RuntimeError(f"대기 시간 초과: {current['html_url']}")
            if on_tick:
                on_tick()
            self.sleep(interval)


def require_success(run, label):
    if run.get("conclusion") != "success":
        raise RuntimeError(f"{label} 실패 ({run.get('conclusion')}): {run['html_url']}")


# ---------------------------------------------------------------- 흐름


def up(run, github, cloudflare, target):
    start_runner(run, target.environment)
    restore_secrets(run, target.environment)
    deployment = github.dispatch(
        DEPLOY_WORKFLOW,
        {
            "environment": target.environment,
            "operation": "apply",
            "commit_sha": target.sha,
        },
    )
    pointed = {"value": None}

    def point_dns():
        # 배포기의 마지막 HTTPS 검증이 새 ALB를 보도록 배포가 끝나기 전에 DNS를 바꾼다.
        try:
            balancer = environment_alb(run, target.environment)
        except RuntimeError as error:
            print(f"ALB 조회 재시도 예정: {error}")
            return
        if balancer and balancer != pointed["value"]:
            cloudflare.point(target.domain, balancer, target.region)
            pointed["value"] = balancer

    result = github.wait(deployment, on_tick=point_dns)
    require_success(result, "배포")
    point_dns()
    if not pointed["value"]:
        raise RuntimeError("배포는 끝났지만 환경 ALB를 찾지 못했습니다")
    print(f"올리기 완료: https://{target.domain}/v1")


def down(run, github, target, snapshot_policy):
    matched(r"retain|discard", snapshot_policy, "스냅샷 정책")
    start_runner(run, target.environment)
    common = {
        "environment": target.environment,
        "scope": "service",
        "commit_sha": target.sha,
        "snapshot_policy": snapshot_policy,
        "purge_state": "false",
    }
    plan = github.wait(
        github.dispatch(DESTROY_WORKFLOW, {**common, "operation": "plan"})
    )
    require_success(plan, "삭제 계획")
    confirmation = f"DELETE {target.environment} {target.account}"
    for attempt in (1, 2):
        result = github.wait(
            github.dispatch(
                DESTROY_WORKFLOW,
                {**common, "operation": "destroy", "confirmation": confirmation},
            )
        )
        if result.get("conclusion") == "success":
            print("내리기 완료. bootstrap·최종 스냅샷·runner는 유지합니다")
            return
        # 런북: 부분 삭제 뒤에는 같은 입력으로 다시 실행해 남은 리소스를 지운다.
        print(f"삭제 실패 {attempt}/2: {result['html_url']}")
    raise RuntimeError("두 번 시도했지만 서비스 삭제가 끝나지 않았습니다")


def main(argv=None):
    parser = argparse.ArgumentParser(description="환경 올리기·내리기")
    parser.add_argument("environment", choices=["dev", "prd"])
    parser.add_argument("action", choices=["up", "down", "stop-runner"])
    parser.add_argument(
        "--snapshot-policy", choices=["retain", "discard"], default="retain"
    )
    arguments = parser.parse_args(argv)
    target = Target.from_environment(arguments.environment)
    run = Runner(workspace())
    github = GitHub(os.environ.get("GITHUB_TOKEN", ""), target.repository)
    if arguments.action == "up":
        up(run, github, Cloudflare(os.environ.get("CLOUDFLARE_API_TOKEN", "")), target)
    elif arguments.action == "down":
        down(run, github, target, arguments.snapshot_policy)
    else:
        stop_runner(run, github, target.environment)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        print(f"중단: {error}", file=sys.stderr)
        sys.exit(1)
