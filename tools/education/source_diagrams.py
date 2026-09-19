"""제한된 HCL 표현과 환경 예제에서 교육용 그림을 만든다. AWS API는 호출하지 않는다."""

import hashlib
import html
import ipaddress
import json
import re
from pathlib import Path

TF_ROOT = "platform/terraform/aws"
CHART_ROOT = "platform/helm/scenetrip"
RESOURCE = re.compile(r'resource\s+"([\w]+)"\s+"([\w]+)"\s*\{')


def fingerprint(root, paths):
    result = {}
    for name in sorted(set(paths)):
        path = Path(name)
        if path.is_absolute() or ".." in path.parts:
            raise ValueError(f"출처 경로는 저장소 내부 상대 경로여야 합니다: {name}")
        result[name] = hashlib.sha256((root / path).read_bytes()).hexdigest()
    return result


def block_body(text, start):
    depth, quoted, escaped = 1, False, False
    for index in range(start, len(text)):
        char = text[index]
        if escaped:
            escaped = False
        elif char == "\\" and quoted:
            escaped = True
        elif char == '"':
            quoted = not quoted
        elif not quoted and char in "{}":
            depth += 1 if char == "{" else -1
            if depth == 0:
                return text[start:index]
    raise ValueError("닫히지 않은 HCL 리소스 블록")


def parse_resources(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"(?m)^\s*(#|//).*?$", "", text)
    blocks = {
        f"{match[1]}.{match[2]}": block_body(text, match.end())
        for match in RESOURCE.finditer(text)
    }
    edges = {
        (name, reference)
        for name, body in blocks.items()
        for reference in re.findall(r"\b(aws_\w+\.\w+)\b", body)
        if reference in blocks and reference != name
    }
    return sorted(blocks), sorted(edges)


def expression(text, key):
    match = re.search(rf"(?m)^\s*{re.escape(key)}\s*=\s*([^\n]+)", text)
    if not match:
        raise ValueError(f"필수 HCL 속성이 없습니다: {key}")
    return match[1].strip()


def evaluate(value, variables):
    value = value.strip()
    if value in variables:
        return variables[value]
    if re.fullmatch(r"\d+", value):
        return int(value)
    if value in ("true", "false"):
        return value == "true"
    conditional = re.fullmatch(r"(.+?)\s*\?\s*(.+?)\s*:\s*(.+)", value)
    if conditional:
        branch = (
            conditional[2] if evaluate(conditional[1], variables) else conditional[3]
        )
        return evaluate(branch, variables)
    if value.startswith("!"):
        return not evaluate(value[1:], variables)
    raise ValueError(f"지원하지 않는 HCL 표현입니다. 그림 생성기를 갱신하세요: {value}")


def subnet_cidrs(network, source, count):
    result = {}
    for name in ("public", "private", "data"):
        match = re.search(rf'resource "aws_subnet" "{name}"\s*\{{', source)
        if not match:
            raise ValueError(f"서브넷 리소스가 없습니다: {name}")
        body = block_body(source, match.end())
        formula = expression(body, "cidr_block")
        parsed = re.fullmatch(
            r"cidrsubnet\(var.vpc_cidr,\s*(\d+),\s*count.index(?:\s*\+\s*(\d+))?\)",
            formula,
        )
        if not parsed:
            raise ValueError(f"지원하지 않는 서브넷 수식: {formula}")
        subnets = list(network.subnets(prefixlen_diff=int(parsed[1])))
        offset = int(parsed[2] or 0)
        result[name] = [str(subnets[index + offset]) for index in range(count)]
    flattened = [
        ipaddress.ip_network(cidr) for group in result.values() for cidr in group
    ]
    if any(
        a.overlaps(b)
        for index, a in enumerate(flattened)
        for b in flattened[index + 1 :]
    ):
        raise ValueError("생성된 서브넷 주소 범위가 겹칩니다")
    return result


def topology(root, environment):
    if environment not in ("dev", "prd"):
        raise ValueError("지원 환경은 dev와 prd입니다")
    path = f"platform/environments/{environment}/terraform.tfvars.json.example"
    values = json.loads((root / path).read_text())
    if values["environment"] != environment:
        raise ValueError("파일 경로와 environment가 다릅니다")
    network = (root / TF_ROOT / "network.tf").read_text()
    database = (root / TF_ROOT / "database.tf").read_text()
    if expression(network, "production") != 'var.environment == "prd"':
        raise ValueError("production 판정이 변경되었습니다. 생성기를 갱신하세요")
    if expression(network, "az_count") != "length(var.availability_zones)":
        raise ValueError("AZ 수 계산이 변경되었습니다. 생성기를 갱신하세요")
    count = len(values["availability_zones"])
    variables = {"local.production": environment == "prd", "local.az_count": count}
    return {
        **values,
        "nat_count": evaluate(expression(network, "nat_count"), variables),
        "multi_az": evaluate(expression(database, "multi_az"), variables),
        "backup_days": evaluate(
            expression(database, "backup_retention_period"), variables
        ),
        "deletion_protection": evaluate(
            expression(database, "deletion_protection"), variables
        ),
        "subnets": subnet_cidrs(
            ipaddress.ip_network(values["vpc_cidr"]), network, count
        ),
    }


def text_at(x, y, label, size=16, color="#17392d"):
    return f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}">{html.escape(str(label))}</text>'


def ingress_shape(root):
    """지원하는 Helm 선언만 확인한다. Helm 렌더·AWS 조회를 대신하지 않는다."""
    paths = sorted((root / CHART_ROOT / "templates").glob("*.yaml"))
    source = "\n".join(path.read_text() for path in paths)
    requirements = (
        r"(?m)^apiVersion: eks\.amazonaws\.com/v1$",
        r"(?m)^kind: IngressClassParams$",
        r"(?m)^kind: IngressClass$",
        r"(?m)^kind: Ingress$",
        r"(?m)^  controller: eks\.amazonaws\.com/alb$",
        r"(?m)^    alb\.ingress\.kubernetes\.io/target-type: ip$",
        r"""(?m)^    alb\.ingress\.kubernetes\.io/listen-ports: '\[\{"HTTPS":443\}\]'$""",
        r"(?m)^    alb\.ingress\.kubernetes\.io/healthcheck-path: /healthz$",
    )
    if not all(re.search(pattern, source) for pattern in requirements):
        raise ValueError("ALB Ingress 선언이 변경되었습니다. 그림 생성기를 갱신하세요")
    services = [
        part
        for part in re.split(r"(?m)^---\s*$", source)
        if re.search(r"(?m)^kind: Service$", part)
    ]
    if not services or any(
        not re.search(r"(?m)^  type: ClusterIP$", part)
        or re.search(r"(?m)^  type: (?!ClusterIP$)", part)
        for part in services
    ):
        raise ValueError(
            "ClusterIP Service 선언이 변경되었습니다. 그림 생성기를 갱신하세요"
        )
    return {
        "controller": "eks.amazonaws.com/alb",
        "listener": "HTTPS:443",
        "target_type": "ip",
        "service_type": "ClusterIP",
    }


def box(x, y, width, height, title, detail, fill="#edf5ef"):
    return (
        f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="9" fill="{fill}" stroke="#b8cec0"/>'
        + text_at(x + 15, y + 28, title, 17)
        + text_at(x + 15, y + 53, detail, 13)
    )


def overview_svg(data):
    environment = data["environment"].upper()
    count = len(data["availability_zones"])
    width = 1110 / count
    elements = [
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 820" role="img" aria-labelledby="title description">',
        f'<title id="title">SceneTrip {environment} 구성도</title>',
        '<desc id="description">환경 예제·Terraform HCL·Helm 선언에서 생성한 형상. 실제 AWS 조회나 plan 결과가 아닙니다.</desc>',
        '<rect width="1200" height="820" fill="#f6f8f3"/>',
        '<g font-family="system-ui,sans-serif">',
        text_at(40, 45, f"SceneTrip · {environment} · {data['aws_region']}", 28),
        text_at(
            40,
            77,
            f"VPC {data['vpc_cidr']}  |  AZ {count}  |  NAT {data['nat_count']}  |  DB {'Multi-AZ' if data['multi_az'] else 'Single-AZ'}",
            18,
        ),
        box(
            40,
            102,
            1120,
            72,
            "허용 클라이언트 → HTTPS ALB + ACM → nginx Pod:8080 → scene-api:8080/v1",
            "Ingress → ClusterIP Service 참조 · 실제 ALB 대상은 Pod IP · ALB 이후 사설 HTTP",
        ),
        text_at(40, 207, "환경별 VPC / EKS Auto Mode workload / 격리된 DB subnet", 19),
    ]
    for index, zone in enumerate(data["availability_zones"]):
        x = 40 + index * width
        elements.extend(
            [
                text_at(x + 12, 240, zone, 18),
                box(
                    x,
                    255,
                    width - 15,
                    76,
                    "Public subnet · IGW",
                    data["subnets"]["public"][index],
                ),
                box(
                    x,
                    346,
                    width - 15,
                    76,
                    "Private application · NAT egress",
                    data["subnets"]["private"][index],
                ),
                box(
                    x,
                    437,
                    width - 15,
                    76,
                    "Private database · 외부 경로 없음",
                    data["subnets"]["data"][index],
                ),
            ]
        )
    elements.extend(
        [
            box(
                40,
                535,
                550,
                78,
                "scene-api → trip-guide:8899 → DeepSeek",
                "에이전트 → 내부 scene-api 조회 · replica 1 / Recreate",
            ),
            box(
                610,
                535,
                550,
                78,
                f"RDS PostgreSQL · {data['database_instance_class']}",
                f"PostGIS · pg_trgm · 백업 {data['backup_days']}일 · 삭제 보호 {str(data['deletion_protection']).lower()}",
            ),
            box(
                40,
                632,
                1120,
                78,
                "GitHub OIDC → STS 역할 → ECR / DB Job / Helm",
                "S3 state + lock · Secrets Manager / KMS · CloudWatch · 원격 SigNoz는 별도 구성",
            ),
            text_at(
                40,
                751,
                "구성 시각화: 환경 예제 + HCL의 지원하는 표현에서 계산. AWS 실시간 인벤토리·Terraform plan이 아닙니다.",
                14,
            ),
            text_at(
                40,
                782,
                "출처: provenance.json · 재생성: just education-generate · 변경 검출: just education-check",
                14,
            ),
            "</g></svg>\n",
        ]
    )
    return "\n".join(elements)


def reference_dot(resources, edges):
    lines = [
        "digraph hcl_references {",
        "  rankdir=LR;",
        '  label="HCL direct references (not terraform graph or live AWS)";',
    ]
    lines += [f'  "{resource}";' for resource in resources]
    lines += [f'  "{source}" -> "{target}";' for source, target in edges]
    return "\n".join(lines + ["}", ""])


def reference_svg(resources, edges):
    height = max(250, 115 + len(edges) * 33)
    elements = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1200 {height}" role="img" aria-labelledby="title">',
        '<title id="title">Terraform HCL 직접 참조 관계</title>',
        f'<rect width="1200" height="{height}" fill="#f6f8f3"/>',
        '<g font-family="system-ui,sans-serif">',
        text_at(
            35, 40, f"Terraform HCL 직접 참조 · 리소스 선언 {len(resources)}개", 24
        ),
        text_at(
            35,
            70,
            "왼쪽 리소스가 오른쪽을 참조합니다. 엔진 graph·배포 후 인벤토리가 아니며 local을 통한 간접 참조는 표시하지 않습니다.",
            13,
        ),
    ]
    for index, (source, target) in enumerate(edges):
        y = 105 + index * 33
        elements.extend(
            [
                text_at(35, y, source, 15),
                text_at(585, y, "→", 19),
                text_at(640, y, target, 15),
            ]
        )
    return "\n".join(elements + ["</g></svg>\n"])


def diagram_outputs(root):
    tf_paths = sorted(
        str(path.relative_to(root)) for path in (root / TF_ROOT).glob("*.tf")
    )
    if not tf_paths:
        raise ValueError("Terraform 소스가 없습니다")
    ingress = ingress_shape(root)
    hcl = "\n".join((root / path).read_text() for path in tf_paths)
    resources, edges = parse_resources(hcl)
    outputs = {
        "diagrams/hcl-references.dot": reference_dot(resources, edges),
        "diagrams/hcl-references.svg": reference_svg(resources, edges),
    }
    for environment in ("dev", "prd"):
        outputs[f"diagrams/{environment}-overview.svg"] = overview_svg(
            topology(root, environment)
        )
    sources = (
        tf_paths
        + sorted(
            str(path.relative_to(root))
            for path in (root / CHART_ROOT / "templates").glob("*.yaml")
        )
        + [
            f"platform/environments/{env}/terraform.tfvars.json.example"
            for env in ("dev", "prd")
        ]
    )
    sources += [
        "services/scene-api/src/main/resources/application.yaml",
        "agents/trip-guide/config/model.json",
        "platform/helm/scenetrip/values.yaml",
        "platform/helm/scenetrip/values-dev.yaml",
        "platform/helm/scenetrip/values-prd.yaml",
        "tools/education/source_diagrams.py",
    ]
    outputs["diagrams/provenance.json"] = (
        json.dumps(
            {
                "schema_version": 1,
                "kind": "source-configuration-not-live-aws-not-terraform-plan",
                "generator": "just education-generate",
                "ingress": ingress,
                "sources": fingerprint(root, sources),
                "outputs": {
                    name: hashlib.sha256(value.encode()).hexdigest()
                    for name, value in sorted(outputs.items())
                },
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n"
    )
    return outputs
