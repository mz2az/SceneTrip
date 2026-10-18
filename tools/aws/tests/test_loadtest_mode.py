"""DEV 부하 시험 모드 명세 검사 — MZ2AZ-502, docs/project/plans/load-test.md §3, platform/helm/scenetrip/README.md.

시험 모드(`loadtest.enabled`)는 DEV 에서만 켠다. 켜면 차트가

- 가짜 바깥 서버(`fake-external`: ConfigMap 에 `files/fake_external.py`, Deployment, Service 8080)를 올리고
- scene-api·trip-guide → fake-external 8080 통로를 NetworkPolicy 로 열고(기본 차단 네임스페이스)
- scene-api 의 카카오·구글 공개키 주소와 trip-guide 의 모델 주소를 가짜로 덮고
- gateway 의 IP 당 상한을 `loadtest.gatewayRate`·`gatewayBurst`(·`gatewayConnections`)로 올린다.

PRD 에서 켜면 렌더가 실패한다. 꺼져 있으면 지금과 같다 — 가짜도, 덮는 환경변수도, 바뀐 상한도 없다.

구현이 아니라 명세를 기준으로 쓴다.
"""

import json
import os
import re
import unittest
from pathlib import Path

from tools.aws.tests.test_gateway_limits import api_location, nginx_conf
from tools.aws.tests.test_public_ingress import PublicChartTest, chart_values

FAKE = "fake-external"
JWK = json.dumps(
    {
        "kty": "RSA",
        "alg": "RS256",
        "use": "sig",
        "kid": "load-test",
        "n": "AQAB",
        "e": "AQAB",
    }
)
CLIENT_ID = "load-test-client.apps.googleusercontent.com"

SCENE_API_OVERRIDES = {
    "SCENETRIP_NAVIGATION_KAKAO_BASEURL": "http://fake-external:8080/kakao",
    "SCENETRIP_AUTH_GOOGLE_JWKSURI": "http://fake-external:8080/google/jwks",
    "SCENETRIP_AUTH_GOOGLE_CLIENTIDS": CLIENT_ID,
}
TRIP_GUIDE_OVERRIDES = {
    "TRIP_GUIDE_MODEL_BASE_URL": "http://fake-external:8080/bedrock",
    "TRIP_GUIDE_MODEL_AUTH": "none",
}


def loadtest_values(enabled=True, **changes):
    values = chart_values()
    values["loadtest"] = {
        "enabled": enabled,
        "googleClientId": CLIENT_ID,
        "googleJwkJson": JWK,
        **changes,
    }
    return values


# 저장소에 YAML 파서가 없다(test_media.py 와 같은 방식) — 렌더 결과를 문서·들여쓰기 단위의 글로 읽는다.


def documents(rendered):
    """렌더 결과를 (kind, metadata.name, 본문) 목록으로."""
    out = []
    for doc in rendered.split("\n---"):
        kind = name = None
        for line in doc.splitlines():
            if line.startswith("kind: "):
                kind = line.split(":", 1)[1].strip()
            if line.startswith("  name: ") and name is None:
                name = line.split(":", 1)[1].strip().strip("'\"")
        if kind:
            out.append((kind, name, doc))
    return out


def find(docs, kind, name):
    found = [doc for k, n, doc in docs if k == kind and n == name]
    return found[0] if len(found) == 1 else None


def section(doc, key, indent):
    """`key:` 줄(정확히 indent 칸) 아래, 같거나 얕은 들여쓰기가 나오기 전까지."""
    lines = doc.splitlines()
    for i, line in enumerate(lines):
        if line == " " * indent + key + ":" or line.startswith(
            " " * indent + key + ": "
        ):
            body = [line[len(" " * indent + key + ":") :]]
            for rest in lines[i + 1 :]:
                stripped = rest.lstrip(" ")
                if stripped and len(rest) - len(stripped) <= indent:
                    break
                body.append(rest)
            return "\n".join(body)
    return ""


def apps_in(text):
    apps = set(re.findall(r"\bapp:\s*([\w-]+)", text))
    for values in re.findall(r"values:\s*\[([^\]]*)\]", text):
        apps.update(v.strip() for v in values.split(",") if v.strip())
    return apps


def selects(policy, app):
    selector = section(policy, "podSelector", 2)
    return app in apps_in(selector) or selector.strip() == "{}"


def rules(text):
    """`- ` 로 시작하는 목록 항목(ingress/egress 규칙)들."""
    items, current, base = [], None, None
    for line in text.splitlines()[1:]:
        stripped = line.lstrip(" ")
        indent = len(line) - len(stripped)
        if stripped.startswith("- ") and (base is None or indent == base):
            base = indent
            if current is not None:
                items.append("\n".join(current))
            current = [line]
        elif current is not None:
            current.append(line)
    if current is not None:
        items.append("\n".join(current))
    return items


def ports_in(text):
    return {int(p) for p in re.findall(r"\bport:\s*(\d+)", text)}


def allows(docs, policy_app, direction, peer_key, peer_app, port):
    for kind, _, policy in docs:
        if kind != "NetworkPolicy" or not selects(policy, policy_app):
            continue
        for rule in rules(section(policy, direction, 2)):
            peers = rule.split("ports:", 1)[0] if peer_key in rule else ""
            if peer_app in apps_in(peers) and port in ports_in(rule):
                return True
    return False


def allows_ingress(docs, target, source, port):
    return allows(docs, target, "ingress", "from", source, port)


def allows_egress(docs, source, target, port):
    return allows(docs, source, "egress", "to", target, port)


def env_value(doc, name):
    """컨테이너 env 의 `- name: NAME` 다음 `value:` (따옴표 문자열은 풀어서)."""
    match = re.search(r"- name: " + re.escape(name) + r"\n\s+value: (.+)", doc)
    if match is None:
        return None
    raw = match.group(1).strip()
    return json.loads(raw) if raw.startswith('"') else raw


def configmap_value(doc, name):
    match = re.search(r"^  " + re.escape(name) + r": (.+)$", doc, re.MULTILINE)
    if match is None:
        return None
    raw = match.group(1).strip()
    return json.loads(raw) if raw.startswith('"') else raw


def block_scalar(doc, key, indent):
    """`key: |` 아래의 여러 줄 값을 들여쓰기를 걷어 돌려준다."""
    lines = doc.splitlines()
    head = " " * indent + key + ": |"
    for i, line in enumerate(lines):
        if line.startswith(head):
            body = []
            for rest in lines[i + 1 :]:
                stripped = rest.lstrip(" ")
                if stripped and len(rest) - len(stripped) <= indent:
                    break
                body.append(rest[indent + 2 :])
            return "\n".join(body)
    return None


class LoadtestModeChartTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        self.chart = PublicChartTest("helm_template")
        self.chart.setUp()

    def render(self, environment, values):
        process = self.chart.helm_template(environment, values)
        self.assertEqual(process.returncode, 0, process.stderr)
        return process.stdout

    def one(self, docs, kind, name):
        doc = find(docs, kind, name)
        self.assertIsNotNone(doc, f"{kind}/{name} 가 정확히 하나여야 합니다")
        return doc

    # --- 켜짐 + DEV -------------------------------------------------------------

    def test_enabled_dev_renders_fake_external_workload(self):
        docs = documents(self.render("dev", loadtest_values()))
        deployment = self.one(docs, "Deployment", FAKE)
        self.assertRegex(deployment, r"\n  replicas: 1\n")
        self.assertIn("app: fake-external", section(deployment, "template", 2))
        # trip-guide 와 같은 이미지(Python 3.13)에 파일 하나만 얹어 돈다.
        self.assertIn('image: "fixture:guide"', deployment)
        command = section(deployment, "command", 10)
        self.assertTrue(command, "command 가 없으면 trip-guide 가 뜹니다")
        self.assertIn("python3", command)
        self.assertIn("/fake_external.py", command)
        self.assertEqual(env_value(deployment, "FAKE_PORT"), "8080")
        self.assertEqual(
            json.loads(env_value(deployment, "FAKE_GOOGLE_JWK_JSON")), json.loads(JWK)
        )
        for probe in ("readinessProbe", "livenessProbe"):
            with self.subTest(probe=probe):
                self.assertIn("path: /healthz", section(deployment, probe, 10))
        # 다른 워크로드와 같은 보안 수준
        self.assertIn("runAsNonRoot: true", deployment)
        self.assertIn("automountServiceAccountToken: false", deployment)
        self.assertIn("allowPrivilegeEscalation: false", deployment)
        self.assertIn("readOnlyRootFilesystem: true", deployment)
        self.assertRegex(deployment, r"drop: \[ALL\]")
        resources = section(deployment, "resources", 10)
        self.assertRegex(resources, r"requests:[\s\S]*cpu:")
        self.assertRegex(resources, r"limits:[\s\S]*memory:")

    def test_enabled_dev_renders_service_on_8080(self):
        docs = documents(self.render("dev", loadtest_values()))
        service = self.one(docs, "Service", FAKE)
        self.assertIn("app: fake-external", section(service, "selector", 2))
        self.assertEqual(ports_in(section(service, "ports", 2)), {8080})

    def test_enabled_dev_configmap_carries_the_script(self):
        docs = documents(self.render("dev", loadtest_values()))
        carriers = [
            (name, doc)
            for kind, name, doc in docs
            if kind == "ConfigMap"
            and block_scalar(doc, "fake_external.py", 2) is not None
        ]
        self.assertEqual(
            len(carriers), 1, "스크립트를 담은 ConfigMap 이 하나여야 합니다"
        )
        name, doc = carriers[0]
        script = (
            self.root / "platform/helm/scenetrip/files/fake_external.py"
        ).read_text(encoding="utf-8")
        rendered = block_scalar(doc, "fake_external.py", 2)
        self.assertEqual(
            [line.rstrip() for line in rendered.rstrip("\n").splitlines()],
            [line.rstrip() for line in script.rstrip("\n").splitlines()],
        )
        # Deployment 가 그 ConfigMap 을 마운트한다
        volumes = section(self.one(docs, "Deployment", FAKE), "volumes", 6)
        self.assertRegex(volumes, r"configMap:\s*\n\s*name: " + re.escape(name))

    def test_enabled_dev_network_policies_open_the_three_flows(self):
        docs = documents(self.render("dev", loadtest_values()))
        for source in ("scene-api", "trip-guide"):
            with self.subTest(source=source):
                self.assertTrue(
                    allows_ingress(docs, FAKE, source, 8080),
                    f"fake-external 이 {source} 를 받지 않습니다",
                )
                self.assertTrue(
                    allows_egress(docs, source, FAKE, 8080),
                    f"{source} 가 fake-external 로 나가지 못합니다",
                )
        # 가짜는 그 밖에서 받지 않는다(gateway 가 직접 부르지 못한다)
        self.assertFalse(allows_ingress(docs, FAKE, "gateway", 8080))
        # 기본 차단은 그대로
        self.assertIsNotNone(find(docs, "NetworkPolicy", "default-deny"))

    def test_enabled_dev_overrides_external_addresses(self):
        docs = documents(self.render("dev", loadtest_values()))
        config = self.one(docs, "ConfigMap", "scene-api")
        for name, value in SCENE_API_OVERRIDES.items():
            with self.subTest(name=name):
                self.assertEqual(configmap_value(config, name), value)
        guide = self.one(docs, "Deployment", "trip-guide")
        for name, value in TRIP_GUIDE_OVERRIDES.items():
            with self.subTest(name=name):
                self.assertEqual(env_value(guide, name), value)

    def test_enabled_dev_raises_gateway_ip_limits(self):
        conf = nginx_conf(
            self.render(
                "dev",
                loadtest_values(
                    gatewayRate="2000r/s", gatewayBurst=4000, gatewayConnections=4000
                ),
            )
        )
        self.assertEqual(
            re.findall(r"limit_req_zone [^;]+;", conf),
            ["limit_req_zone $binary_remote_addr zone=api:10m rate=2000r/s;"],
        )
        block = api_location(conf)
        self.assertIn("limit_req zone=api burst=4000 nodelay;", block)
        self.assertIn("limit_conn connections 4000;", block)
        self.assertNotIn("rate=30r/s", conf)
        self.assertNotIn("burst=60 ", conf)

    def test_enabled_defaults_raise_the_limit(self):
        # 값을 따로 주지 않아도 시험 모드면 30r/s·burst 60·동시 60 이 아니다.
        values = chart_values()
        values["loadtest"] = {
            "enabled": True,
            "googleClientId": CLIENT_ID,
            "googleJwkJson": JWK,
        }
        conf = nginx_conf(self.render("dev", values))
        self.assertNotIn("rate=30r/s", conf)
        self.assertNotIn("burst=60 ", api_location(conf))
        self.assertNotIn("limit_conn connections 60;", api_location(conf))

    def test_bad_gateway_rate_fails_to_render(self):
        for rate in ("lots", "2000", "2000r/h; evil"):
            with self.subTest(rate=rate):
                process = self.chart.helm_template(
                    "dev", loadtest_values(gatewayRate=rate)
                )
                self.assertNotEqual(process.returncode, 0, process.stdout)

    def test_mode_flip_restarts_scene_api_trip_guide_and_gateway(self):
        # 파드가 새 환경변수·nginx.conf 로 다시 떠야 한다 — 파드 템플릿이 달라져야 쿠버네티스가 새로 띄운다.
        def templates(values):
            docs = documents(self.render("dev", values))
            return {
                name: section(self.one(docs, "Deployment", name), "template", 2)
                for name in ("scene-api", "trip-guide", "gateway")
            }

        on = templates(loadtest_values(enabled=True))
        off = templates(loadtest_values(enabled=False))
        for name in ("scene-api", "trip-guide", "gateway"):
            with self.subTest(name=name):
                self.assertNotEqual(on[name], off[name])

    def test_enabled_requires_client_id_and_jwk(self):
        for missing in ("googleClientId", "googleJwkJson"):
            with self.subTest(missing=missing):
                process = self.chart.helm_template(
                    "dev", loadtest_values(**{missing: ""})
                )
                self.assertNotEqual(process.returncode, 0, process.stdout)

    # --- 켜짐 + PRD -------------------------------------------------------------

    def test_enabled_prd_fails_to_render(self):
        process = self.chart.helm_template("prd", loadtest_values())
        self.assertNotEqual(process.returncode, 0, process.stdout)
        self.assertIn("loadtest", process.stderr)
        self.assertIn("prd", process.stderr.lower())
        self.assertNotIn(FAKE, process.stdout)

    # --- 꺼짐 -------------------------------------------------------------------

    def test_disabled_renders_no_fake_and_no_overrides(self):
        for environment in ("dev", "prd"):
            for values in (chart_values(), loadtest_values(enabled=False)):
                with self.subTest(
                    environment=environment, loadtest="loadtest" in values
                ):
                    rendered = self.render(environment, values)
                    self.assertNotIn(FAKE, rendered)
                    self.assertNotIn("fake_external", rendered)
                    for name in (*SCENE_API_OVERRIDES, *TRIP_GUIDE_OVERRIDES):
                        self.assertNotIn(name, rendered)
                    conf = nginx_conf(rendered)
                    self.assertIn(
                        "limit_req_zone $binary_remote_addr zone=api:10m rate=30r/s;",
                        conf,
                    )
                    block = api_location(conf)
                    self.assertIn("limit_req zone=api burst=60 nodelay;", block)
                    self.assertIn("limit_conn connections 60;", block)

    def test_disabled_ignores_every_other_loadtest_value(self):
        # 꺼져 있으면 나머지 시험 값이 무엇이든 렌더는 기본값과 바이트 단위로 같다 — 모든 것이 enabled 뒤에 있다.
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                baseline = self.render(environment, chart_values())
                stray = self.render(
                    environment,
                    loadtest_values(
                        enabled=False,
                        gatewayRate="9999r/s",
                        gatewayBurst=9999,
                        gatewayConnections=9999,
                    ),
                )
                self.assertEqual(baseline, stray)


if __name__ == "__main__":
    unittest.main()
