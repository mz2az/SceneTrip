"""폐기형 Docker 픽스처. 테스트가 만든 컨테이너만 이름으로 정리한다."""

import json
import os
import shutil
import subprocess
import time
import unittest
import uuid
from pathlib import Path

from python.runfiles import runfiles


class DockerTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        self.locator = runfiles.Create()
        self.docker = shutil.which("docker")
        self.assertIsNotNone(
            self.docker, "Docker CLI와 실행 중인 로컬 Docker 엔진이 필요합니다"
        )
        self.sensitive = ()
        # Docker에서도 DOCKER_CONTEXT가 DOCKER_HOST보다 우선한다.
        endpoint = (
            None if os.environ.get("DOCKER_CONTEXT") else os.environ.get("DOCKER_HOST")
        )
        if not endpoint:
            context = self.command(
                "context", "inspect", "--format", "{{json .Endpoints.docker.Host}}"
            )
            endpoint = json.loads(context.stdout)
        self.assertTrue(
            endpoint.startswith("unix://"),
            "원격 Docker 엔진에는 테스트 컨테이너를 만들지 않습니다",
        )
        self.command("info", "--format", "{{.ServerVersion}}")

    def command(self, *arguments, stdin=None, extra_env=None, check=True):
        result = subprocess.run(
            [self.docker, *arguments],
            input=stdin,
            env={**os.environ, **(extra_env or {})},
            text=True,
            capture_output=True,
            timeout=180,
            check=False,
        )
        if check and result.returncode:
            detail = result.stdout + result.stderr
            for value in self.sensitive:
                detail = detail.replace(value, "<redacted>")
            self.fail(f"Docker {arguments[0]} 실패: {detail}")
        return result

    def load_image(self, runfile):
        self.command("load", "--input", self.locator.Rlocation(runfile))

    def unique_name(self, component):
        return f"scenetrip-it-{component}-{uuid.uuid4().hex[:12]}"

    def network(self):
        name = self.unique_name("network")
        self.command("network", "create", name)
        self.addCleanup(self.command, "network", "rm", name, check=False)
        return name

    def container(self, component, image, *options, command=()):
        name = self.unique_name(component)
        self.addCleanup(self.command, "rm", "--force", "--volumes", name, check=False)
        self.command(
            "run",
            "--detach",
            "--name",
            name,
            "--platform",
            "linux/amd64",
            "--label",
            "scenetrip.test=aws-integration",
            *options,
            image,
            *command,
        )
        return name

    def wait_until(self, predicate, description):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.3)
        self.fail(f"60초 안에 준비되지 않았습니다: {description}")
