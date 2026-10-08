"""실제 nginx proxy 체인에서 ALB append·신뢰 hop·client IP 경계를 검증한다."""

import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

from tools.aws.integration.support import DockerTest

IMAGE_TAR = sys.argv.pop(1)
IMAGE = "scenetrip-integration/nginx:1.28.0-alpine"
NGINX_PREFIX = """pid /tmp/nginx.pid;
events { worker_connections 128; }
http {
  access_log off;
  client_body_temp_path /tmp/client;
  proxy_temp_path /tmp/proxy;
  fastcgi_temp_path /tmp/fastcgi;
  uwsgi_temp_path /tmp/uwsgi;
  scgi_temp_path /tmp/scgi;
"""

# IP 상한(30r/s, burst 60)을 기계 속도와 무관하게 넘기는 순차 요청 수.
BURST_REQUESTS = 300


class GatewayTests(DockerTest):
    # gateway.public 값. 기존(비공개) 동작은 명시적으로 false 로 렌더한다.
    PUBLIC = False

    def setUp(self):
        super().setUp()
        self.load_image(IMAGE_TAR)
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.work = Path(self.temporary.name)
        self.work.chmod(0o755)
        self.net = self.network()
        self.client = self.start_client("approved")
        self.other_client = self.start_client("also-approved")
        self.unapproved = self.start_client("unapproved")
        self.client_ip = self.container_ip(self.client)
        self.start_backend()
        self.relay = self.start_relay()
        self.gateway = self.start_nginx("gateway", self.render_config())
        self.wait_until(self.gateway_ready, "ALB proxy 체인")

    def start_client(self, name):
        return self.container(
            name,
            IMAGE,
            "--network",
            self.net,
            "--read-only",
            "--user",
            "10001:10001",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--entrypoint",
            "/bin/sleep",
            command=("3600",),
        )

    def container_ip(self, name):
        details = json.loads(self.command("inspect", name).stdout)[0]
        return details["NetworkSettings"]["Networks"][self.net]["IPAddress"]

    def start_nginx(self, alias, source):
        config = self.work / (alias + ".conf")
        config.write_text(source)
        name = self.container(
            alias,
            IMAGE,
            "--network",
            self.net,
            "--network-alias",
            alias,
            "--read-only",
            "--user",
            "10001:10001",
            "--cap-drop",
            "ALL",
            "--security-opt",
            "no-new-privileges",
            "--tmpfs",
            "/tmp:rw,nosuid,noexec,mode=1777",
            "--mount",
            f"type=bind,src={config},dst=/etc/nginx/nginx.conf,readonly",
            "--entrypoint",
            "nginx",
            command=("-g", "daemon off;"),
        )
        self.command("exec", name, "nginx", "-t")
        return name

    def gateway_ready(self):
        for name in (self.gateway, self.relay):
            state = json.loads(self.command("inspect", name).stdout)[0]["State"]
            if not state["Running"]:
                logs = self.command("logs", name)
                self.fail(
                    "nginx가 시작 직후 종료됐습니다: " + logs.stdout + logs.stderr
                )
        return self.request("/healthz")[0] == 200

    def render_config(self):
        helm = self.root / "tools/bazel/cloud/helm"
        values = {
            "database.host": "database.example.internal",
            "gateway.host": "api.example.test",
            "gateway.certificateArn": "arn:aws:acm:ap-northeast-2:111122223333:certificate/00000000-0000-0000-0000-000000000000",
            "gateway.allowedCidrs[0]": self.client_ip + "/32",
            "gateway.allowedCidrs[1]": self.container_ip(self.other_client) + "/32",
            "gateway.trustedProxyCidrs[0]": self.container_ip(self.relay) + "/32",
            "gateway.albSubnetIds[0]": "subnet-00000000000000001",
            "gateway.albSubnetIds[1]": "subnet-00000000000000002",
            "gateway.securityGroupId": "sg-00000000000000001",
            "sceneApi.image": "example.invalid/scene-api:test",
            "tripGuide.image": "example.invalid/trip-guide:test",
            "network.dnsCidr": "172.20.0.10/32",
        }
        arguments = [
            str(helm),
            "template",
            "scenetrip",
            str(self.root / "platform/helm/scenetrip"),
        ]
        for name, value in values.items():
            arguments.extend(("--set-string", name + "=" + value))
        arguments.extend(
            ("--set", "gateway.public=" + ("true" if self.PUBLIC else "false"))
        )
        rendered = subprocess.run(
            arguments,
            env={**os.environ, "RUNFILES_DIR": os.environ["TEST_SRCDIR"]},
            text=True,
            capture_output=True,
            check=True,
            timeout=30,
        ).stdout
        self.assertEqual(rendered.count("  nginx.conf: |\n"), 1)
        block = rendered.split("  nginx.conf: |\n", 1)[1]
        lines = []
        for line in block.splitlines():
            if line and not line.startswith("    "):
                break
            lines.append(line[4:])
        return "\n".join(lines) + "\n"

    def start_backend(self):
        self.start_nginx(
            "scene-api",
            NGINX_PREFIX
            + """
  server {
    listen 8080;
    location / {
      default_type application/json;
      return 200 '{"proto":"$http_x_forwarded_proto","forwarded":"$http_forwarded","service":"$http_x_service_token","host":"$host","xff":"$http_x_forwarded_for","real":"$http_x_real_ip"}';
    }
  }
}
""",
        )

    def start_relay(self):
        # ALB처럼 수신한 XFF의 마지막에 실제 TCP client 주소를 추가한다.
        # Docker DNS를 요청 시 해석하므로 gateway보다 먼저 relay를 생성할 수 있다.
        return self.start_nginx(
            "alb-relay",
            NGINX_PREFIX
            + """
  resolver 127.0.0.11 ipv6=off valid=1s;
  server {
    listen 8080;
    location / {
      set $gateway gateway:8080;
      proxy_pass http://$gateway;
      proxy_set_header Host $http_host;
      proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
  }
}
""",
        )

    def request(
        self, path, *, host="api.example.test", headers=None, client=None, direct=False
    ):
        arguments = ["exec", client or self.client, "wget", "-S", "-O", "-", "-T", "3"]
        for key, value in {"Host": host, **(headers or {})}.items():
            arguments.extend(("--header", key + ": " + value))
        target = "gateway" if direct else "alb-relay"
        result = self.command(
            *arguments, "http://" + target + ":8080" + path, check=False
        )
        statuses = re.findall(r"^\s+HTTP/\d\.\d (\d{3})", result.stderr, re.MULTILINE)
        response_headers = dict(
            re.findall(r"^\s+([^:\s]+): ([^\r\n]+)", result.stderr, re.MULTILINE)
        )
        return int(statuses[-1]) if statuses else 0, result.stdout, response_headers

    def assert_forwarding(self, marker):
        status, body, headers = self.request(
            "/v1/places?query=" + marker,
            headers={
                "X-Forwarded-Proto": "http",
                "Forwarded": "for=untrusted",
                "X-Service-Token": marker,
                "X-Forwarded-For": "198.51.100.7, 203.0.113.8",
                "X-Real-IP": "198.51.100.9",
            },
        )
        self.assertEqual(status, 200)
        upstream = json.loads(body)
        self.assertEqual(
            upstream,
            {
                "proto": "https",
                "host": "api.example.test",
                "forwarded": "",
                "service": "",
                "xff": self.client_ip,
                "real": self.client_ip,
            },
        )
        self.assertIn("max-age=", headers["Strict-Transport-Security"])
        self.assertEqual(headers["X-Content-Type-Options"], "nosniff")

    def assert_client_and_peer_boundaries(self):
        spoofed = {"X-Forwarded-For": self.client_ip, "X-Real-IP": self.client_ip}
        self.assertEqual(
            self.request("/v1/places", client=self.unapproved, headers=spoofed)[0], 403
        )
        self.assertEqual(
            self.request("/v1/places", direct=True, headers=spoofed)[0], 403
        )
        for path in (
            "/actuator/health",
            "/internal/ping",
            "/v1/actuator/health",
            "/v1/internal/ping",
            "/v1/unknown",
            "/v1/%61ctuator/health",
            "/v1/places/../actuator/health",
            "/v1/places%2f..%2factuator/health",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.request(path)[0], 404)
        self.assertEqual(
            self.request("/v1/places", host="untrusted.example.test")[0], 404
        )

    def test_alb_append_trusted_hop_and_gateway_controls(self):
        marker = secrets.token_hex(16)
        self.assert_forwarding(marker)
        self.assert_client_and_peer_boundaries()
        self.assert_rate_limit_uses_actual_client()
        logs = self.command("logs", self.gateway)
        self.assertNotIn(marker, logs.stdout + logs.stderr)

    def assert_rate_limit_uses_actual_client(self, client=None, path="/v1/places"):
        # Docker exec 자체의 지연을 제외하고 동일 client에서 실제 HTTP burst를 보낸다.
        # 위조 XFF를 바꿔도 실제 IP bucket을 우회할 수 없어야 한다.
        # 상한이 30r/s·burst 60 이라(MZ2AZ-334) 순차 요청 N 개가 막히려면 N > 61 + 30×(걸린 초) 여야 한다.
        # 80 개는 1 초 안팎이면 통과해 버려 결과가 기계 속도에 달렸다 — 300 개면 요청당 25ms 까지 429 가 난다.
        burst = """i=1
while [ "$i" -le BURST ]; do
  wget -S -O /dev/null -T 3 --header 'Host: api.example.test' \
    --header "X-Forwarded-For: 198.51.100.$i" http://alb-relay:8080PATH 2>&1
  i=$((i + 1))
done
""".replace("PATH", path).replace("BURST", str(BURST_REQUESTS))
        result = self.command("exec", client or self.client, "sh", "-c", burst)
        statuses = re.findall(r"^\s+HTTP/\d\.\d (\d{3})", result.stdout, re.MULTILINE)
        self.assertEqual(len(statuses), BURST_REQUESTS)
        self.assertTrue(set(statuses).issubset({"200", "429"}), set(statuses))
        self.assertIn("429", statuses, "동일 실제 client의 burst가 제한되지 않았습니다")
        self.assertEqual(self.request("/v1/places", client=self.other_client)[0], 200)

    # --- ADR 0019 / MZ2AZ-333: 로그인 경로 허용 목록 -----------------------------
    LOGIN_PATHS = ("/v1/auth/refresh", "/v1/auth/google", "/v1/me")
    STILL_HIDDEN = (
        "/v1/actuator/health",
        "/v1/internal/ping",
        "/actuator/health",
        "/v1/unknown",
    )

    def assert_reaches_backend(self, path, client=None):
        status, body, _ = self.request(path, client=client)
        self.assertEqual(status, 200, path)
        upstream = json.loads(body)
        self.assertEqual(upstream["host"], "api.example.test", path)
        return upstream

    def test_login_paths_reach_backend_for_allowed_client(self):
        for path in self.LOGIN_PATHS:
            with self.subTest(path=path):
                upstream = self.assert_reaches_backend(path)
                self.assertEqual(upstream["real"], self.client_ip)
        # 비공개 모드: 허용 목록 밖 client 는 로그인 경로도 403 이다.
        for path in self.LOGIN_PATHS + ("/v1/places",):
            with self.subTest(path=path, client="unapproved"):
                self.assertEqual(self.request(path, client=self.unapproved)[0], 403)
        for path in self.STILL_HIDDEN:
            with self.subTest(path=path):
                self.assertEqual(self.request(path)[0], 404)

    def test_login_paths_are_rate_limited(self):
        self.assert_rate_limit_uses_actual_client(path="/v1/auth/refresh")


class PublicGatewayTests(GatewayTests):
    """gateway.public=true — 어떤 client IP 든 받지만 ALB(신뢰 relay) 경유만."""

    PUBLIC = True

    def test_alb_append_trusted_hop_and_gateway_controls(self):
        marker = secrets.token_hex(16)
        # 허용 client 의 전달 형태는 공개여도 같다.
        self.assert_forwarding(marker)
        unapproved_ip = self.container_ip(self.unapproved)

        # 허용 목록 밖 client 가 relay 를 거치면 200 이고 백엔드는 실제 IP 를 본다.
        status, body, _ = self.request(
            "/v1/places",
            client=self.unapproved,
            headers={"X-Forwarded-For": "198.51.100.7", "X-Real-IP": "198.51.100.9"},
        )
        self.assertEqual(status, 200)
        upstream = json.loads(body)
        self.assertEqual(upstream["xff"], unapproved_ip)
        self.assertEqual(upstream["real"], unapproved_ip)

        # 직접 연결(신뢰 relay 가 아닌 peer)은 공개여도 거부된다 — 위조 헤더로도.
        spoofed = {
            "X-Forwarded-For": self.client_ip,
            "X-Real-IP": self.client_ip,
        }
        for client in (self.client, self.unapproved):
            with self.subTest(direct=client):
                self.assertEqual(
                    self.request("/v1/places", client=client, direct=True)[0], 403
                )
                self.assertEqual(
                    self.request(
                        "/v1/places", client=client, direct=True, headers=spoofed
                    )[0],
                    403,
                )
        for path in self.LOGIN_PATHS:
            with self.subTest(path=path, direct=True):
                self.assertEqual(
                    self.request(path, client=self.unapproved, direct=True)[0], 403
                )

        for path in (
            "/actuator/health",
            "/internal/ping",
            "/v1/actuator/health",
            "/v1/internal/ping",
            "/v1/unknown",
            "/v1/%61ctuator/health",
            "/v1/places/../actuator/health",
            "/v1/places%2f..%2factuator/health",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.request(path, client=self.unapproved)[0], 404)
        self.assertEqual(
            self.request(
                "/v1/places", host="untrusted.example.test", client=self.unapproved
            )[0],
            404,
        )
        # 공개에서도 IP 당 제한은 실제 client 기준이다(위조 XFF 로 우회 불가).
        self.assert_rate_limit_uses_actual_client(client=self.unapproved)
        logs = self.command("logs", self.gateway)
        self.assertNotIn(marker, logs.stdout + logs.stderr)

    def test_login_paths_reach_backend_for_allowed_client(self):
        unapproved_ip = self.container_ip(self.unapproved)
        for client, ip in (
            (self.client, self.client_ip),
            (self.unapproved, unapproved_ip),
        ):
            for path in self.LOGIN_PATHS:
                with self.subTest(path=path, client=ip):
                    upstream = self.assert_reaches_backend(path, client=client)
                    self.assertEqual(upstream["real"], ip)
        for path in self.STILL_HIDDEN:
            with self.subTest(path=path):
                self.assertEqual(self.request(path, client=self.unapproved)[0], 404)

    def test_login_paths_are_rate_limited(self):
        self.assert_rate_limit_uses_actual_client(
            client=self.unapproved, path="/v1/auth/refresh"
        )


if __name__ == "__main__":
    unittest.main()
