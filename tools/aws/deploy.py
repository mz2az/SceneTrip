"""비밀값 동기화, DB 준비, Helm 배포. 민감 자료는 stdin으로만 전달한다."""

import base64
import ipaddress
import json
import secrets
import time
from pathlib import Path

from tools.aws.alb import (
    alb_values,
    verify_nodeclass,
    wait_for_alb,
    wait_for_signoz_alb,
)
from tools.aws.config import (
    SIGNOZ_DOMAINS,
    SIGNOZ_ENVIRONMENTS,
    matched,
    validate_secret,
)

NAMESPACE = "scenetrip"
# 버전과 다중 아키텍처 manifest digest를 함께 고정한다.
POSTGRES_IMAGE = "postgres:17.6-bookworm@sha256:f3bd19c606e442c3d7bdfa8002e03fe260a1023351e0ea4598032022b68dd6e3"


# SigNoz(ADR 0025). 수집기 Service 는 차트가 `<릴리스>-otel-collector` 로 만든다 — scene-api 가 OTLP gRPC 로 보낸다.
SIGNOZ_NAMESPACE = "signoz"
SIGNOZ_RELEASE = "signoz"
SIGNOZ_OTLP_ENDPOINT = "http://signoz-otel-collector.signoz.svc.cluster.local:4317"
SIGNOZ_STORAGE_CLASS = "signoz-gp3"
SIGNOZ_PASSWORD_SECRET = "signoz-clickhouse"
# SigNoz 관리자 — 로그인 아이디일 뿐 메일을 보내지 않는다(개인 메일을 저장소에 적지 않는다). 팀원은 SigNoz 안에서 초대한다.
SIGNOZ_ROOT_EMAIL = "admin@scenetrip.io"
SIGNOZ_ROOT_SECRET = "signoz-root"


def sync_secret(run, name, values):
    manifest = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": name, "namespace": NAMESPACE},
        "type": "Opaque",
        "stringData": values,
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(manifest), quiet=True)


def secret_value(run, arn, kind, environment=None):
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
    return validate_secret(
        kind, json.loads(json.loads(raw)["SecretString"]), environment
    )


def generated_once(run, arn, kind, make):
    """비밀이 비어 있으면 make() 로 만들어 저장하고, 있으면 저장된 것을 그대로 쓴다.

    배포가 사람 대신 만드는 비밀(DB 역할 비밀번호, 로그인 서명 키)의 공통 규칙이다.
    **환경이 처음 생길 때 한 번만 만든다.** 이후 배포는 저장된 값을 다시 쓴다 — 배포마다
    바뀌면 DB 접속이 끊기거나(비밀번호) 로그인한 사람이 전부 로그아웃된다(서명 키).
    값은 stdin 으로만 넘기고 명령 줄에 싣지 않는다.

    칸에 키가 새로 늘면(make() 가 더 많은 키를 내면) **있던 값은 건드리지 않고 없는 키만**
    만들어 채운다. 예: 서명 키만 있던 auth 칸에 애플 토큰 암호화 키가 더해질 때.
    """
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
        stored = json.loads(
            json.loads(
                run(
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
            )["SecretString"]
        )
        fresh = make()
        if not isinstance(stored, dict) or set(fresh) <= set(stored):
            return validate_secret(kind, stored)
        # 이 칸에 키가 새로 늘었다(예: auth 에 암호화 키). **있던 값은 그대로 두고 없는 키만**
        # 새로 만들어 채운다 — 있던 서명 키를 바꾸면 전원이 로그아웃된다.
        value = validate_secret(kind, {**fresh, **stored})
    else:
        value = validate_secret(kind, make())
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


def database_credentials(run, arn):
    return generated_once(
        run,
        arn,
        "database",
        lambda: {
            "username": "app_runtime",
            "password": secrets.token_urlsafe(36),
            "migration_username": "app_migrate",
            "migration_password": secrets.token_urlsafe(36),
        },
    )


def auth_secret(run, arn):
    """로그인 액세스 토큰(JWT) 서명 키 — 환경당 한 번 만들어 계속 쓴다 (MZ2AZ-332, ADR 0018).

    48 바이트 난수의 base64. 사람은 이 값을 보지 않는다.

    카카오 키가 든 scene_api 칸에 넣지 않고 auth 칸을 따로 쓴다. 사람이 카카오 키를 바꾸며
    JSON 을 통째로 다시 넣으면 서명 키가 빠지고, 다음 배포가 「없다」 로 보고 새로 만들어
    로그인한 사람이 전부 로그아웃된다. 사람이 만지는 칸과 배포가 만지는 칸을 나눠 그 길을 막는다.
    """
    return generated_once(
        run,
        arn,
        "auth",
        lambda: {
            "SCENETRIP_AUTH_JWT_SECRET": base64.b64encode(
                secrets.token_bytes(48)
            ).decode(),
            # 애플 refresh token 암호화 키(AES-256). 서명 키와 따로 — scene-api TokenCipher 참고.
            "SCENETRIP_AUTH_TOKEN_ENCRYPTION_KEY": base64.b64encode(
                secrets.token_bytes(32)
            ).decode(),
            # 서비스 비밀값(ADR 0023) — trip-guide 가 scene-api 읽기에 달아 한도 열쇠를 믿게 한다.
            "SCENETRIP_SERVICE_TOKEN": base64.b64encode(
                secrets.token_bytes(32)
            ).decode(),
        },
    )


def trip_guide_secret(auth):
    """trip-guide 의 Secret — 서비스 비밀값 **하나만**.

    auth 칸을 통째로 넘기지 않는다. 서명 키·암호화 키가 에이전트에 가면 에이전트가 토큰을
    만들 수 있게 된다(ADR 0013·0023: 에이전트는 자격 증명을 쥐지 않는다). 모델 키는 없다 — Bedrock 은
    Pod Identity 역할의 임시 자격 증명으로 부르고(MZ2AZ-317), 그래서 trip-guide 용 Secrets Manager 비밀값도 없다.
    """
    return {"SCENETRIP_SERVICE_TOKEN": auth["SCENETRIP_SERVICE_TOKEN"]}


def gateway_values(settings, outputs):
    # 인터넷 전체 공개 여부는 Terraform 이 내리고 출력한다 — DEV·PRD 모두 연다(ADR 0019·0024).
    public = outputs.get("ingress_public", False)
    if not isinstance(public, bool):
        raise TypeError("ingress_public 은 true/false 여야 합니다")
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
            "public": public,
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


DATA_LOAD_JOB = "data-load"
DATA_LOAD_SECRET = "database-load-transient"
DATA_LOAD_DEADLINE = 1800
DATA_LOAD_POLL_SECONDS = 60


def data_load_job(host, image):
    """원격 데이터 적재 Job(MZ2AZ-414, docs/project/plans/remote-data-load.md).

    DB 초기화 Job 과 같은 모양(restricted)에, 적재 이미지의 entrypoint(seed/remote-load.sh)를 그대로 쓴다. 편의시설 압축을
    /tmp 에 풀어 emptyDir 1Gi, 91 만 행이라 상한 30 분·메모리 512Mi. 라벨 app=data-load 는 네트워크 정책
    database-jobs 가 5432 만 연다.
    """
    job = bootstrap_job(host, image)
    container = {
        key: value
        for key, value in job["spec"]["template"]["spec"]["containers"][0].items()
        if key != "command"
    }
    container = {
        **container,
        "name": "data-load",
        "env": [
            {"name": "PGHOST", "value": host},
            {"name": "PGDATABASE", "value": "scenetrip"},
            {"name": "PGSSLMODE", "value": "require"},
        ],
        "envFrom": [{"secretRef": {"name": DATA_LOAD_SECRET}}],
        "resources": {
            "requests": {"cpu": "250m", "memory": "256Mi"},
            "limits": {"memory": "512Mi"},
        },
        "volumeMounts": [{"name": "tmp", "mountPath": "/tmp"}],
    }
    return {
        **job,
        "metadata": {"name": DATA_LOAD_JOB, "namespace": NAMESPACE},
        "spec": {
            **job["spec"],
            "activeDeadlineSeconds": DATA_LOAD_DEADLINE,
            "template": {
                "metadata": {"labels": {"app": DATA_LOAD_JOB}},
                "spec": {
                    **job["spec"]["template"]["spec"],
                    "containers": [container],
                    "volumes": [{"name": "tmp", "emptyDir": {"sizeLimit": "1Gi"}}],
                },
            },
        },
    }


def delete_data_load(run):
    run(
        [
            "kubectl",
            "-n",
            NAMESPACE,
            "delete",
            "job",
            DATA_LOAD_JOB,
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
            DATA_LOAD_SECRET,
            "--ignore-not-found",
        ],
        quiet=True,
    )


def data_load_progress(run):
    """Job 로그 중 단계 줄(==>)과 요약·실패 줄만 — 적재 SQL 의 출력이나 비밀값은 내보내지 않는다."""
    try:
        logs = run(
            ["kubectl", "-n", NAMESPACE, "logs", f"job/{DATA_LOAD_JOB}", "--tail=200"],
            quiet=True,
        )
    except RuntimeError:
        return []
    return [
        line
        for line in logs.splitlines()
        if line.startswith(("==> ", "DATA_LOAD_SUMMARY ", "DATA_LOAD_FAILED "))
    ]


def load_data(run, outputs, database, image, commit):
    """적재 Job 을 띄우고 끝날 때까지 기다린다. 적재 계정은 소유자(app_migrate) — 적재 SQL 이 임시 표·TRUNCATE·MV 갱신을
    한다. 몇 번을 돌려도 같다(지우지 않는다). 끝나면 성공·실패와 관계없이 Job 과 임시 Secret 을 지운다."""
    matched(r"[0-9a-f]{7,40}", commit, "커밋")
    delete_data_load(run)
    try:
        sync_secret(
            run,
            DATA_LOAD_SECRET,
            {
                "PGUSER": database["migration_username"],
                "PGPASSWORD": database["migration_password"],
                "SCENETRIP_COMMIT": commit,
            },
        )
        run(
            ["kubectl", "apply", "-f", "-"],
            stdin=json.dumps(data_load_job(outputs["database_host"], image)),
            quiet=True,
        )
        # 기다리는 동안 1 분마다 단계 줄을 읽어 새 줄만 찍는다. 끝난 뒤에만 읽으면 제한 시간에 걸린 Job 은 파드가 지워져
        # 로그가 없다 — 어느 단계에서 멈췄는지 모른다(2026-10-13 DEV).
        seen = []
        complete = False
        for _ in range(DATA_LOAD_DEADLINE // DATA_LOAD_POLL_SECONDS + 2):
            try:
                run(
                    [
                        "kubectl",
                        "-n",
                        NAMESPACE,
                        "wait",
                        "--for=condition=complete",
                        f"job/{DATA_LOAD_JOB}",
                        f"--timeout={DATA_LOAD_POLL_SECONDS}s",
                    ],
                    quiet=True,
                )
                complete = True
            except RuntimeError:
                complete = False
            lines = data_load_progress(run) or seen
            for line in lines[len(seen) :]:
                print(line)
            seen = lines
            if complete or any(line.startswith("DATA_LOAD_FAILED ") for line in seen):
                break
        if not complete:
            raise RuntimeError(
                "데이터 적재 Job 이 끝나지 못했습니다 — 위 단계 줄을 보세요"
            )
        if not any(line.startswith("DATA_LOAD_SUMMARY ") for line in seen):
            raise RuntimeError("데이터 적재 요약이 없습니다")
    finally:
        delete_data_load(run)


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


def deploy(run, root, settings, outputs, signoz_chart=None):
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
    signoz = settings.environment in SIGNOZ_ENVIRONMENTS
    if signoz:
        install_signoz(run, root, settings, outputs, signoz_chart)
        expose_signoz(run, settings, outputs)
    credentials = database_credentials(run, outputs["app_secret_arns"]["database"])
    # scene-api 는 사람이 넣은 카카오 키와 배포가 만든 서명 키를 한 Secret 으로 받는다 —
    # Helm 차트가 scene-api-secrets 를 통째로 환경변수로 넣는다(envFrom).
    auth = auth_secret(run, outputs["app_secret_arns"]["auth"])
    sync_secret(
        run,
        "scene-api-secrets",
        {
            **secret_value(
                run,
                outputs["app_secret_arns"]["scene_api"],
                "scene_api",
                settings.environment,
            ),
            **auth,
        },
    )
    sync_secret(
        run,
        "trip-guide-secrets",
        trip_guide_secret(auth),
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
        # 사용자 사진 버킷 — bootstrap 이 만들고 Terraform 은 이름만 출력한다(docs/project/plans/review.md §13).
        "sceneApi": {
            "image": image("scene_api"),
            "mediaBucket": outputs["user_media_bucket"],
        },
        "tripGuide": {"image": image("trip_guide")},
        # 비면 scene-api 가 OTEL 을 끈다(templates/config.yaml). SigNoz 를 올린 환경만 켠다.
        "otel": {"endpoint": SIGNOZ_OTLP_ENDPOINT if signoz else ""},
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


def signoz_storage_class():
    """EKS Auto Mode 의 내장 EBS 드라이버(`ebs.csi.eks.amazonaws.com`)로 gp3·암호화 디스크. PVC 를 지우면 디스크도 지운다."""
    return {
        "apiVersion": "storage.k8s.io/v1",
        "kind": "StorageClass",
        "metadata": {"name": SIGNOZ_STORAGE_CLASS},
        "provisioner": "ebs.csi.eks.amazonaws.com",
        "volumeBindingMode": "WaitForFirstConsumer",
        "reclaimPolicy": "Delete",
        "allowVolumeExpansion": True,
        "parameters": {"type": "gp3", "encrypted": "true"},
    }


def clickhouse_password(run):
    """SigNoz 의 ClickHouse 비밀번호 — 클러스터에 한 번 만들고 다시 쓴다. 바꾸면 이미 뜬 ClickHouse 와 어긋난다.

    사람이 쓸 값이 아니라 Secrets Manager 에 두지 않는다. 클러스터를 지우면 디스크와 함께 사라진다. 차트가 이 값을
    `tcp://user:password@…` 주소에 그대로 넣으므로 16 진 문자만 쓴다."""
    raw = run(
        [
            "kubectl",
            "-n",
            SIGNOZ_NAMESPACE,
            "get",
            "secret",
            SIGNOZ_PASSWORD_SECRET,
            "--ignore-not-found",
            "-o",
            "json",
        ],
        quiet=True,
    )
    if raw.strip():
        encoded = json.loads(raw).get("data", {}).get("password", "")
        password = base64.b64decode(encoded).decode() if encoded else ""
        return matched(r"[0-9a-f]{64}", password, "SigNoz ClickHouse 비밀번호")
    password = secrets.token_hex(32)
    manifest = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": SIGNOZ_PASSWORD_SECRET, "namespace": SIGNOZ_NAMESPACE},
        "type": "Opaque",
        "stringData": {"password": password},
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(manifest), quiet=True)
    return password


def signoz_root_password(run, arn):
    """SigNoz 관리자 비밀번호 — 환경당 한 번 만들어 Secrets Manager 에 두고 다시 쓴다. 사람이 로그인할 때 거기서 꺼낸다.

    SigNoz 는 조직(첫 관리자)이 있어야 수집기에 파이프라인 설정을 내려준다(opamp). 사람이 가입하기를 기다리면 DEV 를 올릴
    때마다 trace 가 버려진다(2026-10-13 실측: 「cannot create agent without orgId」, 수집기가 4317 을 열지 않음). 그래서 배포가
    관리자를 만든다. SigNoz 의 비밀번호 규칙(대·소문자·숫자·기호)을 늘 만족하도록 끝에 네 종류를 하나씩 붙인다."""
    return generated_once(
        run,
        arn,
        "signoz",
        lambda: {"SIGNOZ_ROOT_PASSWORD": secrets.token_urlsafe(24) + "Aa1!"},
    )["SIGNOZ_ROOT_PASSWORD"]


def install_signoz(run, root, settings, outputs, chart):
    """SigNoz 를 `signoz` 네임스페이스에 올린다(ADR 0025). 이미지는 환경 ECR 의 복사본(aws.mirror_signoz)에서 받는다."""
    if not chart:
        raise ValueError("SigNoz 차트 파일이 지정되지 않았습니다")
    registry = (
        f"{settings.account}.dkr.ecr.{settings.region}.amazonaws.com"
        f"/scenetrip-{settings.environment}/mirror"
    )
    if outputs["ecr_repository_urls"]["mirror_signoz"] != registry + "/signoz/signoz":
        raise ValueError("Terraform ECR 출력이 선택한 계정·환경과 다릅니다")
    namespace = {
        "apiVersion": "v1",
        "kind": "Namespace",
        "metadata": {
            "name": SIGNOZ_NAMESPACE,
            # ClickHouse 초기화 컨테이너가 restricted 를 넘는다. 특권·호스트 경로는 baseline 이 막는다.
            "labels": {
                "pod-security.kubernetes.io/enforce": "baseline",
                "pod-security.kubernetes.io/warn": "restricted",
            },
        },
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(namespace), quiet=True)
    run(
        ["kubectl", "apply", "-f", "-"],
        stdin=json.dumps(signoz_storage_class()),
        quiet=True,
    )
    root_secret = {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": SIGNOZ_ROOT_SECRET, "namespace": SIGNOZ_NAMESPACE},
        "type": "Opaque",
        "stringData": {
            "password": signoz_root_password(run, outputs["app_secret_arns"]["signoz"])
        },
    }
    run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(root_secret), quiet=True)
    values = {
        "global": {"imageRegistry": registry},
        "clickhouse": {"password": clickhouse_password(run)},
        # 첫 관리자(=조직)를 설치 때 만든다. 비밀번호는 Secret 에서 읽어 파드 명세에 값이 남지 않는다.
        "signoz": {
            "env": {
                "signoz_user_root_enabled": "true",
                "signoz_user_root_email": SIGNOZ_ROOT_EMAIL,
                "signoz_user_root_org_name": "scenetrip",
                "signoz_user_root_password": {
                    "valueFrom": {
                        "secretKeyRef": {
                            "name": SIGNOZ_ROOT_SECRET,
                            "key": "password",
                        }
                    }
                },
            }
        },
    }
    run(
        [
            "helm",
            "upgrade",
            "--install",
            SIGNOZ_RELEASE,
            str(chart),
            "--namespace",
            SIGNOZ_NAMESPACE,
            "--values",
            str(root / "platform/helm/signoz/values.yaml"),
            "--values",
            str(root / f"platform/helm/signoz/values-{settings.environment}.yaml"),
            "--values",
            "-",
            "--atomic",
            "--wait",
            "--timeout",
            "15m",
            "--history-max",
            "5",
        ],
        stdin=json.dumps(values),
        quiet=True,
    )
    print(f"{settings.environment} SigNoz 배포 완료")


def signoz_ingress(settings, outputs):
    """SigNoz 화면 전용 ALB(ADR 0025) — Terraform 의 Cloudflare 전용 보안 그룹·인증서로. API ALB 와 같은 형식이다."""
    name = f"scenetrip-{settings.environment}-signoz-alb"
    certificate = outputs["signoz_certificate_arn"]
    group = matched(
        r"sg-(?:[a-f0-9]{8}|[a-f0-9]{17})",
        outputs["signoz_alb_security_group_id"],
        "SigNoz ALB 보안 그룹",
    )
    return [
        {
            "apiVersion": "eks.amazonaws.com/v1",
            "kind": "IngressClassParams",
            "metadata": {"name": name},
            "spec": {
                "scheme": "internet-facing",
                "ipAddressType": "ipv4",
                "namespaceSelector": {
                    "matchLabels": {"kubernetes.io/metadata.name": SIGNOZ_NAMESPACE}
                },
                "subnets": {"ids": list(alb_values(outputs)["albSubnetIds"])},
                "certificateARNs": [certificate],
            },
        },
        {
            "apiVersion": "networking.k8s.io/v1",
            "kind": "IngressClass",
            "metadata": {"name": name},
            "spec": {
                "controller": "eks.amazonaws.com/alb",
                "parameters": {
                    "apiGroup": "eks.amazonaws.com",
                    "kind": "IngressClassParams",
                    "name": name,
                },
            },
        },
        {
            "apiVersion": "networking.k8s.io/v1",
            "kind": "Ingress",
            "metadata": {
                "name": "signoz-ui",
                "namespace": SIGNOZ_NAMESPACE,
                "annotations": {
                    "alb.ingress.kubernetes.io/listen-ports": '[{"HTTPS":443}]',
                    "alb.ingress.kubernetes.io/certificate-arn": certificate,
                    "alb.ingress.kubernetes.io/ssl-policy": "ELBSecurityPolicy-TLS13-1-2-2021-06",
                    "alb.ingress.kubernetes.io/target-type": "ip",
                    "alb.ingress.kubernetes.io/backend-protocol": "HTTP",
                    "alb.ingress.kubernetes.io/security-groups": group,
                    # 443 의 Cloudflare 대역 규칙과 8080 backend 규칙은 Terraform(signoz.tf)이 관리한다.
                    "alb.ingress.kubernetes.io/manage-backend-security-group-rules": "false",
                    "alb.ingress.kubernetes.io/healthcheck-path": "/api/v1/health",
                    "alb.ingress.kubernetes.io/success-codes": "200",
                    "alb.ingress.kubernetes.io/load-balancer-attributes": "idle_timeout.timeout_seconds=120",
                    "alb.ingress.kubernetes.io/target-group-attributes": "deregistration_delay.timeout_seconds=30",
                },
            },
            "spec": {
                "ingressClassName": name,
                "rules": [
                    {
                        "host": SIGNOZ_DOMAINS[settings.environment],
                        "http": {
                            "paths": [
                                {
                                    "path": "/",
                                    "pathType": "Prefix",
                                    "backend": {
                                        "service": {
                                            "name": SIGNOZ_RELEASE,
                                            "port": {"number": 8080},
                                        }
                                    },
                                }
                            ]
                        },
                    }
                ],
            },
        },
    ]


def expose_signoz(run, settings, outputs):
    """SigNoz 화면을 Cloudflare 뒤로 연다 — 인증서가 주소를 덮는지 먼저 본다. 인증서가 없으면(Terraform 변수 비움) 열지 않는다."""
    certificate = outputs["signoz_certificate_arn"]
    if not certificate:
        print(f"{settings.environment} SigNoz 화면 인증서가 없어 화면을 열지 않습니다")
        return None
    details = json.loads(
        run(
            [
                "aws",
                "acm",
                "describe-certificate",
                "--certificate-arn",
                certificate,
                "--output",
                "json",
            ],
            quiet=True,
        )
    )["Certificate"]
    if details["Status"] != "ISSUED":
        raise ValueError("SigNoz ACM 인증서가 ISSUED 상태가 아닙니다")
    if SIGNOZ_DOMAINS[settings.environment] not in details["SubjectAlternativeNames"]:
        raise ValueError("SigNoz ACM 인증서가 화면 주소를 포함하지 않습니다")
    for manifest in signoz_ingress(settings, outputs):
        run(["kubectl", "apply", "-f", "-"], stdin=json.dumps(manifest), quiet=True)
    hostname = wait_for_signoz_alb(run, settings, outputs)
    print(f"SigNoz 화면 ALB: {hostname}")
    return hostname


# 받은 span 이 들어가는 표(SigNoz 0.145 의 trace 저장소). HTTPS 검증이 보낸 실제 요청이 여기 쌓여야 한다.
SIGNOZ_SPAN_QUERY = (
    "SELECT count() FROM signoz_traces.distributed_signoz_index_v3 "
    "WHERE timestamp > now() - INTERVAL 15 MINUTE"
)


def verify_signoz_traces(run, timeout=180, poll=10):
    """scene-api 의 trace 가 SigNoz 에 **실제로** 쌓였는지 확인한다 — SigNoz 가 떠 있어도 보내는 쪽이 꺼져 있으면 화면만 빈다.

    HTTPS 검증(verify_https)이 보낸 요청의 span 을 ClickHouse 에 직접 센다. ClickHouse 파드 안에서 기본 사용자로
    묻는다(그 파드의 localhost 만 허용된다) — 비밀번호를 명령줄에 싣지 않는다. 수집기가 묶어 보내므로 몇 초 늦게 보인다."""
    pod = run(
        [
            "kubectl",
            "-n",
            SIGNOZ_NAMESPACE,
            "get",
            "pods",
            "-l",
            "clickhouse.altinity.com/chi=signoz-clickhouse",
            "-o",
            "jsonpath={.items[0].metadata.name}",
        ],
        quiet=True,
    ).strip()
    matched(r"[a-z0-9-]{1,253}", pod, "SigNoz ClickHouse 파드 이름")
    deadline = time.monotonic() + timeout
    while True:
        counted = run(
            [
                "kubectl",
                "-n",
                SIGNOZ_NAMESPACE,
                "exec",
                pod,
                "-c",
                "clickhouse",
                "--",
                "clickhouse-client",
                "--query",
                SIGNOZ_SPAN_QUERY,
            ],
            quiet=True,
        ).strip()
        spans = int(matched(r"[0-9]+", counted, "SigNoz span 수"))
        if spans > 0:
            print(f"SigNoz 에 최근 15 분 span {spans} 개 확인")
            return spans
        if time.monotonic() > deadline:
            raise RuntimeError(
                "SigNoz 에 trace 가 쌓이지 않았습니다 — scene-api 의 OTEL 설정·수집기를 확인하세요"
            )
        time.sleep(poll)


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
