"""ALB 형상과 Auto Mode 보안 경계 검증, 제한 시간 내 준비 상태 확인."""

import ipaddress
import json
import time

from tools.aws.config import matched

POLL_SECONDS = 5
PRIVATE_NETWORKS = tuple(
    ipaddress.ip_network(cidr)
    for cidr in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)


def alb_values(outputs):
    subnet_ids = outputs["public_subnet_ids"]
    cidrs = outputs["public_subnet_cidrs"]
    if (
        not isinstance(subnet_ids, list)
        or not isinstance(cidrs, list)
        or len(subnet_ids) < 2
        or len(subnet_ids) != len(cidrs)
        or any(not isinstance(value, str) for value in [*subnet_ids, *cidrs])
        or len(set(subnet_ids)) != len(subnet_ids)
        or len(set(cidrs)) != len(cidrs)
    ):
        raise ValueError(
            "ALB subnet ID·CIDR은 서로 대응하는 2개 이상의 고유 목록이어야 합니다"
        )
    for subnet in subnet_ids:
        matched(r"subnet-(?:[a-f0-9]{8}|[a-f0-9]{17})", subnet, "ALB subnet")
    for cidr in cidrs:
        network = ipaddress.ip_network(cidr, strict=True)
        if (
            network.version != 4
            or not 24 <= network.prefixlen <= 27
            or not any(network.subnet_of(private) for private in PRIVATE_NETWORKS)
        ):
            raise ValueError(
                "ALB proxy 신뢰 범위는 RFC1918의 /24~27 subnet CIDR이어야 합니다"
            )
    for key in ("alb_security_group_id", "workload_security_group_id"):
        matched(r"sg-(?:[a-f0-9]{8}|[a-f0-9]{17})", outputs[key], "보안 그룹")
    matched(r"vpc-(?:[a-f0-9]{8}|[a-f0-9]{17})", outputs["vpc_id"], "VPC")
    return {
        "albSubnetIds": list(subnet_ids),
        "trustedProxyCidrs": list(cidrs),
        "securityGroupId": outputs["alb_security_group_id"],
    }


def verify_nodeclass(run, outputs):
    expected = outputs["workload_security_group_id"]
    run(
        [
            "kubectl",
            "wait",
            "nodeclass/default",
            "--for=condition=Ready",
            "--timeout=180s",
        ],
        quiet=True,
    )
    nodeclass = json.loads(
        run(
            [
                "kubectl",
                "get",
                "nodeclass",
                "default",
                "-o",
                "json",
                "--request-timeout=15s",
            ],
            quiet=True,
        )
    )
    groups = nodeclass.get("status", {}).get("securityGroups", [])
    if expected not in {group.get("id") for group in groups}:
        raise ValueError(
            "NodeClass의 실제 보안 그룹이 Terraform backend 규칙과 다릅니다"
        )
    if nodeclass.get("spec", {}).get("podSecurityGroupSelectorTerms"):
        raise ValueError(
            "NodeClass의 별도 Pod 보안 그룹은 이 배포 형상에서 지원하지 않습니다"
        )


def aws_read(run, operation, *arguments):
    return json.loads(
        run(
            [
                "aws",
                "elbv2",
                operation,
                *arguments,
                "--output",
                "json",
                "--cli-connect-timeout",
                "5",
                "--cli-read-timeout",
                "15",
            ],
            quiet=True,
            env={"AWS_MAX_ATTEMPTS": "1"},
        )
    )


def healthy_targets(run, arn):
    groups = aws_read(run, "describe-target-groups", "--load-balancer-arn", arn)[
        "TargetGroups"
    ]
    if not groups:
        return False
    for group in groups:
        if (group["TargetType"], group["Protocol"], group["Port"]) != (
            "ip",
            "HTTP",
            8080,
        ):
            raise ValueError("ALB backend는 gateway IP target HTTP:8080이어야 합니다")
        targets = aws_read(
            run, "describe-target-health", "--target-group-arn", group["TargetGroupArn"]
        )["TargetHealthDescriptions"]
        if not targets or any(
            target["TargetHealth"]["State"] != "healthy" for target in targets
        ):
            return False
    return True


def application_ready(run, settings, outputs, hostname):
    matched(
        rf"[a-zA-Z0-9.-]+\.{settings.region}\.elb\.amazonaws\.com",
        hostname,
        "ALB DNS 이름",
    )
    candidates = aws_read(run, "describe-load-balancers")["LoadBalancers"]
    matching = [item for item in candidates if item["DNSName"] == hostname]
    if not matching:
        return False
    if len(matching) != 1:
        raise ValueError("Ingress DNS에 대응하는 ALB를 하나로 식별하지 못했습니다")
    load_balancer = matching[0]
    if load_balancer["Type"] != "application":
        raise ValueError("로드 밸런서 타입이 application이 아닙니다")
    if load_balancer["Scheme"] != "internet-facing":
        raise ValueError("ALB 공개 형상이 Terraform 설계와 다릅니다")
    if set(load_balancer["SecurityGroups"]) != {outputs["alb_security_group_id"]}:
        raise ValueError("ALB 보안 그룹이 Terraform의 전용 보안 그룹과 다릅니다")
    if load_balancer["VpcId"] != outputs["vpc_id"] or {
        zone["SubnetId"] for zone in load_balancer["AvailabilityZones"]
    } != set(outputs["public_subnet_ids"]):
        raise ValueError("ALB VPC·subnet이 Terraform 환경의 형상과 다릅니다")
    arn = matched(
        rf"arn:aws:elasticloadbalancing:{settings.region}:{settings.account}:loadbalancer/app/[a-zA-Z0-9-]+/[a-f0-9]+",
        load_balancer["LoadBalancerArn"],
        "ALB ARN",
    )
    if load_balancer["State"]["Code"] == "failed":
        raise RuntimeError("AWS ALB 생성이 failed 상태입니다")
    return load_balancer["State"]["Code"] == "active" and healthy_targets(run, arn)


def wait_for_alb(run, settings, outputs, timeout=600):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        ingress = json.loads(
            run(
                [
                    "kubectl",
                    "-n",
                    "scenetrip",
                    "get",
                    "ingress",
                    "gateway",
                    "-o",
                    "json",
                    "--request-timeout=15s",
                ],
                quiet=True,
            )
        )
        endpoints = ingress.get("status", {}).get("loadBalancer", {}).get("ingress", [])
        hostname = endpoints[0].get("hostname", "") if endpoints else ""
        if hostname and application_ready(run, settings, outputs, hostname):
            return hostname
        time.sleep(POLL_SECONDS)
    raise TimeoutError(
        "ALB Ingress 주소·application active·gateway target healthy 대기 시간을 초과했습니다"
    )
