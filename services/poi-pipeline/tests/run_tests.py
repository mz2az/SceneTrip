"""pytest 없이 pytest 모양의 검사를 돈다 — pip 의존성을 들이지 않으려고.

`tmp_path`·`capsys`·`monkeypatch` 세 픽스처만 흉내 낸다. 그 이상이 필요해지면 그때
rules_python 의 pip 확장으로 pytest 를 들인다(계획서 §5).
"""

from __future__ import annotations

import contextlib
import importlib
import inspect
import io
import os
import pathlib
import sys
import tempfile
import traceback


class Capsys:
    def __init__(self):
        self.out, self.err = io.StringIO(), io.StringIO()
        self._stack = contextlib.ExitStack()

    def __enter__(self):
        self._stack.enter_context(contextlib.redirect_stdout(self.out))
        self._stack.enter_context(contextlib.redirect_stderr(self.err))
        return self

    def __exit__(self, *exc):
        self._stack.close()

    def readouterr(self):
        pair = type(
            "Captured", (), {"out": self.out.getvalue(), "err": self.err.getvalue()}
        )()
        self.out.seek(0), self.out.truncate(), self.err.seek(0), self.err.truncate()
        return pair


class Monkeypatch:
    def __init__(self):
        self._env: list[tuple[str, str | None]] = []

    def delenv(self, name, raising=True):
        if name in os.environ:
            self._env.append((name, os.environ.pop(name)))
        elif raising:
            raise KeyError(name)

    def setenv(self, name, value):
        self._env.append((name, os.environ.get(name)))
        os.environ[name] = value

    def undo(self):
        for name, old in reversed(self._env):
            if old is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = old


def run_one(func) -> None:
    kwargs = {}
    tmp = tempfile.TemporaryDirectory()
    capsys, patch = Capsys(), Monkeypatch()
    names = inspect.signature(func).parameters
    if "tmp_path" in names:
        kwargs["tmp_path"] = pathlib.Path(tmp.name)
    if "capsys" in names:
        kwargs["capsys"] = capsys
    if "monkeypatch" in names:
        kwargs["monkeypatch"] = patch
    try:
        if "capsys" in names:
            with capsys:
                func(**kwargs)
        else:
            func(**kwargs)
    finally:
        patch.undo()
        tmp.cleanup()


def main() -> int:
    here = pathlib.Path(__file__).resolve().parent
    sys.path.insert(0, str(here))
    failures, passed = [], 0
    for file in sorted(here.glob("test_*.py")):
        module = importlib.import_module(file.stem)
        for name, func in inspect.getmembers(module, inspect.isfunction):
            if not name.startswith("test_") or func.__module__ != module.__name__:
                continue
            try:
                run_one(func)
                passed += 1
            except Exception:  # noqa: BLE001 — 실패를 모아 한 번에 보고한다
                failures.append(f"{file.stem}.{name}\n{traceback.format_exc()}")
    for failure in failures:
        print(failure, file=sys.stderr)
    print(f"{passed} passed, {len(failures)} failed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
