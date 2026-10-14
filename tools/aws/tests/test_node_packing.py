"""PRD 가 노드 한 대에 몰리던 문제(2026-10-14)의 명세 검사.

- scenetrip 차트: 두 벌(replicas 2)인 scene-api·gateway 는 가용 영역을 반드시 나누고(DoNotSchedule), 같은 영역 안에서는
  노드를 되도록 나눈다(ScheduleAnyway). 한 벌이면 분산 조건이 없다.
- scene-api 의 최소 필요량(requests)은 상한과 같은 1Gi 다. gateway·trip-guide 는 128Mi/512Mi 그대로다.
- SigNoz 공식 차트(@signoz_chart)에 values.yaml 을 얹어 그린 결과에 부품별 requests·limits 가 실린다.

구현이 아니라 명세를 기준으로 쓴다. 차트는 Bazel 이 받은 helm 으로 실제로 그린다.
"""

import json
import os
import re
import subprocess
import unittest
from pathlib import Path

from python.runfiles import runfiles

from tools.aws.tests.test_public_ingress import chart_values


def workspace():
    return Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]


def helm():
    return str(workspace() / "tools/bazel/cloud/helm")


def documents(rendered):
    return [doc for doc in re.split(r"(?m)^---\s*$", rendered) if doc.strip()]


def named(docs, kind, name):
    found = [
        doc
        for doc in docs
        if re.search(rf"(?m)^kind: {re.escape(kind)}\s*$", doc)
        and re.search(rf"(?m)^  name: \"?{re.escape(name)}\"?\s*$", doc)
    ]
    if len(found) != 1:
        raise AssertionError(f"{kind}/{name} 가 {len(found)} 개 그려졌습니다")
    return found[0]


def indent(line):
    return len(line) - len(line.lstrip(" "))


def block(lines, start):
    """start 줄(「키:」) 아래로 들여쓰기가 더 깊은 줄들(주석 제외). 빈 줄이나 같은 깊이에서 끝난다."""
    base = indent(lines[start])
    body = []
    for line in lines[start + 1 :]:
        if line.strip().startswith("#"):
            continue
        if not line.strip() or indent(line) <= base:
            break
        body.append(line)
    return body


def scalar(value):
    return value.strip().strip("'\"")


def container_resources(doc, container):
    """문서 안 컨테이너 container 의 resources 를 {"requests": {...}, "limits": {...}} 로."""
    lines = doc.splitlines()
    starts = [
        i
        for i, line in enumerate(lines)
        if re.fullmatch(rf"\s*- name: \"?{re.escape(container)}\"?\s*", line)
    ]
    if not starts:
        raise AssertionError(f"컨테이너 {container} 가 없습니다")
    for start in starts:
        item = indent(lines[start])
        for i in range(start + 1, len(lines)):
            line = lines[i]
            if line.strip() and indent(line) <= item:
                break
            if line.strip() == "resources:" and indent(line) == item + 2:
                parsed = {}
                section = None
                for entry in block(lines, i):
                    key, _, value = entry.strip().partition(":")
                    if not value.strip():
                        section = parsed.setdefault(key, {})
                    elif section is not None:
                        section[key] = scalar(value)
                return parsed
    raise AssertionError(f"컨테이너 {container} 에 resources 가 없습니다")


def spread_constraints(doc):
    """Deployment 의 topologySpreadConstraints 를 항목별 dict 목록으로. 없으면 []."""
    lines = doc.splitlines()
    starts = [
        i
        for i, line in enumerate(lines)
        if line.strip() == "topologySpreadConstraints:"
    ]
    if not starts:
        return []
    if len(starts) != 1:
        raise AssertionError("topologySpreadConstraints 가 두 번 이상 나옵니다")
    items = []
    for line in block(lines, starts[0]):
        text = line.strip()
        if text.startswith("- "):
            items.append({})
            text = text[2:]
        key, _, value = text.partition(":")
        if value.strip():
            items[-1][key.strip()] = scalar(value)
    return items


def expected_spread(app):
    return [
        {
            "maxSkew": "1",
            "topologyKey": "topology.kubernetes.io/zone",
            "whenUnsatisfiable": "DoNotSchedule",
            "app": app,
        },
        {
            "maxSkew": "1",
            "topologyKey": "kubernetes.io/hostname",
            "whenUnsatisfiable": "ScheduleAnyway",
            "app": app,
        },
    ]


class SceneTripSpreadChartTest(unittest.TestCase):
    """scenetrip 차트의 분산 조건과 컨테이너 자원."""

    def render(self, environment=None):
        values_files = [str(workspace() / "platform/helm/scenetrip/values.yaml")]
        if environment:
            values_files.append(
                str(workspace() / f"platform/helm/scenetrip/values-{environment}.yaml")
            )
        command = [
            helm(),
            "template",
            "scenetrip",
            str(workspace() / "platform/helm/scenetrip"),
            "--namespace",
            "scenetrip",
        ]
        for path in values_files:
            command += ["--values", path]
        command += ["--values", "-"]
        process = subprocess.run(
            command,
            input=json.dumps(chart_values()),
            text=True,
            capture_output=True,
            check=False,
            timeout=60,
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        return documents(process.stdout)

    def deployment(self, docs, name):
        return named(docs, "Deployment", name)

    def test_prd_two_replicas_spread_across_zones_then_hosts(self):
        docs = self.render("prd")
        for name in ("scene-api", "gateway"):
            with self.subTest(name=name):
                deployment = self.deployment(docs, name)
                self.assertRegex(deployment, r"(?m)^  replicas: 2\s*$")
                self.assertEqual(spread_constraints(deployment), expected_spread(name))

    def test_prd_trip_guide_single_replica_has_no_spread(self):
        deployment = self.deployment(self.render("prd"), "trip-guide")
        self.assertRegex(deployment, r"(?m)^  replicas: 1\s*$")
        self.assertEqual(spread_constraints(deployment), [])

    def test_single_replica_has_no_spread(self):
        for environment in ("dev", None):
            docs = self.render(environment)
            for name in ("scene-api", "gateway", "trip-guide"):
                with self.subTest(environment=environment or "default", name=name):
                    deployment = self.deployment(docs, name)
                    self.assertRegex(deployment, r"(?m)^  replicas: 1\s*$")
                    self.assertEqual(spread_constraints(deployment), [])

    def test_scene_api_requests_match_memory_limit(self):
        for environment in ("dev", "prd", None):
            with self.subTest(environment=environment or "default"):
                deployment = self.deployment(self.render(environment), "scene-api")
                self.assertEqual(
                    container_resources(deployment, "scene-api"),
                    {
                        "requests": {"cpu": "250m", "memory": "1Gi"},
                        "limits": {"memory": "1Gi"},
                    },
                )

    def test_gateway_and_trip_guide_keep_small_requests(self):
        for environment in ("dev", "prd", None):
            docs = self.render(environment)
            for name in ("gateway", "trip-guide"):
                with self.subTest(environment=environment or "default", name=name):
                    resources = container_resources(self.deployment(docs, name), name)
                    self.assertEqual(resources["requests"]["memory"], "128Mi")
                    self.assertEqual(resources["limits"], {"memory": "512Mi"})


class SignozResourcesChartTest(unittest.TestCase):
    """SigNoz 공식 차트에 platform/helm/signoz/values.yaml 을 얹어 그린 결과."""

    def render(self, environment):
        chart = runfiles.Create().Rlocation(os.environ["SIGNOZ_CHART_RUNFILE"])
        self.assertTrue(chart and Path(chart).is_file(), chart)
        root = workspace() / "platform/helm/signoz"
        process = subprocess.run(
            [
                helm(),
                "template",
                "signoz",
                chart,
                "--namespace",
                "signoz",
                "--values",
                str(root / "values.yaml"),
                "--values",
                str(root / f"values-{environment}.yaml"),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=120,
        )
        self.assertEqual(process.returncode, 0, process.stderr)
        return process.stdout

    def test_component_resources(self):
        cases = [
            (
                "ClickHouseInstallation",
                "signoz-clickhouse",
                "clickhouse",
                {"cpu": "250m", "memory": "1536Mi"},
                {"memory": "3Gi"},
            ),
            (
                "StatefulSet",
                "signoz-zookeeper",
                "zookeeper",
                {"cpu": "100m", "memory": "512Mi"},
                {"memory": "1Gi"},
            ),
            (
                "StatefulSet",
                "signoz",
                "signoz",
                {"cpu": "100m", "memory": "512Mi"},
                {"memory": "1Gi"},
            ),
            (
                "Deployment",
                "signoz-otel-collector",
                "collector",
                {"cpu": "100m", "memory": "512Mi"},
                {"memory": "1Gi"},
            ),
        ]
        for environment in ("dev", "prd"):
            docs = documents(self.render(environment))
            for kind, name, container, requests, limits in cases:
                with self.subTest(environment=environment, workload=name):
                    self.assertEqual(
                        container_resources(named(docs, kind, name), container),
                        {"requests": requests, "limits": limits},
                    )

    def test_storage_class_stays_signoz_gp3_without_chart_storage_class(self):
        for environment in ("dev", "prd"):
            with self.subTest(environment=environment):
                rendered = self.render(environment)
                classes = set(
                    re.findall(r"(?m)^\s*storageClassName:\s*\"?([^\s\"]+)", rendered)
                )
                self.assertEqual(classes, {"signoz-gp3"})
                # installCustomStorageClass: false — 차트가 StorageClass 를 만들지 않는다.
                self.assertNotRegex(rendered, r"(?m)^kind: StorageClass\s*$")
