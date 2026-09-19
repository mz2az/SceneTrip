"""AWS 배포 경계 검증과 비밀값을 출력하지 않는 프로세스 실행."""

import json
import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path

SECRET_KEYS = {
    "scene_api": {"KAKAO_REST_KEY"},
    "trip_guide": {"DEEPSEEK_API_KEY"},
    "database": {"username", "password", "migration_username", "migration_password"},
}


def matched(pattern, value, label):
    if not isinstance(value, str) or not re.fullmatch(pattern, value):
        raise ValueError(f"{label} 입력 형식이 올바르지 않습니다")
    return value


@dataclass(frozen=True)
class Settings:
    environment: str
    account: str
    region: str
    role: str
    sha: str
    domain: str
    run_id: str = "0"
    attempt: str = "1"

    def __post_init__(self):
        matched(r"dev|prd", self.environment, "환경")
        matched(r"[0-9]{12}", self.account, "AWS 계정")
        matched(r"[a-z]{2}-[a-z]+-[1-9]", self.region, "AWS 리전")
        matched(r"[0-9a-f]{40}", self.sha, "Git SHA")
        matched(
            r"[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+",
            self.domain,
            "API 도메인",
        )
        matched(r"[0-9]+", self.run_id, "실행 ID")
        matched(r"[1-9][0-9]*", self.attempt, "실행 횟수")
        expected = (
            f"arn:aws:iam::{self.account}:role/scenetrip-{self.environment}-deploy"
        )
        if self.role != expected:
            raise ValueError("배포 역할의 계정·환경이 입력과 다릅니다")

    @property
    def tag(self):
        return f"{self.sha}-{self.run_id}-{self.attempt}"

    @property
    def cluster(self):
        return f"scenetrip-{self.environment}"

    @classmethod
    def from_environment(cls, environment):
        return cls(
            environment,
            os.environ["AWS_ACCOUNT_ID"],
            os.environ["AWS_REGION"],
            os.environ["AWS_DEPLOY_ROLE_ARN"],
            os.environ["AWS_COMMIT_SHA"],
            os.environ["AWS_API_DOMAIN"],
            os.environ.get("GITHUB_RUN_ID", "0"),
            os.environ.get("GITHUB_RUN_ATTEMPT", "1"),
        )


def validate_secret(kind, value):
    if not isinstance(value, dict) or set(value) != SECRET_KEYS[kind]:
        raise ValueError(f"{kind} 비밀값 키가 허용 목록과 다릅니다")
    if any(
        not isinstance(item, str) or not item.strip() or "\x00" in item
        for item in value.values()
    ):
        raise ValueError(f"{kind} 비밀값은 비어 있지 않은 문자열이어야 합니다")
    if kind == "database" and (
        value["username"] != "app_runtime"
        or value["migration_username"] != "app_migrate"
    ):
        raise ValueError("DB 런타임·마이그레이션 역할 이름이 잘못되었습니다")
    return dict(value)


class Runner:
    def __init__(self, root):
        self.root = root

    def __call__(self, command, *, stdin=None, quiet=False, cwd=None, env=None):
        name = command[0]
        if name in {"aws", "terraform", "kubectl", "helm"}:
            from python.runfiles import runfiles

            location = runfiles.Create().Rlocation(f"_main/tools/bazel/cloud/{name}")
            if not location:
                raise RuntimeError(f"Bazel 실행 도구를 찾을 수 없습니다: {name}")
            command = [location, *command[1:]]
        from python.runfiles import runfiles

        cloud_aws = runfiles.Create().Rlocation("_main/tools/bazel/cloud/aws")
        process_env = {**os.environ, **(env or {})}
        if cloud_aws:
            process_env = {
                **process_env,
                "PATH": str(Path(cloud_aws).parent)
                + os.pathsep
                + process_env.get("PATH", ""),
            }
        process = subprocess.run(
            command,
            input=stdin,
            text=True,
            capture_output=True,
            cwd=cwd or self.root,
            env=process_env,
            check=False,
        )
        if process.returncode:
            # AWS 응답·Terraform plan·Job 로그에는 민감한 정보가 포함될 수 있다.
            raise RuntimeError(
                f"{name} {command[1] if len(command) > 1 else ''} 실패 (exit {process.returncode}); AWS CloudTrail·서비스 이벤트를 확인하세요. 민감정보 보호를 위해 명령 stderr는 저장하지 않습니다"
            )
        if not quiet and process.stdout:
            print(process.stdout, end="")
        return process.stdout


def output_values(raw):
    return {key: item["value"] for key, item in json.loads(raw).items()}


def workspace():
    return Path(os.environ.get("BUILD_WORKSPACE_DIRECTORY", Path.cwd())).resolve()
