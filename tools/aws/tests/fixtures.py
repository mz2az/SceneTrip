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
