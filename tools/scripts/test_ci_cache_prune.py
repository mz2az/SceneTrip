"""실제 GitHub API 없이 가짜 gh 로 CI 캐시 정리 스크립트의 지우기 범위를 검증한다."""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PREFIX = "bazel-Linux-"
KEEP = "bazel-Linux-abc123"

FAKE_GH = r"""#!/bin/bash
# 호출 하나를 한 줄로 남긴다(인자는 탭으로 구분).
(IFS=$'\t'; printf '%s\n' "$*") >> "$TEST_CALLS"
if [ "$1 $2" = "cache list" ]; then
  printf '%s' "$TEST_LIST"
  exit "${TEST_LIST_STATUS:-0}"
fi
if [ "$1 $2" = "cache delete" ]; then
  if [ -n "${TEST_FAIL_ID:-}" ] && [ "$3" = "$TEST_FAIL_ID" ]; then
    exit 1
  fi
  exit 0
fi
exit 0
"""


def listing(*rows):
    return "".join(f"{cache_id}\t{key}\n" for cache_id, key in rows)


class CiCachePruneTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        scripts = self.root / "tools" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("_lib.sh", "ci-cache-prune.sh"):
            source = Path(__file__).parent / name
            self.assertTrue(source.is_file(), f"명령 구현이 없습니다: {name}")
            shutil.copyfile(source, scripts / name)
        self.script = scripts / "ci-cache-prune.sh"
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fake = self.bin / "gh"
        fake.write_text(FAKE_GH)
        fake.chmod(0o755)
        self.calls = self.root / "calls"
        self.env = {
            **os.environ,
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "TEST_CALLS": str(self.calls),
            "TEST_LIST": "",
        }
        self.env.pop("DRY_RUN", None)

    def run_script(self, *args, env=None, **extra):
        return subprocess.run(
            [shutil.which("bash") or "/bin/bash", str(self.script), *args],
            cwd=self.root,
            env={**(env if env is not None else self.env), **extra},
            capture_output=True,
            text=True,
            check=False,
        )

    def gh_calls(self):
        if not self.calls.exists():
            return []
        return [line.split("\t") for line in self.calls.read_text().splitlines()]

    def deleted_ids(self):
        calls = self.gh_calls()
        return [c[2] for c in calls if c[:2] == ["cache", "delete"] and len(c) > 2]

    def list_calls(self):
        return [c for c in self.gh_calls() if c[:2] == ["cache", "list"]]

    def test_wrong_argument_count_fails(self):
        for args in ((), (KEEP,), (KEEP, PREFIX, "extra")):
            with self.subTest(args=args):
                self.calls.unlink(missing_ok=True)
                result = self.run_script(
                    *args, TEST_LIST=listing((1, KEEP), (2, PREFIX + "old"))
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.deleted_ids(), [])

    def test_empty_prefix_fails_without_delete(self):
        result = self.run_script(
            KEEP, "", TEST_LIST=listing((1, KEEP), (2, PREFIX + "old"))
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.deleted_ids(), [])

    def test_keep_outside_prefix_fails_without_delete(self):
        result = self.run_script(
            "other-key",
            PREFIX,
            TEST_LIST=listing((1, "other-key"), (2, PREFIX + "old")),
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.deleted_ids(), [])

    def test_missing_gh_fails(self):
        tools = self.root / "tools-only"
        tools.mkdir()
        for name in (
            "bash",
            "sh",
            "cut",
            "grep",
            "dirname",
            "cat",
            "sed",
            "awk",
            "tr",
            "head",
            "tail",
            "wc",
            "sort",
            "env",
            "printf",
            "basename",
            "uname",
        ):
            found = shutil.which(name)
            if found and Path(found).name != "gh":
                (tools / name).symlink_to(found)
        env = {**self.env, "PATH": str(tools)}
        result = self.run_script(KEEP, PREFIX, env=env)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls.exists())

    def test_list_passes_ref_key_json_and_jq(self):
        result = self.run_script(KEEP, PREFIX, TEST_LIST=listing((1, KEEP)))
        self.assertEqual(result.returncode, 0, result.stderr)
        lists = self.list_calls()
        self.assertEqual(len(lists), 1, self.gh_calls())
        args = lists[0]

        def value_of(flag):
            if flag in args:
                index = args.index(flag)
                self.assertLess(index + 1, len(args), f"{flag} 값이 없습니다: {args}")
                return args[index + 1]
            joined = [a for a in args if a.startswith(flag + "=")]
            self.assertTrue(joined, f"{flag} 가 없습니다: {args}")
            return joined[0].split("=", 1)[1]

        self.assertEqual(value_of("--ref"), "refs/heads/main")
        self.assertEqual(value_of("--key"), PREFIX)
        fields = {f.strip() for f in value_of("--json").split(",")}
        self.assertTrue({"id", "key"} <= fields, fields)
        jq = value_of("--jq")
        self.assertIn(".id", jq)
        self.assertIn(".key", jq)
        self.assertIn("\\t", jq)

    def test_keep_not_listed_deletes_nothing_and_warns(self):
        result = self.run_script(
            KEEP, PREFIX, TEST_LIST=listing((1, PREFIX + "old"), (2, PREFIX + "older"))
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.deleted_ids(), [])
        self.assertNotEqual(result.stderr.strip(), "")

    def test_keep_only_as_prefix_of_listed_key_is_not_a_match(self):
        result = self.run_script(
            KEEP, PREFIX, TEST_LIST=listing((1, KEEP + "-x"), (2, PREFIX + "old"))
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.deleted_ids(), [])

    def test_deletes_all_others_by_id_and_keeps_exact_match(self):
        rows = (
            (101, PREFIX + "old1"),
            (102, KEEP),
            (103, PREFIX + "old2"),
            (104, KEEP + "-longer"),
            (105, KEEP[:-1]),
        )
        result = self.run_script(KEEP, PREFIX, TEST_LIST=listing(*rows))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(self.deleted_ids()), ["101", "103", "104", "105"])
        self.assertNotIn("102", self.deleted_ids())
        for call in self.gh_calls():
            self.assertNotIn(KEEP, call[2:] if call[:2] == ["cache", "delete"] else [])

    def test_only_keep_listed_deletes_nothing(self):
        result = self.run_script(KEEP, PREFIX, TEST_LIST=listing((7, KEEP)))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.deleted_ids(), [])

    def test_dry_run_deletes_nothing(self):
        result = self.run_script(
            KEEP, PREFIX, DRY_RUN="1", TEST_LIST=listing((1, PREFIX + "old"), (2, KEEP))
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.deleted_ids(), [])

    def test_one_delete_failure_continues(self):
        rows = ((1, PREFIX + "a"), (2, PREFIX + "b"), (3, KEEP), (4, PREFIX + "c"))
        result = self.run_script(
            KEEP, PREFIX, TEST_LIST=listing(*rows), TEST_FAIL_ID="2"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(self.deleted_ids()), ["1", "2", "4"])


if __name__ == "__main__":
    unittest.main()
