"""출시 전 운영 보호 — 스냅샷 정리, 끊긴 배포 점검, 공용 장면 사진 버킷 (MZ2AZ-364).

계획: docs/project/plans/ops-protection.md. 배포·삭제 흐름(aws.py)과 달리 커밋·Environment 입력을 요구하지 않는다 — 노트북에서
관리자 자격으로, 또는 삭제 흐름이 끝난 뒤 그 자격 그대로 부른다. 지우거나 바꾸는 것은 `--execute` 를 줄 때만 한다.
"""

import argparse
import json
import platform
import re
import shutil
import subprocess
import sys
from pathlib import Path

from tools.aws.config import Runner, workspace

REGION = "ap-northeast-2"
SHARED_MEDIA_STACK = "scenetrip-shared-media"
SHARED_MEDIA_BUCKET = "scenetrip-media-prod"
SHARED_MEDIA_TEMPLATE = "platform/terraform/shared-media/template.json"


def aws(run, service, operation, *arguments, region=REGION):
    out = run(
        [
            "aws",
            service,
            operation,
            *arguments,
            "--region",
            region,
            "--output",
            "json",
            "--cli-connect-timeout",
            "5",
            "--cli-read-timeout",
            "30",
        ],
        quiet=True,
    )
    return json.loads(out) if out and out.strip() else {}


# ─────────────────────────── 2. 최종 스냅샷 정리 ───────────────────────────


def final_snapshots(run, environment):
    """이 환경의 최종 스냅샷(`scenetrip-{env}-final-*`), 최근 것부터."""
    prefix = f"scenetrip-{environment}-final-"
    listed = aws(run, "rds", "describe-db-snapshots", "--snapshot-type", "manual")
    snapshots = listed.get("DBSnapshots")
    if not isinstance(snapshots, list):
        raise TypeError("RDS 스냅샷 목록 형식이 올바르지 않습니다")
    mine = [
        s
        for s in snapshots
        if str(s.get("DBSnapshotIdentifier", "")).startswith(prefix)
        and s.get("Status") == "available"
    ]
    return sorted(
        mine, key=lambda s: str(s.get("SnapshotCreateTime", "")), reverse=True
    )


def prune_snapshots(run, environment, keep, *, execute):
    """최근 `keep` 개만 남기고 지운다. **dev 만** — prd 스냅샷은 이 코드가 지우지 않는다.

    올리기가 스냅샷을 복원하지 않으므로(aws-teardown.md) dev 의 최종 스냅샷은 사람이 꺼내 쓰는 백업일 뿐이다. 하나가 깨져도 하나가 남게 기본 2 개.
    """
    if environment != "dev":
        raise ValueError(
            "최종 스냅샷 정리는 dev 만 합니다 — prd 스냅샷은 사람이 정한다"
        )
    if keep < 1:
        raise ValueError("적어도 하나는 남깁니다(--keep 1 이상)")
    snapshots = final_snapshots(run, environment)
    kept, doomed = snapshots[:keep], snapshots[keep:]
    for s in kept:
        print(f"남김  {s['DBSnapshotIdentifier']}  {s.get('SnapshotCreateTime', '')}")
    for s in doomed:
        verb = "지움" if execute else "지울 것"
        print(f"{verb}  {s['DBSnapshotIdentifier']}  {s.get('SnapshotCreateTime', '')}")
        if execute:
            aws(
                run,
                "rds",
                "delete-db-snapshot",
                "--db-snapshot-identifier",
                s["DBSnapshotIdentifier"],
            )
    if not doomed:
        print(f"정리할 스냅샷이 없습니다 — {len(kept)} 개, 남길 수 {keep}")
    elif not execute:
        print("미리 보기 — 지우려면 --execute")
    return [s["DBSnapshotIdentifier"] for s in doomed]


# ─────────────────────────── 5. 끊긴 배포 점검 ───────────────────────────


def state_bucket(run, environment):
    account = aws(run, "sts", "get-caller-identity")["Account"]
    return f"scenetrip-tfstate-{account}-{REGION}-{environment}"


def state_types(run, bucket, environment):
    """Terraform state 에 있는 관리 자원의 (type, name) 집합. state 가 없으면 빈 집합."""
    key = f"scenetrip/{environment}/terraform.tfstate"
    try:
        raw = run(
            ["aws", "s3", "cp", f"s3://{bucket}/{key}", "-", "--region", REGION],
            quiet=True,
        )
    except Exception:  # noqa: BLE001 — 없는 state 는 빈 state 다
        return set()
    state = json.loads(raw) if raw and raw.strip() else {}
    return {
        (r.get("type"), r.get("name"))
        for r in state.get("resources", [])
        if r.get("mode") == "managed"
    }


def lock_exists(run, bucket, environment):
    key = f"scenetrip/{environment}/terraform.tfstate.tflock"
    listed = aws(run, "s3api", "list-objects-v2", "--bucket", bucket, "--prefix", key)
    return any(item.get("Key") == key for item in listed.get("Contents", []) or [])


def drift(run, environment):
    """잠금 파일이 남았는가, 상태 밖에 남은 큰 자원(EKS·RDS·NAT·VPC)이 있는가. 읽기만 한다.

    2026-10-09 dev 올리기가 apply 도중 끊겨 잠금 파일·EKS·RDS 가 상태 밖에 남았다 — 그때 손으로 찾은 것을 명령 하나로 만든 것이다.
    무엇을 지울지는 사람이 런북(docs/ops/aws-deployment.md 「끊긴 배포 정리」)을 보고 정한다.
    """
    name = f"scenetrip-{environment}"
    bucket = state_bucket(run, environment)
    types = state_types(run, bucket, environment)
    findings = []

    if lock_exists(run, bucket, environment):
        findings.append(
            "잠금 파일이 남아 있다 — 지금 도는 배포·삭제가 없는지 먼저 본다"
        )

    clusters = aws(run, "eks", "list-clusters").get("clusters", [])
    if name in clusters and ("aws_eks_cluster", "this") not in types:
        findings.append(f"EKS {name} 이 state 밖에 있다")

    dbs = aws(run, "rds", "describe-db-instances").get("DBInstances", [])
    if (
        any(d.get("DBInstanceIdentifier") == name for d in dbs)
        and (
            "aws_db_instance",
            "postgres",
        )
        not in types
    ):
        findings.append(f"RDS {name} 이 state 밖에 있다")

    # describe-nat-gateways 는 --filter, describe-vpcs 는 --filters 다(같은 뜻, 다른 이름).
    tags = [
        "Name=tag:Project,Values=scenetrip",
        f"Name=tag:Environment,Values={environment}",
    ]
    nats = [
        n
        for n in aws(run, "ec2", "describe-nat-gateways", "--filter", *tags).get(
            "NatGateways", []
        )
        if n.get("State") in {"pending", "available"}
    ]
    if nats and not any(t == "aws_nat_gateway" for t, _ in types):
        findings.append(f"NAT 게이트웨이 {len(nats)} 개가 state 밖에 있다(시간당 요금)")

    vpcs = aws(run, "ec2", "describe-vpcs", "--filters", *tags).get("Vpcs", [])
    if vpcs and ("aws_vpc", "this") not in types:
        findings.append(f"VPC {len(vpcs)} 개가 state 밖에 있다")

    print(f"state: s3://{bucket} — 관리 자원 {len(types)} 개")
    if not findings:
        print("어긋난 것 없음")
        return []
    for f in findings:
        print(f"! {f}")
    print("정리: docs/ops/aws-deployment.md 「끊긴 배포 정리」")
    return findings


# ─────────────────────────── 3. 공용 장면 사진 버킷 ───────────────────────────


# 가져올 자원 — 버킷과 **이미 걸려 있는 버킷 정책**. 정책을 새로 만들려 하면 「이미 있다」 로 막힌다(2026-10-10 첫 적용이 그렇게
# 되돌려졌다). 정책도 지금 내용 그대로 가져온다.
IMPORTABLE = {
    "SharedMediaBucket": ("AWS::S3::Bucket", {"BucketName": SHARED_MEDIA_BUCKET}),
    "SharedMediaBucketPolicy": (
        "AWS::S3::BucketPolicy",
        {"Bucket": SHARED_MEDIA_BUCKET},
    ),
}


def import_template(desired):
    """가져오기용 템플릿 — 버킷·정책이 **지금 가진 설정 그대로**. 가져오기는 속성을 적용하지 않으므로, 바라는 설정(버전 관리)을 처음부터
    넣으면 가져온 뒤의 갱신이 「바뀐 것 없음」 으로 보고 영영 켜지 않는다. 그래서 둘로 나눈다."""
    template = json.loads(json.dumps(desired))
    resources = template["Resources"]
    properties = resources["SharedMediaBucket"]["Properties"]
    properties.pop("VersioningConfiguration", None)
    properties["LifecycleConfiguration"]["Rules"] = [
        r
        for r in properties["LifecycleConfiguration"]["Rules"]
        if r.get("Id") != "ExpireNoncurrentVersions"
    ]
    template.pop("Outputs", None)
    return template


def stack_status(run):
    try:
        stacks = aws(
            run, "cloudformation", "describe-stacks", "--stack-name", SHARED_MEDIA_STACK
        )
    except Exception:  # noqa: BLE001 — 없는 스택
        return None
    found = stacks.get("Stacks") or []
    return found[0].get("StackStatus") if found else None


def stack_resources(run):
    """스택에 이미 든 논리 이름들. 스택이 없으면 빈 집합."""
    try:
        listed = aws(
            run,
            "cloudformation",
            "describe-stack-resources",
            "--stack-name",
            SHARED_MEDIA_STACK,
        )
    except Exception:  # noqa: BLE001 — 없는 스택
        return set()
    return {r.get("LogicalResourceId") for r in listed.get("StackResources", [])}


def change_set(run, temp, template, change_type, *, execute, to_import=()):
    body = temp / f"shared-media-{change_type.lower()}.json"
    body.write_text(json.dumps(template, ensure_ascii=False))
    name = f"{SHARED_MEDIA_STACK}-{change_type.lower()}"
    args = [
        "--stack-name",
        SHARED_MEDIA_STACK,
        "--change-set-name",
        name,
        "--change-set-type",
        change_type,
        "--template-body",
        f"file://{body}",
    ]
    if change_type == "IMPORT":
        args += [
            "--resources-to-import",
            json.dumps(
                [
                    {
                        "ResourceType": IMPORTABLE[logical][0],
                        "LogicalResourceId": logical,
                        "ResourceIdentifier": IMPORTABLE[logical][1],
                    }
                    for logical in to_import
                ]
            ),
        ]
    aws(run, "cloudformation", "create-change-set", *args)
    run(
        [
            "aws",
            "cloudformation",
            "wait",
            "change-set-create-complete",
            "--stack-name",
            SHARED_MEDIA_STACK,
            "--change-set-name",
            name,
            "--region",
            REGION,
        ],
        quiet=True,
    )
    described = aws(
        run,
        "cloudformation",
        "describe-change-set",
        "--stack-name",
        SHARED_MEDIA_STACK,
        "--change-set-name",
        name,
    )
    for change in described.get("Changes", []):
        rc = change.get("ResourceChange", {})
        print(
            f"{rc.get('Action')}  {rc.get('LogicalResourceId')}  {rc.get('ResourceType')}  {rc.get('Replacement', '')}"
        )
    if any(
        c.get("ResourceChange", {}).get("Replacement") == "True"
        for c in described.get("Changes", [])
    ):
        aws(
            run,
            "cloudformation",
            "delete-change-set",
            "--stack-name",
            SHARED_MEDIA_STACK,
            "--change-set-name",
            name,
        )
        raise RuntimeError("버킷을 새로 만드는(교체) 변경이다 — 멈춘다")
    if not execute:
        aws(
            run,
            "cloudformation",
            "delete-change-set",
            "--stack-name",
            SHARED_MEDIA_STACK,
            "--change-set-name",
            name,
        )
        print("미리 보기 — 적용하려면 --execute")
        return
    aws(
        run,
        "cloudformation",
        "execute-change-set",
        "--stack-name",
        SHARED_MEDIA_STACK,
        "--change-set-name",
        name,
    )
    waiter = (
        "stack-import-complete" if change_type == "IMPORT" else "stack-update-complete"
    )
    run(
        [
            "aws",
            "cloudformation",
            "wait",
            waiter,
            "--stack-name",
            SHARED_MEDIA_STACK,
            "--region",
            REGION,
        ],
        quiet=True,
    )
    print(f"{SHARED_MEDIA_STACK}: {stack_status(run)}")


def shared_media(run, root, temp, *, execute):
    """버킷·정책을 스택으로 가져오고(빠진 것만), 바라는 설정(버전 관리 등)을 적용한다. 버킷을 지우거나 새로 만들지 않는다."""
    desired = json.loads((root / SHARED_MEDIA_TEMPLATE).read_text())
    status = stack_status(run)
    if status is not None and not re.fullmatch(
        r"(IMPORT|UPDATE|CREATE)_COMPLETE|(IMPORT|UPDATE)_ROLLBACK_COMPLETE", status
    ):
        raise RuntimeError(
            f"{SHARED_MEDIA_STACK} 가 {status} 상태다 — 콘솔에서 먼저 본다"
        )
    present = stack_resources(run) if status is not None else set()
    missing = [logical for logical in IMPORTABLE if logical not in present]
    if missing:
        print(
            f"1/2 가져오기 — {', '.join(missing)} 을 {SHARED_MEDIA_STACK} 로(지금 설정 그대로)"
        )
        if not execute:
            # 가져오기 변경 세트는 만들기만 해도 빈 스택(REVIEW_IN_PROGRESS)을 남긴다 — 미리 보기에서는 가져오기 변경 세트를 만들지 않는다(조회만).
            # 갱신 미리 보기는 다르다: 바뀔 내용을 보이려고 변경 세트를 만들었다가 지운다(남는 것 없음).
            imported = import_template(desired)["Resources"]
            print(f"가져올 설정: {json.dumps(imported, ensure_ascii=False)}")
            print("2/2 그 뒤 적용: 버전 관리 켬, 지운 판 30 일 뒤 영구 삭제")
            print("미리 보기 — 적용하려면 --execute")
            return
        change_set(
            run,
            temp,
            import_template(desired),
            "IMPORT",
            execute=True,
            to_import=missing,
        )
    print("2/2 설정 적용 — 버전 관리·지운 판 30 일")
    change_set(run, temp, desired, "UPDATE", execute=execute)


# ─────────────────────────── 진입점 ───────────────────────────


class OperatorRunner:
    """노트북의 운영자가 부를 때의 실행기 — 호스트의 `aws` 를 쓴다.

    Bazel 이 고정한 AWS CLI 는 리눅스 amd64 러너 전용이다(배포·삭제는 러너에서 돈다). 이 명령들은 관리자가 자기 노트북에서 자기 자격으로
    부르는 운영 도구라 호스트 CLI 를 쓴다 — 러너에서 부르면(리눅스) Bazel 의 것을 쓴다. 실패하면 stderr 를 그대로 보인다: 운영자가 자기
    자격 문제를 바로 봐야 하고, 이 명령들은 비밀값을 다루지 않는다.
    """

    def __init__(self, root):
        self.root = root
        self.aws = shutil.which("aws")
        if not self.aws:
            raise RuntimeError("호스트에 aws CLI 가 없습니다 — brew install awscli")

    def __call__(self, command, *, quiet=False, cwd=None, **_):
        if command[0] == "aws":
            command = [self.aws, *command[1:]]
        process = subprocess.run(
            command, text=True, capture_output=True, cwd=cwd or self.root, check=False
        )
        if process.returncode:
            raise RuntimeError(
                f"{' '.join(command[1:3])} 실패: {process.stderr.strip()[:500]}"
            )
        if not quiet and process.stdout:
            print(process.stdout, end="")
        return process.stdout


def runner(root):
    linux_amd64 = sys.platform.startswith("linux") and platform.machine() in {
        "x86_64",
        "AMD64",
    }
    return Runner(root) if linux_amd64 else OperatorRunner(root)


def main(argv=None):
    import tempfile

    parser = argparse.ArgumentParser(description="SceneTrip 운영 보호 (MZ2AZ-364)")
    sub = parser.add_subparsers(dest="command", required=True)
    prune = sub.add_parser(
        "snapshot-prune", help="dev 최종 스냅샷을 최근 N 개만 남기고 정리"
    )
    prune.add_argument("environment", choices=["dev", "prd"])
    prune.add_argument("--keep", type=int, default=2)
    prune.add_argument("--execute", action="store_true")
    check = sub.add_parser("drift", help="잠금 파일·state 밖 자원 점검(읽기만)")
    check.add_argument("environment", choices=["dev", "prd"])
    media = sub.add_parser(
        "shared-media", help="장면 사진 버킷을 CloudFormation 으로 관리"
    )
    media.add_argument("--execute", action="store_true")
    args = parser.parse_args(argv)

    root = workspace()
    run = runner(root)
    if args.command == "snapshot-prune":
        prune_snapshots(run, args.environment, args.keep, execute=args.execute)
    elif args.command == "drift":
        if drift(run, args.environment):
            sys.exit(1)
    else:
        with tempfile.TemporaryDirectory(prefix="scenetrip-ops-") as temporary:
            shared_media(run, root, Path(temporary), execute=args.execute)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, TypeError) as error:
        print(f"중단: {error}", file=sys.stderr)
        sys.exit(1)
