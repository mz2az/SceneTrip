"""수동 AWS 배포의 단일 실행 경로. just 레시피가 Bazel로 실행한다."""

import argparse
import json
import os
import re
import shutil
import socket
import ssl
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

from tools.aws.alb import alb_values, verify_nodeclass, wait_for_alb
from tools.aws.config import Runner, Settings, matched, output_values, workspace
from tools.aws.deploy import (
    delete_transient,
    deploy,
    gateway_values,
    validate_environment_outputs,
)


def preflight(run, sha):
    matched(r"[0-9a-f]{40}", sha, "Git SHA")
    run(["git", "merge-base", "--is-ancestor", sha, "origin/main"], quiet=True)
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with Path(output).open("a", encoding="utf-8") as stream:
            stream.write(f"sha={sha}\n")
    print(f"main 포함 커밋 검증 완료: {sha}")


def validate_source(run, settings):
    head = run(["git", "rev-parse", "HEAD"], quiet=True).strip()
    if head != settings.sha:
        raise ValueError("체크아웃된 커밋과 AWS_COMMIT_SHA가 다릅니다")
    if os.environ.get("GITHUB_ACTIONS") == "true":
        if os.environ.get("GITHUB_REF") != "refs/heads/main":
            raise ValueError("AWS workflow는 main 브랜치에서만 실행합니다")
        run(
            ["git", "merge-base", "--is-ancestor", settings.sha, "origin/main"],
            quiet=True,
        )
    dirty = run(["git", "status", "--porcelain", "--untracked-files=all"], quiet=True)
    if dirty.strip():
        raise ValueError("변경된 작업 트리에서는 배포하지 않습니다")


def validate_identity(run, settings, operation="apply"):
    identity = json.loads(
        run(["aws", "sts", "get-caller-identity", "--output", "json"], quiet=True)
    )
    if identity["Account"] != settings.account:
        raise ValueError("실제 AWS 계정이 선택한 환경과 다릅니다")
    role_arn = (
        os.environ["AWS_BOOTSTRAP_ROLE_ARN"]
        if operation in {"bootstrap-plan", "bootstrap-apply"}
        else settings.role
    )
    role = role_arn.rsplit("/", 1)[-1]
    expected = rf"arn:aws:sts::{settings.account}:assumed-role/{re.escape(role)}/[^/]+"
    if not re.fullmatch(expected, identity.get("Arn", "")):
        raise ValueError("실제 AWS 세션이 선택한 환경의 역할과 다릅니다")


def isolated_terraform(run, root, temp):
    source = root / "platform/terraform/aws"
    destination = temp / "terraform-source"
    destination.mkdir()
    tracked = run(
        ["git", "ls-files", "platform/terraform/aws"], quiet=True
    ).splitlines()
    for name in tracked:
        relative = Path(name).relative_to("platform/terraform/aws")
        if relative.parent == Path(".") and (
            relative.suffix == ".tf" or relative.name == ".terraform.lock.hcl"
        ):
            shutil.copyfile(source / relative, destination / relative)
    if not (destination / "versions.tf").is_file():
        raise ValueError(
            "추적된 Terraform 소스가 없습니다. 검토 후 커밋한 소스만 배포합니다"
        )
    return destination


def configure_tools(temp, provider_runfile):
    from python.runfiles import runfiles

    provider = Path(runfiles.Create().Rlocation(provider_runfile))
    config = temp / "terraform.rc"
    config.write_text(
        "provider_installation {\n  filesystem_mirror {\n"
        + f"    path = {json.dumps(str(provider.parents[3]))}\n  }}\n}}\n"
    )
    os.environ["TF_CLI_CONFIG_FILE"] = str(config)
    os.environ["TF_IN_AUTOMATION"] = "true"
    os.environ["TF_DATA_DIR"] = str(temp / "terraform-data")
    os.environ["KUBECONFIG"] = str(temp / "kubeconfig")


def variables(settings):
    config = json.loads(os.environ["TF_VAR_FILE_JSON"])
    for name, expected in (
        ("environment", settings.environment),
        ("aws_account_id", settings.account),
        ("aws_region", settings.region),
    ):
        if config.get(name) != expected:
            raise ValueError(f"Terraform {name} 입력과 배포 환경이 다릅니다")
    gateway_values(settings, config)
    return config


def initialize_terraform(run, root, settings, temp):
    directory = isolated_terraform(run, root, temp)
    bucket = (
        f"scenetrip-tfstate-{settings.account}-{settings.region}-{settings.environment}"
    )
    run(
        [
            "terraform",
            "init",
            "-input=false",
            "-lockfile=readonly",
            "-reconfigure",
            f"-backend-config=bucket={bucket}",
            f"-backend-config=key=scenetrip/{settings.environment}/terraform.tfstate",
            f"-backend-config=region={settings.region}",
            "-backend-config=encrypt=true",
            "-backend-config=use_lockfile=true",
        ],
        cwd=directory,
    )
    return directory


def terraform(run, root, settings, temp, operation):
    config = variables(settings)
    path = temp / "terraform.tfvars.json"
    path.write_text(json.dumps(config))
    path.chmod(0o600)
    directory = initialize_terraform(run, root, settings, temp)
    run(["terraform", "validate", "-no-color"], cwd=directory)
    plan = temp / "plan.tfplan"
    run(
        [
            "terraform",
            "plan",
            "-input=false",
            "-no-color",
            f"-var-file={path}",
            f"-out={plan}",
        ],
        cwd=directory,
    )
    if operation == "plan":
        return None
    run(["terraform", "apply", "-input=false", "-no-color", str(plan)], cwd=directory)
    return output_values(
        run(["terraform", "output", "-json"], quiet=True, cwd=directory)
    )


def verify_certificate(run, settings, outputs):
    certificate = json.loads(
        run(
            [
                "aws",
                "acm",
                "describe-certificate",
                "--certificate-arn",
                outputs["ingress_certificate_arn"],
                "--output",
                "json",
            ],
            quiet=True,
        )
    )["Certificate"]
    if certificate["Status"] != "ISSUED":
        raise ValueError("ACM 인증서가 ISSUED 상태가 아닙니다")

    def covers(name):
        if name.startswith("*."):
            return settings.domain.count(".") == name.count(
                "."
            ) and settings.domain.endswith(name[1:])
        return settings.domain == name

    if not any(covers(name) for name in certificate["SubjectAlternativeNames"]):
        raise ValueError("ACM 인증서가 API 도메인을 포함하지 않습니다")


def publish(run, outputs, settings, temp):
    data = json.loads(
        run(["aws", "ecr", "get-authorization-token", "--output", "json"], quiet=True)
    )["authorizationData"]
    registry = f"{settings.account}.dkr.ecr.{settings.region}.amazonaws.com"
    token = next(
        (
            entry["authorizationToken"]
            for entry in data
            if entry["proxyEndpoint"] == f"https://{registry}"
        ),
        None,
    )
    if not token:
        raise ValueError("선택한 AWS 계정의 ECR 인증을 받지 못했습니다")
    docker = temp / "docker"
    docker.mkdir(mode=0o700)
    config = docker / "config.json"
    config.write_text(json.dumps({"auths": {registry: {"auth": token}}}))
    config.chmod(0o600)
    for key, target in (
        ("scene_api", "//services/scene-api:push"),
        ("trip_guide", "//agents/trip-guide:push"),
        ("migration", "//services/scene-api:migration_push"),
    ):
        repository = outputs["ecr_repository_urls"][key]
        expected = (
            f"{registry}/scenetrip-{settings.environment}/"
            + {
                "scene_api": "scene-api",
                "trip_guide": "trip-guide",
                "migration": "migration",
            }[key]
        )
        if repository != expected:
            raise ValueError("Terraform ECR 출력이 선택한 계정·환경과 다릅니다")
        run(
            [
                "just",
                "run",
                target,
                "--",
                "--repository",
                repository,
                "--tag",
                settings.tag,
            ],
            env={"DOCKER_CONFIG": str(docker)},
        )


def verify_https_once(settings, hostname):
    # 인증 없는 읽기 요청과 차단 경로만 확인한다. 사용자 데이터 생성/변경은 하지 않는다.
    matched(
        rf"[a-zA-Z0-9.-]+\.elb(?:\.{re.escape(settings.region)})?\.amazonaws\.com",
        hostname,
        "ALB DNS 이름",
    )

    def addresses(name):
        return {
            entry[4][0]
            for entry in socket.getaddrinfo(name, 443, type=socket.SOCK_STREAM)
        }

    if not addresses(settings.domain).intersection(addresses(hostname)):
        raise ValueError(
            "API 도메인이 이번 환경의 ALB를 가리키지 않습니다. 출력된 ALB로 DNS를 연결하세요"
        )
    for path, expected in (
        ("/v1/contents", 200),
        ("/v1/actuator/health", 404),
        ("/v1/internal", 404),
    ):
        request = urllib.request.Request(f"https://{settings.domain}{path}")
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            status = error.code
            if status in {502, 503, 504}:
                raise ConnectionError("ALB 대상의 HTTPS 응답 준비 대기") from None
        except urllib.error.URLError as error:
            if isinstance(error.reason, ssl.SSLError):
                # 입력 타입 검사가 아니라 TLS 오류를 재시도 대상에서 분리하는 분기다.
                raise RuntimeError("HTTPS TLS 인증서 검증에 실패했습니다") from None  # noqa: TRY004
            raise ConnectionError("HTTPS 연결 준비 대기") from None
        if status != expected:
            raise RuntimeError(
                f"HTTPS 검증 실패: {path}, 예상 {expected}, 실제 {status}"
            )
    print("HTTPS 인증서·공개 읽기 API·내부 경로 차단 확인 완료")


def verify_https(settings, hostname, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        try:
            verify_https_once(settings, hostname)
            return
        except (socket.gaierror, ConnectionError, TimeoutError):
            if time.monotonic() >= deadline:
                raise RuntimeError(
                    "HTTPS DNS·연결·ALB 응답 대기 시간을 초과했습니다"
                ) from None
            time.sleep(5)


def verify_existing(run, root, settings, temp):
    directory = initialize_terraform(run, root, settings, temp)
    outputs = output_values(
        run(["terraform", "output", "-json"], quiet=True, cwd=directory)
    )
    validate_environment_outputs(settings, outputs)
    alb_values(outputs)
    run(
        [
            "aws",
            "eks",
            "update-kubeconfig",
            "--name",
            settings.cluster,
            "--region",
            settings.region,
        ],
        quiet=True,
    )
    verify_nodeclass(run, outputs)
    hostname = wait_for_alb(run, settings, outputs)
    print(f"검증할 ALB DNS: {hostname}")
    verify_https(settings, hostname)


def bootstrap(run, root, settings, operation):
    repository = matched(
        r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+",
        os.environ["GITHUB_REPOSITORY"],
        "GitHub 저장소",
    )
    provider = matched(
        rf"arn:aws:iam::{settings.account}:oidc-provider/token.actions.githubusercontent.com",
        os.environ["GITHUB_OIDC_PROVIDER_ARN"],
        "OIDC 공급자",
    )
    name = f"scenetrip-{settings.environment}-bootstrap"
    stacks = json.loads(
        run(["aws", "cloudformation", "list-stacks", "--output", "json"], quiet=True)
    )["StackSummaries"]
    exists = any(
        stack["StackName"] == name and stack["StackStatus"] != "DELETE_COMPLETE"
        for stack in stacks
    )
    change = f"manual-{settings.tag}"
    run(
        [
            "aws",
            "cloudformation",
            "create-change-set",
            "--stack-name",
            name,
            "--change-set-name",
            change,
            "--change-set-type",
            "UPDATE" if exists else "CREATE",
            "--capabilities",
            "CAPABILITY_NAMED_IAM",
            "--template-body",
            "file://" + str(root / "platform/terraform/bootstrap/template.json"),
            "--parameters",
            f"ParameterKey=Environment,ParameterValue={settings.environment}",
            f"ParameterKey=GitHubRepository,ParameterValue={repository}",
            f"ParameterKey=GitHubOidcProviderArn,ParameterValue={provider}",
        ],
        quiet=True,
    )
    try:
        run(
            [
                "aws",
                "cloudformation",
                "wait",
                "change-set-create-complete",
                "--stack-name",
                name,
                "--change-set-name",
                change,
            ],
            quiet=True,
        )
    except RuntimeError:
        result = json.loads(
            run(
                [
                    "aws",
                    "cloudformation",
                    "describe-change-set",
                    "--stack-name",
                    name,
                    "--change-set-name",
                    change,
                ],
                quiet=True,
            )
        )
        if re.search(
            "didn't contain changes|No updates", result.get("StatusReason", "")
        ):
            print("부트스트랩 변경 없음")
            return
        raise
    run(
        [
            "aws",
            "cloudformation",
            "describe-change-set",
            "--stack-name",
            name,
            "--change-set-name",
            change,
            "--query",
            "Changes",
            "--output",
            "json",
        ]
    )
    if operation == "bootstrap-apply":
        run(
            [
                "aws",
                "cloudformation",
                "execute-change-set",
                "--stack-name",
                name,
                "--change-set-name",
                change,
            ],
            quiet=True,
        )
        run(
            [
                "aws",
                "cloudformation",
                "wait",
                "stack-update-complete" if exists else "stack-create-complete",
                "--stack-name",
                name,
            ],
            quiet=True,
        )
        run(
            [
                "aws",
                "cloudformation",
                "describe-stacks",
                "--stack-name",
                name,
                "--query",
                "Stacks[0].Outputs",
                "--output",
                "json",
            ]
        )


def render(run, root, environment):
    values = {
        "database": {"host": "database.example.internal"},
        "sceneApi": {"image": "example.invalid/scene-api:" + "a" * 40},
        "tripGuide": {"image": "example.invalid/trip-guide:" + "a" * 40},
        "gateway": {
            "host": "api.example.com",
            "certificateArn": "arn:aws:acm:ap-northeast-2:123456789012:certificate/00000000-0000-0000-0000-000000000000",
            "allowedCidrs": ["192.0.2.1/32"],
            "albSubnetIds": ["subnet-aaaaaaaaaaaaaaaaa", "subnet-bbbbbbbbbbbbbbbbb"],
            "trustedProxyCidrs": ["10.40.0.0/24", "10.40.1.0/24"],
            "securityGroupId": "sg-aaaaaaaaaaaaaaaaa",
        },
        "network": {"dnsCidr": "172.20.0.10/32"},
    }
    command = [
        "helm",
        "template",
        "scenetrip",
        str(root / "platform/helm/scenetrip"),
        "--namespace",
        "scenetrip",
        "--values",
        str(root / f"platform/helm/scenetrip/values-{environment}.yaml"),
        "--values",
        "-",
    ]
    run(command, stdin=json.dumps(values))


def main():
    parser = argparse.ArgumentParser(description="SceneTrip 수동 AWS 배포")
    parser.add_argument("--provider-runfile", required=True)
    parser.add_argument(
        "operation",
        choices=[
            "preflight",
            "validate",
            "verify",
            "plan",
            "apply",
            "bootstrap-plan",
            "bootstrap-apply",
            "render",
            "cleanup",
            "tf-check",
        ],
    )
    parser.add_argument("environment", help="dev | prd; preflight는 후보 SHA")
    args = parser.parse_args()
    root = workspace()
    run = Runner(root)
    if args.operation == "preflight":
        preflight(run, args.environment)
        return
    matched(r"dev|prd", args.environment, "환경")
    if args.operation == "render":
        render(run, root, args.environment)
        return
    if args.operation == "tf-check":
        with tempfile.TemporaryDirectory(prefix="scenetrip-tf-check-") as temporary:
            temp = Path(temporary)
            configure_tools(temp, args.provider_runfile)
            directory = root / "platform/terraform/aws"
            run(["terraform", "fmt", "-check", "-recursive"], cwd=directory)
            run(
                [
                    "terraform",
                    "init",
                    "-backend=false",
                    "-input=false",
                    "-lockfile=readonly",
                ],
                cwd=directory,
            )
            run(["terraform", "validate", "-no-color"], cwd=directory)
        return
    settings = Settings.from_environment(args.environment)
    validate_source(run, settings)
    if args.operation == "validate":
        if os.environ.get("AWS_BOOTSTRAP_ROLE_ARN"):
            matched(
                rf"arn:aws:iam::{settings.account}:role/[A-Za-z0-9+=,.@_/-]+",
                os.environ["AWS_BOOTSTRAP_ROLE_ARN"],
                "부트스트랩 역할",
            )
            matched(
                rf"arn:aws:iam::{settings.account}:oidc-provider/token.actions.githubusercontent.com",
                os.environ["GITHUB_OIDC_PROVIDER_ARN"],
                "OIDC 공급자",
            )
        else:
            variables(settings)
        print(
            f"입력 검증 완료: {settings.environment} / {settings.account} / {settings.sha}"
        )
        return
    validate_identity(run, settings, args.operation)
    print(
        f"대상 환경: {settings.environment}, AWS 계정: {settings.account}, 리전: {settings.region}"
    )
    if args.operation.startswith("bootstrap-"):
        bootstrap(run, root, settings, args.operation)
        return
    with tempfile.TemporaryDirectory(prefix="scenetrip-aws-") as temporary:
        temp = Path(temporary)
        configure_tools(temp, args.provider_runfile)
        if args.operation == "verify":
            verify_existing(run, root, settings, temp)
            return
        if args.operation == "cleanup":
            run(
                [
                    "aws",
                    "eks",
                    "update-kubeconfig",
                    "--name",
                    settings.cluster,
                    "--region",
                    settings.region,
                ],
                quiet=True,
            )
            delete_transient(run)
            return
        outputs = terraform(run, root, settings, temp, args.operation)
        if outputs is not None:
            verify_certificate(run, settings, outputs)
            publish(run, outputs, settings, temp)
            hostname = deploy(run, root, settings, outputs)
            verify_https(settings, hostname)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, RuntimeError, OSError) as error:
        raise SystemExit(f"배포 중단: {error}") from None
