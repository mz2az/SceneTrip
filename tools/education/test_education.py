"""교육 산출물의 내용·무결성·오프라인 계약을 검증한다."""

import hashlib
import ipaddress
import json
import tempfile
import unittest
import xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path
from unittest.mock import Mock, patch

import terraform_graph
from build_course import main, render_page, validate_links, write_outputs
from course_content import CHAPTERS
from local_content import LOCAL_CHAPTERS
from source_diagrams import (
    block_body,
    diagram_outputs,
    evaluate,
    expression,
    fingerprint,
    overview_svg,
    parse_resources,
    reference_dot,
    reference_svg,
    subnet_cidrs,
    topology,
)
from terraform_graph import engine_graph, invoke, save_graph, without_backend

NETWORK = """locals {
 production = var.environment == "prd"
 az_count = length(var.availability_zones)
 nat_count = local.production ? local.az_count : 1
}
resource "aws_vpc" "this" { cidr_block = var.vpc_cidr }
resource "aws_subnet" "public" {
 vpc_id = aws_vpc.this.id
 cidr_block = cidrsubnet(var.vpc_cidr, 8, count.index)
}
resource "aws_subnet" "private" {
 cidr_block = cidrsubnet(var.vpc_cidr, 4, count.index + 1)
}
resource "aws_subnet" "data" {
 cidr_block = cidrsubnet(var.vpc_cidr, 8, count.index + 64)
}
"""
DATABASE = """resource "aws_db_instance" "postgres" {
 multi_az = local.production
 backup_retention_period = local.production ? 14 : 7
 deletion_protection = local.production
}
"""


ALB_TEMPLATE = """apiVersion: eks.amazonaws.com/v1
kind: IngressClassParams
metadata:
  name: scenetrip-dev-alb
spec:
  scheme: internet-facing
---
kind: IngressClass
spec:
  controller: eks.amazonaws.com/alb
---
kind: Ingress
metadata:
  name: gateway
  annotations:
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
    alb.ingress.kubernetes.io/healthcheck-path: /healthz
spec:
  ingressClassName: scenetrip-dev-alb
"""
GATEWAY_TEMPLATE = """kind: Service
metadata:
  name: gateway
spec:
  type: ClusterIP
  ports:
    - port: 8080
"""


def fixture(root):
    files = {
        "platform/terraform/aws/network.tf": NETWORK,
        "platform/terraform/aws/database.tf": DATABASE,
        "platform/helm/scenetrip/templates/ingress.yaml": ALB_TEMPLATE,
        "platform/helm/scenetrip/templates/workloads.yaml": GATEWAY_TEMPLATE,
    }
    for environment, zones in (("dev", ["a", "c"]), ("prd", ["a", "b", "c"])):
        files[f"platform/environments/{environment}/terraform.tfvars.json.example"] = (
            json.dumps(
                {
                    "environment": environment,
                    "availability_zones": zones,
                    "aws_region": "ap-northeast-2",
                    "vpc_cidr": "10.40.0.0/16",
                    "database_instance_class": "db.example",
                }
            )
        )
    for path in (
        "services/scene-api/src/main/resources/application.yaml",
        "agents/trip-guide/config/model.json",
        "platform/helm/scenetrip/values.yaml",
        "platform/helm/scenetrip/values-dev.yaml",
        "platform/helm/scenetrip/values-prd.yaml",
        "tools/education/source_diagrams.py",
    ):
        files[path] = "example\n"
    for name, text in files.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")


class Document(HTMLParser):
    def __init__(self, html):
        super().__init__()
        self.ids = []
        self.network_resources = []
        self.feed(html)

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if "id" in attributes:
            self.ids.append(attributes["id"])
        if tag in ("script", "img", "link"):
            source = attributes.get("src", attributes.get("href", ""))
            if source.startswith(("https://", "http://", "//")):
                self.network_resources.append(source)


class EducationTest(unittest.TestCase):
    def test_alb_material_explains_routing_and_retained_nginx_boundary(self):
        chapters = {chapter["id"]: chapter for chapter in CHAPTERS}
        for anchor in ("lb-choice", "alb-resources", "alb-nginx"):
            self.assertIn(anchor, chapters)
        for chapter in [*CHAPTERS, *LOCAL_CHAPTERS]:
            if chapter["id"] != "lb-choice":
                with self.subTest(anchor=chapter["id"]):
                    self.assertNotIn("NLB", str(chapter))
        text = str(CHAPTERS)
        for term in (
            "ClusterIP",
            "IngressClassParams",
            "X-Forwarded-For",
            "LCU",
            "nginx",
            "IP",
        ):
            self.assertIn(term, text)

    def test_alb_diagram_uses_chart_shape_and_hashes_its_templates(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            outputs = diagram_outputs(root)
            self.assertIn("HTTPS ALB", outputs["diagrams/dev-overview.svg"])
            self.assertNotIn("NLB", outputs["diagrams/dev-overview.svg"])
            self.assertIn("ClusterIP", outputs["diagrams/prd-overview.svg"])
            sources = json.loads(outputs["diagrams/provenance.json"])["sources"]
            self.assertIn("platform/helm/scenetrip/templates/ingress.yaml", sources)

    def test_diagram_rejects_changed_ingress_or_public_service(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            ingress = root / "platform/helm/scenetrip/templates/ingress.yaml"
            ingress.write_text(
                ALB_TEMPLATE.replace("eks.amazonaws.com/alb", "ingress.example/other")
            )
            with self.assertRaises(ValueError):
                diagram_outputs(root)
            ingress.write_text(ALB_TEMPLATE)
            gateway = root / "platform/helm/scenetrip/templates/workloads.yaml"
            gateway.write_text(GATEWAY_TEMPLATE.replace("ClusterIP", "LoadBalancer"))
            with self.assertRaises(ValueError):
                diagram_outputs(root)

    def test_course_contains_current_product_and_aws_definitions(self):
        text = str(CHAPTERS)
        for word in (
            "SceneTrip",
            "DeepSeek",
            "X-Device-Id",
            "C.UTF-8",
            "PostGIS",
            "OIDC",
            "STS",
            "NAT",
            "EKS Auto Mode",
            "CloudWatch",
            "SigNoz",
            "Secrets Manager",
            "KMS",
            "Route 53",
            "ACM",
            "S3",
            "ECR",
        ):
            with self.subTest(word=word):
                self.assertIn(word, text)
        self.assertGreaterEqual(len(CHAPTERS), 30)

    def test_every_chapter_has_teaching_depth_and_unique_anchor(self):
        for chapters in (CHAPTERS, LOCAL_CHAPTERS):
            self.assertEqual(
                len(chapters), len({chapter["id"] for chapter in chapters})
            )
            for chapter in chapters:
                self.assertGreater(len(chapter["body"]), 100)
                self.assertTrue(chapter["notes"])
                self.assertTrue(chapter["summary"])

    def test_offline_decks_and_accessible_controls(self):
        html = render_page("SceneTrip", CHAPTERS, presentation=True)
        document = Document(html)
        self.assertEqual(document.network_resources, [])
        self.assertEqual(len(document.ids), len(set(document.ids)))
        for element in (
            "previous-slide",
            "next-slide",
            "overview",
            "toggle-notes",
            "toggle-reading",
            "toggle-fullscreen",
            "print-slides",
            "slide-select",
        ):
            self.assertIn(element, document.ids)
        self.assertIn("@media print", html)
        self.assertIn("decodeURIComponent", html)
        self.assertIn("[contenteditable]", html)

    def test_reading_course_keeps_all_sections_without_javascript(self):
        html = render_page("SceneTrip", CHAPTERS, presentation=False)
        self.assertNotIn("initPresentation(document", html)
        self.assertIn('class="course"', html)
        self.assertEqual(html.count('<section class="slide"'), len(CHAPTERS))

    def test_local_course_has_real_health_path_and_no_removed_commands(self):
        text = str(LOCAL_CHAPTERS)
        self.assertIn("/v1/actuator/health", text)
        self.assertIn("just deploy postgres local", text)
        for removed in (
            "just smoke",
            "just verify-telemetry",
            "just test-masking",
            "/api/health",
        ):
            self.assertNotIn(removed, text)

    def test_generation_is_deterministic_and_check_detects_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outputs = {"one.html": "한글\n", "two.svg": "<svg/>\n"}
            self.assertTrue(write_outputs(root, outputs, check=False))
            self.assertTrue(write_outputs(root, outputs, check=True))
            (root / "one.html").write_text("changed", encoding="utf-8")
            self.assertFalse(write_outputs(root, outputs, check=True))
            self.assertEqual((root / "one.html").read_text(), "changed")

    def test_fingerprint_is_content_sha_and_relative_path(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "input.tf").write_bytes(b"resource {}\n")
            result = fingerprint(root, ["input.tf"])
            self.assertEqual(
                result, {"input.tf": hashlib.sha256(b"resource {}\n").hexdigest()}
            )
            with self.assertRaises(ValueError):
                fingerprint(root, ["../outside"])

    def test_hcl_reference_graph_tracks_changed_dependencies(self):
        hcl = """resource "aws_vpc" "main" { cidr_block = var.vpc_cidr }
resource "aws_subnet" "private" { vpc_id = aws_vpc.main.id
 tags = { Name = "nested braces { must not truncate }" } }
"""
        resources, edges = parse_resources(hcl)
        self.assertEqual(resources, ["aws_subnet.private", "aws_vpc.main"])
        self.assertEqual(edges, [("aws_subnet.private", "aws_vpc.main")])
        self.assertEqual(
            parse_resources(hcl.replace("aws_vpc.main.id", "var.vpc_id"))[1], []
        )

    def test_environment_topology_and_subnet_math(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            dev, prd = topology(root, "dev"), topology(root, "prd")
            self.assertEqual(
                (dev["nat_count"], dev["multi_az"], dev["backup_days"]), (1, False, 7)
            )
            self.assertEqual(
                (prd["nat_count"], prd["multi_az"], prd["backup_days"]), (3, True, 14)
            )
            self.assertEqual(
                dev["subnets"]["private"], ["10.40.16.0/20", "10.40.32.0/20"]
            )
            self.assertEqual(prd["subnets"]["data"][2], "10.40.66.0/24")
            self.assertEqual(len(prd["subnets"]["public"]), 3)

    def test_topology_changes_are_derived_not_frozen(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            path = root / "platform/terraform/aws/network.tf"
            path.write_text(
                NETWORK.replace(
                    "local.production ? local.az_count : 1", "local.az_count"
                )
            )
            self.assertEqual(topology(root, "dev")["nat_count"], 2)
            self.assertIn("NAT 2", overview_svg(topology(root, "dev")))
            path.write_text(
                NETWORK.replace('var.environment == "prd"', 'var.environment != "dev"')
            )
            with self.assertRaisesRegex(ValueError, "production"):
                topology(root, "dev")

    def test_wrong_environment_and_missing_topology_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(ValueError):
                diagram_outputs(root)
            fixture(root)
            with self.assertRaises(ValueError):
                topology(root, "prod")
            path = root / "platform/environments/dev/terraform.tfvars.json.example"
            path.write_text(path.read_text().replace('"dev"', '"prd"'))
            with self.assertRaisesRegex(ValueError, "environment"):
                topology(root, "dev")
            path.write_text(path.read_text().replace('"prd"', '"dev"'))
            (root / "platform/terraform/aws/network.tf").write_text(
                NETWORK.replace("length(var.availability_zones)", "2")
            )
            with self.assertRaisesRegex(ValueError, "AZ"):
                topology(root, "dev")

    def test_unsupported_expression_and_overlapping_subnets_fail_closed(self):
        network = ipaddress.ip_network("10.40.0.0/16")
        for source in (
            NETWORK.replace("count.index + 64", "count.index"),
            NETWORK.replace(
                "cidrsubnet(var.vpc_cidr, 8, count.index)", "var.public_cidr"
            ),
            NETWORK.replace('"aws_subnet" "data"', '"aws_subnet" "renamed"'),
        ):
            with self.subTest(source=source), self.assertRaises(ValueError):
                subnet_cidrs(network, source, 2)
        with self.assertRaises(ValueError):
            evaluate("terraform.workspace", {})
        with self.assertRaises(ValueError):
            expression("", "missing")
        with self.assertRaises(ValueError):
            block_body("unterminated", 0)
        self.assertTrue(evaluate("!false", {}))
        self.assertFalse(evaluate("true ? false : true", {}))
        self.assertEqual(block_body('a="\\"}"}', 0), 'a="\\"}"')

    def test_generated_diagrams_are_valid_and_provenance_tracks_content(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            outputs = diagram_outputs(root)
            for name, text in outputs.items():
                if name.endswith(".svg"):
                    ET.fromstring(text)
            provenance = json.loads(outputs["diagrams/provenance.json"])
            for name, digest in provenance["outputs"].items():
                self.assertEqual(
                    digest, hashlib.sha256(outputs[name].encode()).hexdigest()
                )
            path = root / "platform/terraform/aws/network.tf"
            path.write_text(path.read_text() + "\n# input changed\n")
            self.assertNotEqual(
                outputs["diagrams/provenance.json"],
                diagram_outputs(root)["diagrams/provenance.json"],
            )
            self.assertIn("not terraform graph", reference_dot(["aws_vpc.main"], []))
            self.assertIn("&lt;", reference_svg(["<unsafe>"], [("<unsafe>", "target")]))

    def test_links_include_generated_and_percent_encoded_documents(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "docs/education").mkdir(parents=True)
            (root / "docs/education/space name.md").write_text("ok")
            outputs = {
                "a.html": '<a href="b.html">b</a><a href="space%20name.md">space</a><a href="https://example.com">external</a><a href="#x">hash</a>',
                "b.html": "ok",
            }
            validate_links(root, outputs)
            with self.assertRaisesRegex(ValueError, "missing.md"):
                validate_links(root, {"a.html": '<a href="missing.md">missing</a>'})

    def test_main_generates_checks_and_reports_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            with (
                patch("build_course.validate_links"),
                patch("sys.argv", ["education", "--root", directory]),
            ):
                self.assertEqual(main(), 0)
            with (
                patch("build_course.validate_links"),
                patch("sys.argv", ["education", "--root", directory, "--check"]),
            ):
                self.assertEqual(main(), 0)
                (root / "docs/education/aws-eks-course.html").write_text("drift")
                self.assertEqual(main(), 1)


class EngineGraphTest(unittest.TestCase):
    def test_backend_removed_only_in_isolated_copy(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            source = root / "platform/terraform/aws/versions.tf"
            original = 'terraform {\n backend "s3" { key = "env/state" }\n required_version = ">= 1.13"\n}'
            source.write_text(original)
            provider = root / "mirror/registry/hashicorp/aws/provider.zip"
            provider.parent.mkdir(parents=True)
            provider.write_bytes(b"pinned provider")
            seen = []

            def fake_invoke(binary, arguments, work, environment):
                seen.append(arguments)
                self.assertNotIn('backend "s3"', (work / "versions.tf").read_text())
                self.assertIn(
                    "filesystem_mirror",
                    Path(environment["TF_CLI_CONFIG_FILE"]).read_text(),
                )
                self.assertNotIn(
                    "direct {", Path(environment["TF_CLI_CONFIG_FILE"]).read_text()
                )
                self.assertEqual(environment["AWS_EC2_METADATA_DISABLED"], "true")
                self.assertNotIn("TF_CLI_ARGS_graph", environment)
                self.assertNotIn("AWS_ACCESS_KEY_ID", environment)
                self.assertTrue(
                    Path(environment["TF_DATA_DIR"]).is_relative_to(work.parent)
                )
                if arguments[0] == "version":
                    return '{"terraform_version": "1.13.5"}'
                return (
                    "digraph { a -> b; }\n"
                    if arguments[0] == "graph"
                    else "initialized"
                )

            with (
                patch.dict(
                    "os.environ",
                    {
                        "TF_DATA_DIR": "/outside",
                        "TF_CLI_ARGS_graph": "-draw-cycles",
                        "AWS_ACCESS_KEY_ID": "test-ignored",
                    },
                ),
                patch("terraform_graph.invoke", side_effect=fake_invoke),
            ):
                graph, provenance = engine_graph(root, Path("terraform"), provider)
            self.assertEqual(source.read_text(), original)
            self.assertFalse((source.parent / ".terraform").exists())
            self.assertEqual(provenance["terraform_version"], "1.13.5")
            self.assertEqual(
                provenance["dot_sha256"], hashlib.sha256(graph.encode()).hexdigest()
            )
            self.assertEqual(seen[1], ["graph"])
            self.assertIn("-lockfile=readonly", seen[0])
            self.assertTrue(save_graph(root, graph, provenance, False))
            self.assertTrue(save_graph(root, graph, provenance, True))
            self.assertFalse(save_graph(root, "changed", provenance, True))

    def test_engine_failures_and_wrong_output_are_not_success(self):
        with (
            patch(
                "terraform_graph.subprocess.run",
                return_value=Mock(returncode=1, stderr="bad schema", stdout=""),
            ),
            self.assertRaisesRegex(RuntimeError, "bad schema"),
        ):
            invoke(Path("terraform"), ["validate"], Path("."), {})
        with patch(
            "terraform_graph.subprocess.run",
            return_value=Mock(returncode=0, stdout="ok"),
        ):
            self.assertEqual(invoke(Path("terraform"), ["graph"], Path("."), {}), "ok")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture(root)
            with (
                patch(
                    "terraform_graph.invoke",
                    side_effect=["ok", "not a graph", '{"terraform_version":"1"}'],
                ),
                self.assertRaisesRegex(ValueError, "DOT"),
            ):
                engine_graph(root, Path("terraform"), root / "a/b/c/provider.zip")
            with (
                patch(
                    "terraform_graph.invoke",
                    side_effect=["ok", "digraph{}", '{"terraform_version":"1"}'],
                ),
                patch(
                    "terraform_graph.fingerprint", side_effect=[{}, {"changed": "sha"}]
                ),
                self.assertRaisesRegex(RuntimeError, "원본"),
            ):
                engine_graph(root, Path("terraform"), root / "a/b/c/provider.zip")

    def test_graph_command_wiring_and_readonly_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tools/education").mkdir(parents=True)
            (root / "tools/education/terraform_graph.py").write_text("generator")
            locator = Mock()
            locator.Rlocation.side_effect = lambda name: str(root / name)
            with (
                patch.dict("os.environ", {"BUILD_WORKSPACE_DIRECTORY": directory}),
                patch(
                    "sys.argv", ["graph", "--provider-runfile", "provider", "--check"]
                ),
                patch("terraform_graph.runfiles.Create", return_value=locator),
                patch(
                    "terraform_graph.engine_graph",
                    return_value=("digraph{}", {"sources": {}}),
                ),
            ):
                self.assertEqual(terraform_graph.main(), 1)
                self.assertFalse((root / "docs/education/diagrams").exists())
            self.assertEqual(
                without_backend('terraform { required_version = "1" }'),
                'terraform { required_version = "1" }',
            )


if __name__ == "__main__":
    unittest.main()
