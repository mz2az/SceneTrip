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


class GatewayHarness(DockerTest):
    """컨테이너(client·ALB relay·gateway·가짜 backend)와 요청 도우미만 — 시험은 하위 클래스에."""

    # gateway.public 값. 기존(비공개) 동작은 명시적으로 false 로 렌더한다.
    PUBLIC = False
    # gateway.corsOrigins 값. 비면 차트 기본값([])을 그대로 쓴다(값 파일을 넘기지 않는다).
    CORS_ORIGINS = ()

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
        if self.CORS_ORIGINS:
            # 정규식(~^https://…\.pages\.dev$)의 역슬래시를 --set 이 먹지 않게 값 파일로 넘긴다.
            cors = self.work / "cors-values.json"
            cors.write_text(
                json.dumps({"gateway": {"corsOrigins": list(self.CORS_ORIGINS)}})
            )
            cors.chmod(0o644)
            arguments.extend(("--values", str(cors)))
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
      return 200 '{"proto":"$http_x_forwarded_proto","forwarded":"$http_forwarded","service":"$http_x_service_token","subject":"$http_x_rate_subject","host":"$host","xff":"$http_x_forwarded_for","real":"$http_x_real_ip"}';
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

    def curl(self, method, path, *, headers=None, client=None, host="api.example.test"):
        """busybox wget 은 OPTIONS 를 못 보낸다 — 같은 이미지의 curl 로 보낸다.

        응답 헤더는 이름을 소문자로, 같은 이름이 여러 번 오면 모두 모은다(Vary 등).
        """
        arguments = [
            "exec",
            client or self.client,
            "curl",
            "-sS",
            "-i",
            "--max-time",
            "3",
            "-X",
            method,
        ]
        for key, value in {"Host": host, **(headers or {})}.items():
            arguments.extend(("-H", key + ": " + value))
        result = self.command(*arguments, "http://alb-relay:8080" + path, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        # subprocess text 모드가 CRLF 를 LF 로 바꾼다 — 빈 줄 하나가 헤더 끝이다.
        head, _, body = result.stdout.replace("\r\n", "\n").partition("\n\n")
        lines = head.split("\n")
        status = int(lines[0].split()[1])
        response_headers = {}
        for line in lines[1:]:
            name, _, value = line.partition(":")
            response_headers.setdefault(name.strip().lower(), []).append(value.strip())
        return status, body, response_headers

    def burst_with_origin(self, origin, path="/v1/places"):
        """한 client 에서 keep-alive 로 BURST_REQUESTS 번 보내고 (상태, ACAO) 줄을 돌려준다."""
        result = self.command(
            "exec",
            self.client,
            "curl",
            "-sS",
            "--max-time",
            "30",
            "-H",
            "Host: api.example.test",
            "-H",
            "Origin: " + origin,
            "-w",
            "\nRESULT %{http_code} [%header{access-control-allow-origin}]\n",
            "http://alb-relay:8080" + path + "?n=[1-" + str(BURST_REQUESTS) + "]",
            check=False,
        )
        return re.findall(
            r"^RESULT (\d{3}) \[([^\]\n]*)\]$", result.stdout, re.MULTILINE
        )


class GatewayTests(GatewayHarness):
    def assert_forwarding(self, marker):
        status, body, headers = self.request(
            "/v1/places?query=" + marker,
            headers={
                "X-Forwarded-Proto": "http",
                "Forwarded": "for=untrusted",
                "X-Service-Token": marker,
                "X-Rate-Subject": "user:00000000-0000-0000-0000-000000000001",
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
                "subject": "",
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


# --- 웹 CORS(docs/project/plans/web-release.md §5.3–5.4) -------------------------
DEV_PAGES_PATTERN = r"~^https://[a-z0-9-]+\.scenetrip-app\.pages\.dev$"
EXPOSED = {"retry-after", "ratelimit-limit", "ratelimit-remaining", "ratelimit-reset"}
METHODS = {"GET", "POST", "PUT", "DELETE", "OPTIONS"}
REQUEST_HEADERS = {
    "authorization",
    "content-type",
    "accept-language",
    "x-install-id",
    "idempotency-key",
    "x-scenetrip-coordinate-source",
}
# 정확히 적힌 두 개 + 정규식에 맞는 Pages 미리보기 주소.
ALLOWED_ORIGINS = (
    "https://dev.scenetrip.io",
    "http://localhost:5173",
    "https://pr-12.scenetrip-app.pages.dev",
)
# 허용 목록과 비슷하게 생긴 남의 주소 — 앞부분 일치·뒤에 붙이기·다른 Pages 프로젝트.
LOOKALIKE_ORIGINS = (
    "https://dev.scenetrip.io.evil.test",
    "http://dev.scenetrip.io",
    "https://evil.pages.dev",
    "https://x.scenetrip-app.pages.dev.evil.test",
    "https://a.b.scenetrip-app.pages.dev",
    "https://app.scenetrip.io",
    "null",
)


def header_tokens(headers, name):
    """쉼표로 나열된 헤더 값을 소문자 토큰 집합으로(같은 이름이 여러 줄이어도 합친다)."""
    tokens = set()
    for value in headers.get(name, []):
        tokens.update(part.strip().lower() for part in value.split(",") if part.strip())
    return tokens


class CorsGatewayTests(GatewayHarness):
    """gateway.corsOrigins 에 정확한 Origin 과 ~정규식을 넣었을 때(DEV 값과 같은 모양)."""

    CORS_ORIGINS = (
        "https://dev.scenetrip.io",
        "http://localhost:5173",
        DEV_PAGES_PATTERN,
    )

    def assert_allow_origin(self, headers, origin):
        # 받은 Origin 을 그대로 하나만 되돌린다 — 「*」 는 안 된다.
        self.assertEqual(headers.get("access-control-allow-origin"), [origin])
        self.assertIn("origin", header_tokens(headers, "vary"))
        self.assertNotIn("access-control-allow-credentials", headers)

    def assert_no_cors(self, headers):
        self.assertNotIn("access-control-allow-origin", headers)

    def test_allowed_origin_gets_exact_allow_origin(self):
        for origin in ALLOWED_ORIGINS:
            with self.subTest(origin=origin):
                status, body, headers = self.curl(
                    "GET", "/v1/places", headers={"Origin": origin}
                )
                self.assertEqual(status, 200)
                # 요청 자체는 그대로 backend 까지 간다.
                self.assertEqual(json.loads(body)["host"], "api.example.test")
                self.assert_allow_origin(headers, origin)
                self.assertTrue(
                    EXPOSED.issubset(
                        header_tokens(headers, "access-control-expose-headers")
                    ),
                    headers.get("access-control-expose-headers"),
                )
                # add_header 를 location 에 새로 달면 server 단의 것이 사라진다 — 남아 있어야 한다.
                self.assertIn("max-age=", headers["strict-transport-security"][0])
                self.assertEqual(headers["x-content-type-options"], ["nosniff"])

    def test_disallowed_or_missing_origin_gets_no_allow_origin(self):
        for origin in LOOKALIKE_ORIGINS + (None,):
            with self.subTest(origin=origin):
                status, _, headers = self.curl(
                    "GET",
                    "/v1/places",
                    headers={"Origin": origin} if origin else None,
                )
                # 거절이 아니라 헤더만 안 낸다 — 응답 상태는 평소와 같다.
                self.assertEqual(status, 200)
                self.assert_no_cors(headers)
                self.assertIn("max-age=", headers["strict-transport-security"][0])
                self.assertEqual(headers["x-content-type-options"], ["nosniff"])

    def test_preflight_is_answered_by_gateway(self):
        for origin in ALLOWED_ORIGINS:
            for path in ("/v1/places", "/v1/me", "/v1/courses/1"):
                with self.subTest(origin=origin, path=path):
                    status, body, headers = self.curl(
                        "OPTIONS",
                        path,
                        headers={
                            "Origin": origin,
                            "Access-Control-Request-Method": "POST",
                            "Access-Control-Request-Headers": "authorization,content-type,idempotency-key",
                        },
                    )
                    # 가짜 backend 는 어떤 메서드든 200 + JSON 을 돌려준다 — 204·빈 본문이면 gateway 가 답한 것.
                    self.assertEqual(status, 204, body)
                    self.assertEqual(body, "")
                    self.assertEqual(
                        headers.get("access-control-allow-origin"), [origin]
                    )
                    self.assertTrue(
                        METHODS.issubset(
                            {
                                token.upper()
                                for token in header_tokens(
                                    headers, "access-control-allow-methods"
                                )
                            }
                        ),
                        headers.get("access-control-allow-methods"),
                    )
                    self.assertTrue(
                        REQUEST_HEADERS.issubset(
                            header_tokens(headers, "access-control-allow-headers")
                        ),
                        headers.get("access-control-allow-headers"),
                    )
                    self.assertEqual(headers.get("access-control-max-age"), ["86400"])
                    self.assertNotIn("access-control-allow-credentials", headers)

    def test_forbidden_client_response_carries_cors(self):
        # 비공개 모드에서 허용 CIDR 밖 client 는 403 — 웹이 상태를 읽을 수 있게 CORS 헤더가 실린다.
        status, _, headers = self.curl(
            "GET",
            "/v1/places",
            client=self.unapproved,
            headers={"Origin": "https://dev.scenetrip.io"},
        )
        self.assertEqual(status, 403)
        self.assert_allow_origin(headers, "https://dev.scenetrip.io")

    def test_rate_limited_response_carries_cors(self):
        origin = "https://pr-12.scenetrip-app.pages.dev"
        results = self.burst_with_origin(origin)
        self.assertEqual(len(results), BURST_REQUESTS)
        statuses = {status for status, _ in results}
        self.assertTrue(statuses.issubset({"200", "429"}), statuses)
        self.assertIn("429", statuses, "burst 가 IP 상한에 걸리지 않았습니다")
        # 200 이든 429 든 허용 Origin 이면 모두 그 Origin 을 되돌린다 — 어긋난 것을 상태별로 센다.
        missing = {}
        for status, allow_origin in results:
            if allow_origin != origin:
                missing[status] = missing.get(status, 0) + 1
        self.assertEqual(
            missing, {}, "Access-Control-Allow-Origin 이 빠진 응답 수(상태별)"
        )


class DefaultCorsGatewayTests(GatewayHarness):
    """차트 기본값(corsOrigins: [])이면 어떤 Origin 에도 CORS 헤더를 내지 않는다."""

    def test_no_cors_headers_without_configured_origins(self):
        for origin in ALLOWED_ORIGINS + ("https://app.scenetrip.io",):
            with self.subTest(origin=origin):
                status, _, headers = self.curl(
                    "GET", "/v1/places", headers={"Origin": origin}
                )
                self.assertEqual(status, 200)
                self.assertFalse(
                    [name for name in headers if name.startswith("access-control-")],
                    headers,
                )


if __name__ == "__main__":
    unittest.main()
