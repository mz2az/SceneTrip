"""기기·앱 대신 대역을 써서 설치 경계와 실행 인자 보존을 검증한다."""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


class IosRunTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory(prefix="ios run '")
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.temp = self.root / "temporary bundles"
        self.temp.mkdir()
        scripts = self.root / "tools" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("_lib.sh", "ios-run.sh"):
            shutil.copyfile(Path(__file__).parent / name, scripts / name)
        self.script = scripts / "ios-run.sh"
        self.trace = self.root / "trace.jsonl"
        self.udid = "00000000-0000-0000-0000-000000000017"
        binary = self.root / "fake bin"
        binary.mkdir()
        stub = f"""#!{sys.executable}
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['TEST_TRACE'], 'a') as trace:
    trace.write(json.dumps(['xcrun', *args]) + '\\n')
if args[1] == 'getenv':
    print(os.environ['TEST_UDID'])
    sys.exit(int(os.environ.get('TEST_DEVICE_STATUS', '0')))
if args[1] == 'install':
    if not Path(args[3], 'Info.plist').is_file(): sys.exit(91)
sys.exit(int(os.environ.get('TEST_' + args[1].upper() + '_STATUS', '0')))
"""
        for name in ("xcrun",):
            command = binary / name
            command.write_text(stub)
            command.chmod(0o755)
        ipa = self.root / "bazel-bin" / "apps" / "scenetrip-ios" / "bin.ipa"
        ipa.parent.mkdir(parents=True)
        with zipfile.ZipFile(ipa, "w") as archive:
            archive.writestr("Payload/bin.app/Info.plist", "test bundle")
            archive.writestr("Payload/bin.app/bin", "test executable")
        self.ipa = ipa
        self.env = {
            **os.environ,
            "PATH": f"{binary}:{os.environ['PATH']}",
            "SCENETRIP_IOS_DEVICE": "",
            "TEST_TRACE": str(self.trace),
            "TEST_UDID": self.udid,
            "TMPDIR": str(self.temp),
        }

    def run_script(self, *args, **env):
        result = subprocess.run(
            ["bash", str(self.script), *args],
            cwd=self.root,
            env={**self.env, **env},
            text=True,
            capture_output=True,
            check=False,
        )
        self.calls = (
            [json.loads(line) for line in self.trace.read_text().splitlines()]
            if self.trace.exists()
            else []
        )
        return result

    def test_same_bazel_ipa_is_installed_without_deleting_data(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.calls[0], ["xcrun", "simctl", "getenv", "booted", "SIMULATOR_UDID"]
        )
        self.assertEqual(self.calls[1][:4], ["xcrun", "simctl", "install", self.udid])
        self.assertEqual(
            self.calls[2],
            [
                "xcrun",
                "simctl",
                "launch",
                "--terminate-running-process",
                self.udid,
                "com.mz2az.scenetrip",
            ],
        )
        self.assertEqual(len(self.calls), 3)
        self.assertFalse(
            Path(self.calls[1][4]).parent.parent.exists(),
            "임시 번들은 종료 뒤 정리한다",
        )

    def test_explicit_device_and_fault_arguments_are_preserved(self):
        fault = "guide/chat:down:all,guide/plan:down:all,navigation/next-leg:down:all"
        args = [
            "-demoDrive",
            "0",
            "-netFault",
            fault,
            "-title",
            "space ' ; $(not executed)",
        ]
        result = self.run_script(*args, SCENETRIP_IOS_DEVICE=self.udid)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls[0][3], self.udid)
        self.assertEqual(self.calls[-1][6:], args)

    def test_unavailable_or_ambiguous_device_stops_before_install(self):
        result = self.run_script(TEST_DEVICE_STATUS="37")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SCENETRIP_IOS_DEVICE", result.stderr)
        self.assertEqual(len(self.calls), 1)

    def test_missing_or_broken_ipa_stops_before_install(self):
        for broken in (False, True):
            with self.subTest(broken=broken):
                if broken:
                    self.ipa.write_text("invalid archive")
                else:
                    self.ipa.unlink()
                self.trace.unlink(missing_ok=True)
                result = self.run_script()
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(len(self.calls), 1)
                self.assertEqual(list(self.temp.iterdir()), [])

    def test_missing_bundle_stops_before_install_and_cleans_temp(self):
        with zipfile.ZipFile(self.ipa, "w") as archive:
            archive.writestr("unexpected.txt", "not an app")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls), 1)
        self.assertIn("번들", result.stderr)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_install_failure_stops_before_launch_and_cleans_temp(self):
        result = self.run_script(TEST_INSTALL_STATUS="39")
        self.assertEqual(result.returncode, 39)
        self.assertEqual(len(self.calls), 2)
        self.assertFalse(Path(self.calls[-1][4]).parent.parent.exists())

    def test_launch_failure_is_not_swallowed(self):
        result = self.run_script(TEST_LAUNCH_STATUS="40")
        self.assertEqual(result.returncode, 40)
        self.assertEqual(len(self.calls), 3)
        self.assertFalse(Path(self.calls[1][4]).parent.parent.exists())


if __name__ == "__main__":
    unittest.main()
