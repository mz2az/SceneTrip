"""코드 생성기가 낸 디렉터리의 소스를 **srcjar 하나**로 묶는다.

## 왜 이런 것이 필요한가

`openapi_generator` 의 산출물은 트리 아티팩트(디렉터리)다. Bazel 은 분석 시점에 그
안을 열거하지 못하는데 `kt_android_library` 의 `srcs` 는 개별 파일을 요구한다.

Swift 쪽은 `merge_tree_sources` 로 **파일 하나에 합쳐서** 피했다. 코틀린에는 그
방법이 통하지 않는다 — 파일마다 `package` 선언이 있고 한 파일에 `package` 는 하나만
올 수 있다. 합치면 컴파일이 깨진다.

대신 **srcjar** 로 감싼다. 코틀린 컴파일러는 소스가 든 zip 을 srcs 로 받아 안에서
꺼내 쓴다 (`KotlinCompile … { kt: N, java: N, srcjars: N }` 의 세 번째 칸이 그것이다).
파일이 파일로 남으므로 package 선언도 그대로다.

## 왜 zip 이 아니라 zipper 인가

`zip` 명령은 호스트에 있을 수도 없을 수도 있다. `@bazel_tools//tools/zip:zipper` 는
Bazel 이 함께 들고 오므로 기계마다 갈리지 않는다 — 저장소의 hermeticity 규칙
(AGENTS.md §4) 이 요구하는 바다.
"""

def _tree_srcjar_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.out)
    names = ["-name '*.%s'" % ext for ext in ctx.attr.extensions]
    pattern = names[0] if len(names) == 1 else "\\( %s \\)" % " -o ".join(names)
    args = ctx.actions.declare_file(ctx.attr.out + ".entries")

    ctx.actions.run_shell(
        inputs = [ctx.file.src],
        outputs = [out, args],
        tools = [ctx.executable._zipper],
        # Bazel 은 액션 환경을 `env -` 로 비운다 (.bazelrc 의 strict_action_env).
        # PATH 를 세우지 않으면 find 가 실행되지 않는다.
        #
        # find 에 -L 이 필요한 이유: 샌드박스는 입력을 **심볼릭 링크로** 넣는다.
        # -type f 는 링크를 제외하므로 -L 없이는 결과가 0 개다.
        command = """
set -euo pipefail
export PATH="/usr/bin:/bin:/usr/local/bin"
src="$1"; dst="$2"; entries="$3"; zipper="$4"; sub="$5"
root="$src${sub:+/$sub}"
[ -d "$root" ] || { echo "하위 경로가 없습니다: $root" >&2; exit 1; }

# 정렬해서 순서를 고정한다 — 파일 시스템 순서에 맡기면 빌드가 재현되지 않는다.
files=$(find -L "$root" -type f %s | sort)
[ -n "$files" ] || { echo "묶을 파일이 없습니다: $root" >&2; exit 1; }

# zipper 의 항목 형식은 `zip안경로=실제경로` 다. zip 안 경로는 패키지 구조를
# 그대로 따라가야 컴파일러가 package 선언과 대조할 수 있다.
: > "$entries"
for f in $files; do
  printf '%%s=%%s\\n' "${f#"$root"/}" "$f" >> "$entries"
done

"$zipper" c "$dst" "@$entries"
""" % pattern,
        arguments = [
            ctx.file.src.path,
            out.path,
            args.path,
            ctx.executable._zipper.path,
            ctx.attr.subdir,
        ],
        mnemonic = "TreeSrcJar",
        progress_message = "%s 의 소스를 srcjar 로 묶는 중" % ctx.label,
    )
    return [DefaultInfo(files = depset([out]))]

tree_srcjar = rule(
    implementation = _tree_srcjar_impl,
    doc = "트리 아티팩트 안의 소스를 srcjar 하나로 묶는다.",
    attrs = {
        "src": attr.label(
            allow_single_file = True,
            mandatory = True,
            doc = "코드 생성기 타깃 (트리 아티팩트를 내놓는 것)",
        ),
        "out": attr.string(
            mandatory = True,
            doc = "만들 파일 이름. **`.srcjar` 로 끝나야 한다** — 컴파일러가 확장자로 구분한다.",
        ),
        "extensions": attr.string_list(
            default = ["kt"],
            doc = "묶을 확장자 (점 없이)",
        ),
        "subdir": attr.string(
            default = "",
            doc = "트리 안에서 시작할 하위 경로. 비우면 트리 전체.",
        ),
        "_zipper": attr.label(
            default = Label("@bazel_tools//tools/zip:zipper"),
            executable = True,
            cfg = "exec",
        ),
    },
)
