# BUILD.bazel 초안 — `rules_python` 이 켜지면 붙일 것

> 2026-09-02. **아직 저장소에 넣지 않았다.** 지금 `agents/trip-guide/BUILD.bazel` 을
> 만들면 `py_library` 를 아는 규칙이 없어 `bazel build //...` 가 **팀 전체에서**
> 깨진다. 그래서 초안으로만 둔다.

## 먼저 필요한 것 — 내 폴더 밖의 변경

`MODULE.bazel` 의 아래 블록에서 주석을 푼다. 그 파일에는 *"해당 언어의 첫 모듈이
들어올 때 주석을 푼다"* 라고 적혀 있고, **`agents/trip-guide` 가 그 첫 모듈이다.**

```python
bazel_dep(name="rules_python", version="2.2.0")
pip = use_extension("@rules_python//python/extensions:pip.bzl", "pip")
pip.parse(
    hub_name="pypi", python_version="3.12", requirements_lock="//:requirements.lock"
)
use_repo(pip, "pypi")
```

**이것은 의존성 추가라 팀 결정이 먼저다**(CLAUDE.md §9). 그리고 `MODULE.bazel` 은
`agents/` 밖이라 내 담당 범위가 아니다. 두 가지 모두 승길님·권호님과 합의가 필요하다.

곁들여 확인할 것 —

- `pip.parse` 가 `//:requirements.lock` 을 찾는다. **이 모듈은 외부 패키지를 하나도
  쓰지 않는다**(표준 라이브러리만). 그래서 빈 잠금 파일이면 충분하다.
- 켠 뒤 `just deps-update` 로 `MODULE.bazel.lock` 을 갱신하고 그 diff 를 함께 커밋한다.

## 초안

```python
load("@rules_python//python:defs.bzl", "py_binary", "py_library", "py_test")

package(default_visibility=["//visibility:public"])

# 프롬프트·계약·계수는 소스가 아니라 data 다. 코드에 박지 않기로 한 것들이라
# (CLAUDE.md §6) 런타임에 파일로 읽는다 — 그래서 반드시 data 로 따라와야 한다.
filegroup(
    name="resources",
    srcs=glob(
        [
            "prompts/*.txt",
            "schemas/*.json",
            "config/*.json",
        ]
    ),
)

py_library(
    name="lib",
    srcs=glob(["src/*.py"]),
    data=[":resources"],
    imports=["."],
)

py_binary(
    name="bin",
    srcs=["src/cli.py"],
    main="src/cli.py",
    deps=[":lib"],
)

py_binary(
    name="web",
    srcs=["web/server.py"],
    main="web/server.py",
    data=["web/index.html"],
    deps=[":lib"],
)

# 결정적 단위 시험 — 모델도 네트워크도 부르지 않는다.
py_test(
    name="unit_test",
    srcs=glob(["tests/*.py"]),
    main="tests/run.py",
    deps=[":lib"],
)

# 평가는 테스트다 (CLAUDE.md §6). just agent-eval trip-guide 가 이것을 부른다.
py_test(
    name="eval_test",
    srcs=glob(["evals/*.py", "tests/*.py"]),
    main="evals/eval_test.py",
    data=["evals/cases.json"],
    deps=[":lib"],
)
```

## 붙일 때 함께 해야 하는 것

1. **`tests/run.py`** — `py_test` 는 `main` 하나를 부른다. `unittest.main()` 대신
   디스커버리를 도는 진입점이 필요하다.

   ```python
   import sys, unittest

   loader = unittest.TestLoader()
   suite = loader.discover("agents/trip-guide/tests", top_level_dir="agents/trip-guide")
   sys.exit(0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1)
   ```

   런파일 경로가 저장소 루트 기준이라 `top_level_dir` 을 명시해야 한다.

2. **`imports = ["."]`** — `src.planner` 같은 절대 임포트가 지금 형태 그대로 돌게 한다.

3. **`just agent-run trip-guide`** 가 그때부터 동작한다. README 의 「알고 두는 미완」
   절과 `python3 -m …` 임시 경로 안내를 지운다.

4. **`just agent-eval trip-guide`** 도 함께 산다 — 레시피가 `//agents/{name}:eval_test`
   를 부르는데 이름을 그대로 맞춰 두었다.

5. **`just check` 에 처음으로 파이썬이 들어온다.** ruff·mypy 같은 파이썬 린트를
   게이트에 넣을지도 그때 함께 정한다(`tools/just/lint.just`).

## 확인

- `just build-module agents/trip-guide` 가 초록인가.
- `just agent-eval trip-guide` 가 지표 5 종 100% 로 통과하는가.
- `bazel run //agents/trip-guide:bin -- --ask "도깨비 촬영지 알려줘"` 가 답하는가.
- 프롬프트 파일을 하나 지우고 빌드하면 **실패하는가** — data 가 제대로 물렸다는 증거다.
