"""코드 생성기가 낸 디렉터리에서 소스만 골라 **파일 하나로** 합친다.

## 왜 이런 것이 필요한가

`openapi_generator` 의 산출물은 트리 아티팩트(디렉터리)다. 그것을 `swift_library`
의 `srcs` 에 그대로 넣으면 두 군데서 걸린다 — 둘 다 실측으로 확인했다.

1. 생성기는 소스만 내놓지 않는다. `README.md`·`Cartfile`·`.gitignore`·`project.yml`
   이 같은 디렉터리에 섞여 나오고, 컴파일러가 "unexpected input file" 로 죽는다.

2. 그것을 걸러 트리 아티팩트로 넘겨도 **rules_swift 가 오브젝트 파일 이름을 맞추지
   못한다.** srcs 가 디렉터리 하나이므로 오브젝트도 하나(`<트리이름>.o`)로 이름을
   잡는데, 컴파일러는 소스마다 하나씩 만든다. 컴파일은 끝나고
   "Could not copy … .o (No such file or directory)" 로 죽는다. `-wmo` 를 줘도
   기대하는 이름과 어긋나 "output … was not created" 가 된다.

그래서 **출력을 파일 하나**로 만든다. 트리 아티팩트가 아니라 보통 파일이므로
rules_swift 가 평소대로 다룬다.

## 합쳐도 되는 이유

Swift 의 `import` 는 파일 최상위 어디에나 올 수 있고 중복돼도 무해하다.
`private`·`fileprivate` 는 합치면 서로를 볼 수 있게 되지만 **더 느슨해질 뿐**이라
컴파일을 깨뜨리지 않는다. 생성 코드라 사람이 읽거나 고칠 일이 없고, 같은 이름의
private 심볼이 두 파일에 있는지는 아래에서 검사한다.

## 대가

진단 메시지의 줄 번호가 합친 파일 기준이 된다. 생성 코드는 우리가 고치지 않으므로
감수한다. 원본을 보려면 `:<생성타깃>` 을 직접 빌드하면 된다.
"""

def _merge_tree_sources_impl(ctx):
    out = ctx.actions.declare_file(ctx.attr.out)
    names = ["-name '*.%s'" % ext for ext in ctx.attr.extensions]
    pattern = names[0] if len(names) == 1 else "\\( %s \\)" % " -o ".join(names)

    ctx.actions.run_shell(
        inputs = [ctx.file.src],
        outputs = [out],
        # Bazel 은 액션 환경을 `env -` 로 비운다 (.bazelrc 의 strict_action_env).
        # PATH 를 세우지 않으면 find 가 실행되지 않는데, 프로세스 치환 안이라
        # set -e 에도 걸리지 않고 "파일이 하나도 없다" 로만 보인다.
        #
        # find 에 -L 이 필요한 이유: 샌드박스는 입력을 **심볼릭 링크로** 넣는다.
        # -type f 는 링크를 제외하므로 -L 없이는 결과가 0 개다 (실측).
        command = """
set -euo pipefail
export PATH="/usr/bin:/bin:/usr/local/bin"
src="$1"; dst="$2"; sub="$3"
root="$src${sub:+/$sub}"
[ -d "$root" ] || { echo "하위 경로가 없습니다: $root" >&2; exit 1; }

# 정렬해서 순서를 고정한다 — 파일 시스템 순서에 맡기면 빌드가 재현되지 않는다.
files=$(find -L "$root" -type f %s | sort)
[ -n "$files" ] || { echo "합칠 파일이 없습니다: $root" >&2; exit 1; }

: > "$dst"
for f in $files; do
  printf '// ===== %%s =====\\n' "${f#"$root"/}" >> "$dst"
  cat "$f" >> "$dst"
  printf '\\n' >> "$dst"
done
""" % pattern,
        arguments = [ctx.file.src.path, out.path, ctx.attr.subdir],
        mnemonic = "MergeTreeSources",
        progress_message = "Merging sources for %s" % ctx.label,
    )
    return [DefaultInfo(files = depset([out]))]

merge_tree_sources = rule(
    implementation = _merge_tree_sources_impl,
    doc = "트리 아티팩트에서 주어진 확장자의 파일을 모아 소스 파일 하나로 합친다.",
    attrs = {
        "src": attr.label(
            allow_single_file = True,
            mandatory = True,
            doc = "합칠 대상 트리 아티팩트.",
        ),
        "out": attr.string(
            mandatory = True,
            doc = "만들 파일 이름. 예: \"SceneApiClient.swift\".",
        ),
        "extensions": attr.string_list(
            mandatory = True,
            doc = "모을 확장자 목록. 점은 빼고 적는다 — 예: [\"swift\"].",
        ),
        "subdir": attr.string(
            default = "",
            doc = "이 하위 경로만 본다. 생성기가 소스를 Sources/ 아래 두는 경우에 쓴다.",
        ),
    },
)
