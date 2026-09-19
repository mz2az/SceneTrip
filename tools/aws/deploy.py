"""비밀값 동기화, DB 준비, Helm 배포. 민감 자료는 stdin으로만 전달한다."""

import ipaddress
import json
import secrets
from pathlib import Path

from tools.aws.alb import alb_values, verify_nodeclass, wait_for_alb
from tools.aws.config import matched, validate_secret

NAMESPACE = "scenetrip"
# 버전과 다중 아키텍처 manifest digest를 함께 고정한다.
POSTGRES_IMAGE = "postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3"


def sync_secret(run, name, values):
    manifest = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": name, "namespace": NAMESPACE},
        "type": "Opaque",
        "stringData": values,
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(manifest), quiet=True)


def secret_value(run, arn, kind):
    raw = run(
        [
            "aws",
            "secretsmanager",
            "get-secret-value",
            "--secret-id",
            arn,
            "--output",
            "json",
        ],
        quiet=True,
    )
    return validate_secret(kind, json.loads(json.loads(raw)["SecretString"]))


def database_credentials(run, arn):
    versions = json.loads(
        run(
            [
                "aws",
                "secretsmanager",
                "list-secret-version-ids",
                "--secret-id",
                arn,
                "--output",
                "json",
            ],
            quiet=True,
        )
    )
    if versions.get("Versions"):
        return secret_value(run, arn, "database")
    value = {
        "username": "app_runtime",
        "password": secrets.token_urlsafe(36),
        "migration_username": "app_migrate",
        "migration_password": secrets.token_urlsafe(36),
    }
    body = {"SecretId": arn, "SecretString": json.dumps(value)}
    run(
        [
            "aws",
            "secretsmanager",
            "put-secret-value",
            "--cli-input-json",
            "file:///dev/stdin",
        ],
        stdin=json.dumps(body),
        quiet=True,
    )
    return value


def gateway_values(settings, outputs):
    cidrs = outputs["ingress_allowed_cidrs"]
    if not isinstance(cidrs, list) or not cidrs:
        raise ValueError("외부 API 접근 CIDR을 명시해야 합니다")
    for cidr in cidrs:
        network = ipaddress.ip_network(cidr, strict=True)
        if network.version != 4 or network.prefixlen == 0:
            raise ValueError("전체 인터넷 또는 IPv6 접근은 지원하지 않습니다")
    certificate = outputs["ingress_certificate_arn"]
    matched(
        rf"arn:aws:acm:{settings.region}:{settings.account}:certificate/[a-f0-9-]{{36}}",
        certificate,
        "ACM 인증서",
    )
    return {
        "gateway": {
            "certificateArn": certificate,
            "allowedCidrs": cidrs,
            "host": settings.domain,
        }
    }


def bootstrap_job(host, image=POSTGRES_IMAGE):
    matched(r"[a-zA-Z0-9.-]+", host, "DB 호스트")
    script = Path(__file__).with_name("db-bootstrap.sh").read_text()
    return {
        "apiVersion": "batch/v1",
        "kind": "Job",
        "metadata": {"name": "database-bootstrap", "namespace": NAMESPACE},
        "spec": {
            "backoffLimit": 0,
            "activeDeadlineSeconds": 300,
            "ttlSecondsAfterFinished": 300,
            "template": {
                "metadata": {"labels": {"app": "database-bootstrap"}},
                "spec": {
                    "restartPolicy": "Never",
                    "automountServiceAccountToken": False,
                    "nodeSelector": {"kubernetes.io/arch": "amd64"},
                    "securityContext": {"seccompProfile": {"type": "RuntimeDefault"}},
                    "containers": [
                        {
                            "name": "bootstrap",
                            "image": image,
                            "command": ["sh", "-c", script],
                            "env": [{"name": "PGHOST", "value": host}],
                            "envFrom": [
                                {"secretRef": {"name": "database-admin-transient"}}
                            ],
                            "resources": {
                                "requests": {"cpu": "50m", "memory": "64Mi"},
                                "limits": {"memory": "128Mi"},
                            },
                            "securityContext": {
                                "runAsUser": 10001,
                                "runAsNonRoot": True,
                                "allowPrivilegeEscalation": False,
                                "readOnlyRootFilesystem": True,
                                "capabilities": {"drop": ["ALL"]},
                            },
                        }
                    ],
                },
            },
        },
    }


def delete_transient(run):
    run(
        [
            "kubectl",
            "-n",
            NAMESPACE,
            "delete",
            "job",
            "database-bootstrap",
            "database-migrate",
            "database-finalize",
            "network-deny-check",
            "--ignore-not-found",
        ],
        quiet=True,
    )
    run(
        [
            "kubectl",
            "-n",
            NAMESPACE,
            "delete",
            "secret",
            "database-admin-transient",
            "database-migration-transient",
            "--ignore-not-found",
        ],
        quiet=True,
    )


def apply_job(run, manifest):
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(manifest), quiet=True)
    run(
        [
            "kubectl",
            "-n",
            NAMESPACE,
            "wait",
            "--for=condition=complete",
            f"job/{manifest['metadata']['name']}",
            "--timeout=360s",
        ],
        quiet=True,
    )


def migrate_database(run, outputs, database, migration_image):
    delete_transient(run)
    raw = run(
        [
            "aws",
            "secretsmanager",
            "get-secret-value",
            "--secret-id",
            outputs["database_master_secret_arn"],
            "--output",
            "json",
        ],
        quiet=True,
    )
    master = json.loads(json.loads(raw)["SecretString"])
    if (
        not isinstance(master, dict)
        or not {"username", "password"} <= master.keys()
        or any(
            not isinstance(master[key], str) or not master[key].strip()
            for key in ("username", "password")
        )
    ):
        raise ValueError("RDS 관리자 비밀값이 올바르지 않습니다")
    try:
        sync_secret(
            run,
            "database-admin-transient",
            {
                "PGUSER": master["username"],
                "PGPASSWORD": master["password"],
                "RUNTIME_PASSWORD": database["password"],
                "MIGRATION_PASSWORD": database["migration_password"],
            },
        )
        apply_job(run, bootstrap_job(outputs["database_host"]))
        sync_secret(
            run,
            "database-migration-transient",
            {
                "SCENETRIP_DB_USER": database["migration_username"],
                "SCENETRIP_DB_PASSWORD": database["migration_password"],
                "SCENETRIP_DB_URL": f"jdbc:postgresql://{outputs['database_host']}:5432/scenetrip?sslmode=require",
            },
        )
        job = bootstrap_job(outputs["database_host"], migration_image)
        container = {
            **job["spec"]["template"]["spec"]["containers"][0],
            "name": "migrate",
            "envFrom": [{"secretRef": {"name": "database-migration-transient"}}],
            "resources": {
                "requests": {"cpu": "100m", "memory": "256Mi"},
                "limits": {"memory": "512Mi"},
            },
            "volumeMounts": [{"name": "tmp", "mountPath": "/tmp"}],
        }
        container = {
            key: value
            for key, value in container.items()
            if key not in {"command", "env"}
        }
        pod = {
            **job["spec"]["template"]["spec"],
            "containers": [container],
            "volumes": [{"name": "tmp", "emptyDir": {}}],
        }
        migration = {
            **job,
            "metadata": {"name": "database-migrate", "namespace": NAMESPACE},
            "spec": {
                **job["spec"],
                "template": {
                    "metadata": {"labels": {"app": "database-migrate"}},
                    "spec": pod,
                },
            },
        }
        apply_job(run, migration)
        final = bootstrap_job(outputs["database_host"])
        final = {
            **final,
            "metadata": {"name": "database-finalize", "namespace": NAMESPACE},
        }
        apply_job(run, final)
    finally:
        delete_transient(run)


def validate_environment_outputs(settings, outputs):
    for key, value in {
        "environment": settings.environment,
        "aws_account_id": settings.account,
        "aws_region": settings.region,
        "cluster_name": settings.cluster,
        "namespace": NAMESPACE,
    }.items():
        if outputs[key] != value:
            raise ValueError(f"Terraform 출력 {key}가 선택한 환경과 다릅니다")


def deploy(run, root, settings, outputs):
    validate_environment_outputs(settings, outputs)
    values = gateway_values(settings, outputs)
    values = {**values, "gateway": {**values["gateway"], **alb_values(outputs)}}
    cluster = json.loads(
        run(
            [
                "aws",
                "eks",
                "describe-cluster",
                "--name",
                settings.cluster,
                "--output",
                "json",
            ],
            quiet=True,
        )
    )["cluster"]
    service_cidr = ipaddress.ip_network(
        cluster["kubernetesNetworkConfig"]["serviceIpv4Cidr"], strict=True
    )
    values = {**values, "network": {"dnsCidr": str(service_cidr[10]) + "/32"}}
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
    verify_nodeclass(run, outputs)
    namespace = {
        "apiVersion": "v1",
        "kind": "Namespace",
        "metadata": {
            "name": NAMESPACE,
            "labels": {"pod-security.kubernetes.io/enforce": "restricted"},
        },
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(namespace), quiet=True)
    cni = {
        "apiVersion": "v1",
        "kind": "ConfigMap",
        "metadata": {"name": "amazon-vpc-cni", "namespace": "kube-system"},
        "data": {"enable-network-policy-controller": "true"},
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(cni), quiet=True)
    credentials = database_credentials(run, outputs["app_secret_arns"]["database"])
    for kind, name in (
        ("scene_api", "scene-api-secrets"),
        ("trip_guide", "trip-guide-secrets"),
    ):
        sync_secret(
            run, name, secret_value(run, outputs["app_secret_arns"][kind], kind)
        )
    sync_secret(
        run,
        "database-runtime",
        {
            "SPRING_DATASOURCE_USERNAME": credentials["username"],
            "SPRING_DATASOURCE_PASSWORD": credentials["password"],
        },
    )

    def image(key):
        return outputs["ecr_repository_urls"][key] + ":" + settings.tag

    migrate_database(run, outputs, credentials, image("migration"))
    values = {
        **values,
        "environment": settings.environment,
        "database": {"host": outputs["database_host"]},
        "sceneApi": {"image": image("scene_api")},
        "tripGuide": {"image": image("trip_guide")},
    }
    run(
        [
            "helm",
            "upgrade",
            "--install",
            "scenetrip",
            str(root / "platform/helm/scenetrip"),
            "--namespace",
            NAMESPACE,
            "--values",
            str(root / f"platform/helm/scenetrip/values-{settings.environment}.yaml"),
            "--values",
            "-",
            "--atomic",
            "--wait",
            "--timeout",
            "10m",
            "--history-max",
            "10",
        ],
        stdin=json.dumps(values),
        quiet=True,
    )
    verify_network_deny(run, image("trip_guide"))
    for name in ("scene-api", "trip-guide", "gateway"):
        run(
            [
                "kubectl",
                "-n",
                NAMESPACE,
                "rollout",
                "status",
                f"deployment/{name}",
                "--timeout=120s",
            ],
            quiet=True,
        )
    hostname = wait_for_alb(run, settings, outputs)
    print(f"DNS 연결 대상 ALB: {hostname}")
    print(
        f"{settings.environment} Helm 배포 완료: {settings.tag}; DB 변경은 Helm 롤백 대상이 아닙니다"
    )
    return hostname


def verify_network_deny(run, image):
    positive = "import socket; socket.create_connection(('scene-api',8080),timeout=5).close(); socket.create_connection(('127.0.0.1',8899),timeout=5).close()"
    run(
        [
            "kubectl",
            "-n",
            NAMESPACE,
            "exec",
            "deployment/trip-guide",
            "--",
            "/usr/bin/python3",
            "-c",
            positive,
        ],
        quiet=True,
    )
    script = """import socket
for host, port in [('scene-api',8080),('trip-guide',8899)]:
    try:
        connection = socket.create_connection((host, port), timeout=3)
    except (TimeoutError, ConnectionRefusedError):
        continue
    connection.close()
    raise SystemExit('NetworkPolicy isolation failed')
"""
    job = bootstrap_job("unused.example", image)
    container = job["spec"]["template"]["spec"]["containers"][0]
    container = {
        key: value for key, value in container.items() if key not in {"env", "envFrom"}
    }
    container = {
        **container,
        "name": "probe",
        "command": ["/usr/bin/python3", "-c", script],
    }
    pod = {**job["spec"]["template"]["spec"], "containers": [container]}
    job = {
        **job,
        "metadata": {"name": "network-deny-check", "namespace": NAMESPACE},
        "spec": {
            **job["spec"],
            "template": {
                "metadata": {"labels": {"app": "network-deny-check"}},
                "spec": pod,
            },
        },
    }
    try:
        apply_job(run, job)
    finally:
        run(
            [
                "kubectl",
                "-n",
                NAMESPACE,
                "delete",
                "job",
                "network-deny-check",
                "--ignore-not-found",
            ],
            quiet=True,
        )
    print("허용되지 않은 Pod의 API·agent 연결 차단 확인 완료")
