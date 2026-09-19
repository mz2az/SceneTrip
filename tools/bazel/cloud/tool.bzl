"""고정된 배포 CLI를 Bazel runfiles 안에서 실행한다."""

def _cloud_tool_impl(ctx):
    binary = ctx.file.binary
    path = binary.short_path
    if path.startswith("../"):
        path = path[3:]
    else:
        path = ctx.workspace_name + "/" + path
    script = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(
        script,
        """#!/usr/bin/env bash
set -euo pipefail
runfiles="${RUNFILES_DIR:-${BASH_SOURCE[0]}.runfiles}"
if [[ ! -d "$runfiles" ]]; then
  echo 'Bazel runfiles를 찾을 수 없습니다. just run으로 실행하세요.' >&2
  exit 1
fi
export AWS_PAGER=""
export CHECKPOINT_DISABLE=1
exec "$runfiles/%s" "$@"
""" % path,
        is_executable = True,
    )
    return [DefaultInfo(
        executable = script,
        runfiles = ctx.runfiles(files = [binary] + ctx.files.runtime),
    )]

cloud_tool = rule(
    implementation = _cloud_tool_impl,
    attrs = {
        "binary": attr.label(allow_single_file = True, mandatory = True),
        "runtime": attr.label_list(allow_files = True),
    },
    executable = True,
)

def _unsupported_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name)
    ctx.actions.write(
        output,
        "#!/usr/bin/env bash\necho 'AWS CLI 배포는 Linux amd64 러너에서 실행합니다.' >&2\nexit 1\n",
        is_executable = True,
    )
    return [DefaultInfo(executable = output)]

unsupported_tool = rule(implementation = _unsupported_impl, executable = True)
