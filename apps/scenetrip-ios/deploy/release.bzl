"""시뮬레이터와 분리한 배포 버전 입력(MZ2AZ-440)."""

def _release_info_impl(ctx):
    if ctx.var.get("scenetrip_api_base_url", "") != "https://api.scenetrip.io/v1":
        fail("배포 IPA는 운영 API 주소를 명시해야 합니다. just ios-package 사용")
    version = ctx.var.get("scenetrip_release_version", "")
    build = ctx.var.get("scenetrip_release_build", "")
    parts = version.split(".")
    if len(parts) != 3 or not all([p and p.isdigit() for p in parts]):
        fail("scenetrip_release_version은 숫자 세 부분이어야 합니다. just ios-package 사용")
    if not build.isdigit() or int(build) < 1:
        fail("scenetrip_release_build는 양의 정수여야 합니다. just ios-package 사용")
    out = ctx.actions.declare_file(ctx.label.name + ".plist")
    ctx.actions.expand_template(
        template = ctx.file.template,
        output = out,
        substitutions = {
            "<string>0.1.0</string>": "<string>" + version + "</string>",
            "<string>1</string>": "<string>" + build + "</string>",
        },
    )
    return [DefaultInfo(files = depset([out]))]

release_info = rule(
    implementation = _release_info_impl,
    attrs = {"template": attr.label(allow_single_file = True, mandatory = True)},
)
