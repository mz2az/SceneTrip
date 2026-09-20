"""EKS가 살아 있는 동안 ALB를 정리하고 클러스터 삭제를 허용한다."""

import json
import time

from tools.aws.alb import aws_read
from tools.aws.config import matched

GATEWAY_TIMEOUT = 600
POLL_SECONDS = 5


def aws_query(run, service, operation, *arguments):
    return json.loads(
        run(
            [
                "aws",
                service,
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


def live_cluster(run, settings, resources):
    clusters = aws_query(run, "eks", "list-clusters")["clusters"]
    if not isinstance(clusters, list) or any(
        not isinstance(item, str) for item in clusters
    ):
        raise ValueError("EKS 클러스터 목록 형식이 올바르지 않습니다")
    if settings.cluster not in clusters:
        return None
    saved = resources.get("aws_eks_cluster.this")
    if not saved:
        raise ValueError("선택한 EKS가 Terraform state 밖에 존재합니다")
    cluster = aws_query(run, "eks", "describe-cluster", "--name", settings.cluster)[
        "cluster"
    ]
    arn = f"arn:aws:eks:{settings.region}:{settings.account}:cluster/{settings.cluster}"
    vpc = resources.get("aws_vpc.this", {}).get("id")
    if (
        cluster.get("arn") != arn
        or saved.get("arn") != arn
        or cluster.get("name") != settings.cluster
        or not vpc
        or cluster.get("resourcesVpcConfig", {}).get("vpcId") != vpc
        or not saved.get("endpoint")
        or cluster.get("endpoint") != saved["endpoint"]
        or cluster.get("status") not in {"ACTIVE", "DELETING"}
    ):
        raise ValueError("실제 EKS 신원·VPC·endpoint·상태가 삭제 대상과 다릅니다")
    return cluster


def validate_alb(item, settings, resources):
    group = resources.get("aws_security_group.alb", {}).get("id")
    subnets = {
        value["id"]
        for address, value in resources.items()
        if address.startswith("aws_subnet.public[")
    }
    if (
        item.get("Type") != "application"
        or item.get("Scheme") != "internet-facing"
        or not group
        or set(item.get("SecurityGroups", [])) != {group}
        or len(subnets) < 2
        or {zone["SubnetId"] for zone in item.get("AvailabilityZones", [])} != subnets
    ):
        raise ValueError("삭제 대상 VPC의 ALB 타입·SG·subnet이 state와 다릅니다")
    matched(
        rf"arn:aws:elasticloadbalancing:{settings.region}:{settings.account}:loadbalancer/app/[a-zA-Z0-9-]+/[a-f0-9]+",
        item.get("LoadBalancerArn"),
        "삭제 대상 ALB ARN",
    )


def matching_albs(run, settings, resources):
    vpc = resources.get("aws_vpc.this", {}).get("id")
    if not vpc:
        return []
    matched(r"vpc-(?:[a-f0-9]{8}|[a-f0-9]{17})", vpc, "삭제 대상 VPC")
    candidates = aws_read(run, "describe-load-balancers")["LoadBalancers"]
    if not isinstance(candidates, list):
        raise TypeError("ALB 목록 형식이 올바르지 않습니다")
    matching = [item for item in candidates if item.get("VpcId") == vpc]
    if len(matching) > 1:
        raise ValueError("선택한 VPC에 예상보다 많은 로드 밸런서가 있습니다")
    for item in matching:
        validate_alb(item, settings, resources)
    return matching


def get_ingress(run):
    raw = run(
        [
            "kubectl",
            "-n",
            "scenetrip",
            "get",
            "ingress",
            "gateway",
            "--ignore-not-found",
            "-o",
            "json",
            "--request-timeout=15s",
        ],
        quiet=True,
    )
    return json.loads(raw) if raw.strip() else None


def validate_ingress(ingress, settings, albs):
    if ingress is None:
        return
    metadata = ingress.get("metadata", {})
    annotations = metadata.get("annotations", {})
    if (
        metadata.get("name") != "gateway"
        or metadata.get("namespace") != "scenetrip"
        or annotations.get("meta.helm.sh/release-name") != "scenetrip"
        or annotations.get("meta.helm.sh/release-namespace") != "scenetrip"
        or ingress.get("spec", {}).get("ingressClassName") != f"{settings.cluster}-alb"
    ):
        raise ValueError(
            "Ingress가 선택한 환경의 SceneTrip Helm 소유 리소스가 아닙니다"
        )
    endpoints = ingress.get("status", {}).get("loadBalancer", {}).get("ingress", [])
    hosts = {item.get("hostname") for item in endpoints}
    if hosts and albs and hosts != {item["DNSName"] for item in albs}:
        raise ValueError("Ingress DNS와 확인한 ALB가 다릅니다")


def helm_release(run):
    releases = json.loads(
        run(
            [
                "helm",
                "list",
                "--namespace",
                "scenetrip",
                "--all",
                "--filter",
                "^scenetrip$",
                "--output",
                "json",
            ],
            quiet=True,
        )
    )
    if not isinstance(releases, list) or len(releases) > 1:
        raise ValueError("Helm release 목록을 하나로 식별하지 못했습니다")
    if releases and (
        releases[0].get("name") != "scenetrip"
        or releases[0].get("namespace") != "scenetrip"
    ):
        raise ValueError("Helm release의 이름·namespace가 다릅니다")
    return bool(releases)


def wait_gateway_absent(run, settings, resources, timeout=GATEWAY_TIMEOUT):
    deadline = time.monotonic() + timeout
    while True:
        ingress = get_ingress(run)
        albs = matching_albs(run, settings, resources)
        if ingress is None and not albs:
            return
        if time.monotonic() >= deadline:
            raise TimeoutError(
                "Ingress·ALB 정리 대기 시간을 초과했습니다. finalizer는 강제로 제거하지 않습니다"
            )
        time.sleep(POLL_SECONDS)


def gateway_cleanup_available(cluster, albs, resources):
    if cluster is None or cluster["status"] == "DELETING":
        if albs:
            raise RuntimeError(
                "EKS가 없거나 삭제 중인데 ALB가 남았습니다. orphan 리소스를 조사하세요"
            )
        return False
    if not all(
        address in resources
        for address in (
            "aws_eks_access_entry.deployment",
            "aws_eks_access_policy_association.deployment",
        )
    ):
        if albs:
            raise RuntimeError(
                "EKS 배포 접근 권한이 state에 없는데 ALB가 남았습니다. 접근 권한을 복구해 정리하세요"
            )
        print("EKS 접근 리소스와 ALB가 이미 제거되어 Kubernetes 정리를 생략합니다")
        return False
    return True


def remove_gateway(run, settings, resources):
    cluster = live_cluster(run, settings, resources)
    albs = matching_albs(run, settings, resources)
    if not gateway_cleanup_available(cluster, albs, resources):
        return
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
    ingress = get_ingress(run)
    validate_ingress(ingress, settings, albs)
    release = helm_release(run)
    if ingress is not None:
        delete_ingress(run)
    wait_gateway_absent(run, settings, resources)
    if release:
        run(
            [
                "helm",
                "uninstall",
                "scenetrip",
                "--namespace",
                "scenetrip",
                "--no-hooks",
                "--wait",
                "--timeout",
                "10m",
            ],
            quiet=True,
        )
    print("Ingress·ALB 정리와 Helm 해제 확인 완료")


def delete_ingress(run):
    run(
        [
            "kubectl",
            "-n",
            "scenetrip",
            "delete",
            "ingress",
            "gateway",
            "--ignore-not-found",
            "--wait=false",
            "--request-timeout=15s",
        ],
        quiet=True,
    )
