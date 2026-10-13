"""바깥 감시 알람(ADR 0025, docs/project/plans/observability-aws.md §6-3) — 계정 공용 CloudFormation 스택 `scenetrip-alerts`.

PRD API 를 밖에서 부르는 Route 53 헬스 체크 + 그 실패 알람 + 계정 월 예산. 배포 역할을 건드리지 않는다 — 운영자가 자기
관리자 자격으로 `just aws-alerts --email <메일>`(미리 보기) · `--execute` 로 건다. 받는 메일은 저장소에 적지 않는다.
"""

import json

ALERTS_STACK = "scenetrip-alerts"
# Route 53 헬스 체크 지표는 us-east-1 에만 있다 — 알람·알림 주제도 같은 리전에 둔다.
ALERTS_REGION = "us-east-1"
ALERTS_TEMPLATE = "platform/terraform/alerts/template.json"
CHANGE_SET = f"{ALERTS_STACK}-change"


def cloudformation(run, operation, *arguments):
    out = run(
        [
            "aws",
            "cloudformation",
            operation,
            *arguments,
            "--region",
            ALERTS_REGION,
            "--output",
            "json",
        ],
        quiet=True,
    )
    return json.loads(out) if out and out.strip() else {}


def stack_status(run):
    """스택 상태. 없으면 None."""
    try:
        stacks = cloudformation(run, "describe-stacks", "--stack-name", ALERTS_STACK)
    except RuntimeError:
        return None
    listed = stacks.get("Stacks", [])
    return listed[0].get("StackStatus") if listed else None


def failed_events(run):
    """실패한 자원과 이유 — 회사 조직 정책(SCP)에 막혔는지 보려면 이것이 필요하다."""
    events = cloudformation(
        run, "describe-stack-events", "--stack-name", ALERTS_STACK
    ).get("StackEvents", [])
    return [
        (event.get("LogicalResourceId"), event.get("ResourceStatusReason", ""))
        for event in events
        if str(event.get("ResourceStatus", "")).endswith("FAILED")
    ]


def alerts(run, root, temp, *, email, execute):
    template = json.loads((root / ALERTS_TEMPLATE).read_text())
    status = stack_status(run)
    if status is not None and status not in {
        "CREATE_COMPLETE",
        "UPDATE_COMPLETE",
        "UPDATE_ROLLBACK_COMPLETE",
    }:
        raise RuntimeError(
            f"{ALERTS_STACK} 가 {status} 상태다 — 콘솔에서 먼저 본다(실패한 첫 생성이면 지우고 다시)"
        )
    change_type = "CREATE" if status is None else "UPDATE"
    if not execute and change_type == "CREATE":
        # 만들기 변경 세트는 만들기만 해도 빈 스택(REVIEW_IN_PROGRESS)을 남긴다 — 미리 보기는 템플릿만 보인다.
        for logical, resource in template["Resources"].items():
            print(f"Add  {logical}  {resource['Type']}")
        print("미리 보기 — 적용하려면 --execute")
        return
    body = temp / "alerts.json"
    body.write_text(json.dumps(template, ensure_ascii=False))
    cloudformation(
        run,
        "create-change-set",
        "--stack-name",
        ALERTS_STACK,
        "--change-set-name",
        CHANGE_SET,
        "--change-set-type",
        change_type,
        "--template-body",
        f"file://{body}",
        "--parameters",
        json.dumps([{"ParameterKey": "AlertEmail", "ParameterValue": email}]),
    )
    run(
        [
            "aws",
            "cloudformation",
            "wait",
            "change-set-create-complete",
            "--stack-name",
            ALERTS_STACK,
            "--change-set-name",
            CHANGE_SET,
            "--region",
            ALERTS_REGION,
        ],
        quiet=True,
    )
    described = cloudformation(
        run,
        "describe-change-set",
        "--stack-name",
        ALERTS_STACK,
        "--change-set-name",
        CHANGE_SET,
    )
    for change in described.get("Changes", []):
        resource = change.get("ResourceChange", {})
        print(
            f"{resource.get('Action')}  {resource.get('LogicalResourceId')}  {resource.get('ResourceType')}"
        )
    if not execute:
        cloudformation(
            run,
            "delete-change-set",
            "--stack-name",
            ALERTS_STACK,
            "--change-set-name",
            CHANGE_SET,
        )
        print("미리 보기 — 적용하려면 --execute")
        return
    cloudformation(
        run,
        "execute-change-set",
        "--stack-name",
        ALERTS_STACK,
        "--change-set-name",
        CHANGE_SET,
    )
    waiter = (
        "stack-create-complete" if change_type == "CREATE" else "stack-update-complete"
    )
    try:
        run(
            [
                "aws",
                "cloudformation",
                "wait",
                waiter,
                "--stack-name",
                ALERTS_STACK,
                "--region",
                ALERTS_REGION,
            ],
            quiet=True,
        )
    except RuntimeError:
        for logical, reason in failed_events(run):
            print(f"실패: {logical} — {reason}")
        raise
    print(f"{ALERTS_STACK}: {stack_status(run)}")
    if change_type == "CREATE":
        print(
            "받는 메일함의 AWS 알림 구독 확인 메일에서 「Confirm subscription」 을 눌러야 알람이 온다"
        )
