"""비밀값·실계정 없이 사용하는 입력 픽스처."""

from tools.aws.config import Settings


def valid_settings(**changes):
    values = {
        "environment": "dev",
        "account": "123456789012",
        "region": "ap-northeast-2",
        "role": "arn:aws:iam::123456789012:role/scenetrip-dev-deploy",
        "sha": "a" * 40,
        "domain": "api.dev.example.com",
        "run_id": "123",
        "attempt": "1",
    }
    return Settings(**{**values, **changes})


# SigNoz(ADR 0025) — dev 배포는 차트 파일을 받아야 하고, Terraform 출력에 복사 저장소 주소가 있어야 한다.
SIGNOZ_CHART = "signoz-fixture.tgz"
SIGNOZ_OTLP = "http://signoz-otel-collector.signoz.svc.cluster.local:4317"


def ecr_registry(settings):
    return f"{settings.account}.dkr.ecr.{settings.region}.amazonaws.com"


def mirror_urls(settings):
    """Terraform `ecr_repository_urls` 의 SigNoz 복사 저장소 주소(선택한 계정·환경)."""
    from tools.aws.config import SIGNOZ_MIRROR

    return {
        key: f"{ecr_registry(settings)}/scenetrip-{settings.environment}/mirror/{path}"
        for key, (path, _) in SIGNOZ_MIRROR.items()
    }


def is_scenetrip_helm(command):
    return command[:4] == ["helm", "upgrade", "--install", "scenetrip"]


def is_signoz_helm(command):
    return command[:4] == ["helm", "upgrade", "--install", "signoz"]
