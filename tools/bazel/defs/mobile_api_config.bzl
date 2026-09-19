"""모바일 API 주소의 빌드 시점 검증과 설정 생성.

URL을 셸 명령에 보간하지 않고 actions.write로 소스에 넣는다. 잘못된 주소는
앱이 시작된 뒤 URL 파서에서 죽는 대신 빌드 분석 단계에서 거절한다.
"""

_HOST_CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-"
_DIGITS = "0123456789"

def _valid_host(host):
    labels = host.split(".")
    if len(host) > 253 or len(labels) < 2:
        return False
    for label in labels:
        if not label or len(label) > 63 or label.startswith("-") or label.endswith("-"):
            return False
        if not all([character in _HOST_CHARACTERS for character in label.elems()]):
            return False
    return not all([character in _DIGITS for character in labels[-1].elems()])

def validate_api_base_url(value):
    """빈 로컬 기본값 또는 HTTPS /v1 주소를 검증한다.

    Args:
      value: 빌드 설정에 지정한 주소. 빈 값은 로컬 기본값이다.

    Returns:
      유효하면 None, 아니면 사용자에게 보일 오류 메시지.
    """
    if not value:
        return None
    if not value.startswith("https://") or not value.endswith("/v1"):
        return "원격 API 주소는 https://도메인/v1 형식이어야 합니다"
    authority = value[len("https://"):-len("/v1")].split(":")
    if len(authority) > 2 or not _valid_host(authority[0]):
        return "API 주소에는 올바른 DNS 이름만 사용하세요 (경로·인증정보·쿼리 금지)"
    if len(authority) == 2:
        port = authority[1]
        if not port or not all([character in _DIGITS for character in port.elems()]):
            return "API 포트는 1~65535 숫자여야 합니다"
        if int(port) < 1 or int(port) > 65535:
            return "API 포트는 1~65535 숫자여야 합니다"
    return None

def render_api_configuration(language, configured):
    """검증을 통과한 주소에서 플랫폼별 소스를 만든다.

    Args:
      language: swift 또는 kotlin.
      configured: 검증을 통과한 API 주소 또는 빈 로컬 설정.

    Returns:
      해당 플랫폼의 API 설정 소스 문자열.
    """
    if language == "swift":
        address = configured or "http://localhost:8081/v1"
        return 'enum ApiConfiguration {\n    static let baseURL = "%s"\n}\n' % address
    address = configured or "http://10.0.2.2:8081/v1"
    return "package com.mz2az.scenetrip.data\n\n" + 'internal object ApiConfiguration {\n    const val BASE_URL = "%s"\n}\n' % address

def _mobile_api_config_impl(ctx):
    configured = ctx.var.get("scenetrip_api_base_url", "")
    error = validate_api_base_url(configured)
    if error:
        fail(error)
    ctx.actions.write(ctx.outputs.out, render_api_configuration(ctx.attr.language, configured))
    return [DefaultInfo(files = depset([ctx.outputs.out]))]

mobile_api_config = rule(
    implementation = _mobile_api_config_impl,
    attrs = {
        "language": attr.string(mandatory = True, values = ["swift", "kotlin"]),
        "out": attr.output(mandatory = True),
    },
)
