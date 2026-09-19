"""두 모바일 앱이 공유하는 원격 API 주소 검증."""

load("@bazel_skylib//lib:unittest.bzl", "asserts", "unittest")
load(":mobile_api_config.bzl", "render_api_configuration", "validate_api_base_url")

def _mobile_api_config_test_impl(ctx):
    env = unittest.begin(ctx)
    for value in ["", "https://api.example.com/v1", "https://dev-api.example.com:8443/v1"]:
        asserts.equals(env, None, validate_api_base_url(value), value)
    for value in [
        "http://api.example.com/v1",
        "http://localhost:8081/v1",
        "https://api.example.com",
        "https://api.example.com/v1/",
        "https:///v1",
        "https://api.example.com/v1?key=value",
        "https://api.example.com/v1#fragment",
        "https://user:password@api.example.com/v1",
        "https://api.example.com:invalid/v1",
        "https://api.example.com:70000/v1",
        "https://api.example.com:0/v1",
        "https://api.example.com:/v1",
        "https://localhost/v1",
        "https://127.0.0.1/v1",
        "https://999.999.999.999/v1",
        "https://" + "a" * 64 + ".example.com/v1",
        "https://" + "a." * 126 + "example.com/v1",
        "https://api.example.com:443:443/v1",
        "https://api..example.com/v1",
        "https://-api.example.com/v1",
        "https://api.example.com/../v1",
        "https://api.example.com\\evil/v1",
        "https://api.example.com/\";bad/v1",
        "https://api.example.com/$(bad)/v1",
        " https://api.example.com/v1",
    ]:
        asserts.true(env, validate_api_base_url(value) != None, value)
    asserts.true(env, 'baseURL = "http://localhost:8081/v1"' in render_api_configuration("swift", ""))
    asserts.true(env, 'BASE_URL = "http://10.0.2.2:8081/v1"' in render_api_configuration("kotlin", ""))
    for language in ["swift", "kotlin"]:
        source = render_api_configuration(language, "https://api.example.com/v1")
        asserts.true(env, "https://api.example.com/v1" in source)
        asserts.false(env, "http://" in source)
    return unittest.end(env)

mobile_api_config_test = unittest.make(_mobile_api_config_test_impl)
