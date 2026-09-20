"""삭제 범위와 확인값을 AWS 인증 요청 전에 검증한다."""

import os

from tools.aws.config import matched

TEARDOWN_COMMANDS = {
    "service-destroy-plan": ("service", "plan"),
    "service-destroy": ("service", "destroy"),
    "bootstrap-delete-plan": ("bootstrap", "plan"),
    "bootstrap-delete": ("bootstrap", "destroy"),
}


def validate_teardown(settings, scope, operation, snapshot_policy, purge_state):
    matched(r"service|bootstrap", scope, "삭제 범위")
    matched(r"plan|destroy", operation, "삭제 작업")
    matched(r"retain|discard", snapshot_policy, "최종 DB 스냅샷 정책")
    matched(r"true|false", purge_state, "state 버킷 삭제 여부")
    if scope == "service" and purge_state != "false":
        raise ValueError(
            "state 버킷은 서비스 삭제 완료 후 bootstrap 단계에서 삭제합니다"
        )
    if scope == "bootstrap":
        if snapshot_policy != "retain":
            raise ValueError("DB 스냅샷 정책은 서비스 삭제에서만 변경합니다")
        role = matched(
            rf"arn:aws:iam::{settings.account}:role/[A-Za-z0-9+=,.@_/-]+",
            os.environ.get("AWS_BOOTSTRAP_ROLE_ARN", ""),
            "부트스트랩 역할",
        )
        if role.rsplit("/", 1)[-1] in {
            f"{settings.cluster}-deploy",
            f"{settings.cluster}-eks-cluster",
            f"{settings.cluster}-eks-node",
        }:
            raise ValueError("삭제할 스택 밖에 있는 bootstrap 역할이 필요합니다")
    if operation == "destroy":
        expected = f"DELETE {settings.environment} {settings.account}"
        if os.environ.get("AWS_DELETE_CONFIRMATION") != expected:
            raise ValueError(f"삭제 확인값은 정확히 '{expected}'여야 합니다")
