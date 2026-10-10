"""기기·앱 대신 대역을 써서 설치 경계와 실행 인자 보존을 검증한다."""

import datetime
import json
import os
import plistlib
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
        # 가짜 xcrun — 켜진/설치된 기기 목록은 TEST_BOOTED/TEST_AVAILABLE(「이름|UDID」 줄)에서 만든다.
        xcrun = f"""#!{sys.executable}
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ['TEST_TRACE'], 'a') as trace:
    trace.write(json.dumps(['xcrun', *args]) + '\\n')
def devices(name, state):
    print('== Devices ==')
    print('-- iOS 26.0 --')
    for line in os.environ.get(name, '').splitlines():
        if line.strip():
            label, udid = line.split('|')
            print(f'    {{label}} ({{udid}}) ({{state}}) ')
booted = [l for l in os.environ.get('TEST_BOOTED', '').splitlines() if l.strip()]
if args[1] == 'list':
    if args[3] == 'booted':
        devices('TEST_BOOTED', 'Booted')
    else:
        devices('TEST_AVAILABLE', 'Shutdown')
    sys.exit(0)
if args[1] == 'getenv':
    if args[2] == 'booted' and len(booted) > 1:
        print('Multiple devices are booted', file=sys.stderr)
        sys.exit(148)
    print(os.environ['TEST_UDID'])
    sys.exit(int(os.environ.get('TEST_DEVICE_STATUS', '0')))
if args[1] == 'install':
    if not Path(args[3], 'Info.plist').is_file(): sys.exit(91)
sys.exit(int(os.environ.get('TEST_' + args[1].upper() + '_STATUS', '0')))
"""
        # 가짜 open — Simulator 창. TEST_OPEN_STATUS 로 실패를 흉내 낸다.
        opener = f"""#!{sys.executable}
import json, os, sys
with open(os.environ['TEST_TRACE'], 'a') as trace:
    trace.write(json.dumps(['open', *sys.argv[1:]]) + '\\n')
sys.exit(int(os.environ.get('TEST_OPEN_STATUS', '0')))
"""
        # 가짜 plutil — `plutil -extract KEY raw FILE` 만. 호스트 plutil 없이(리눅스 CI 포함) 돈다.
        plutil = f"""#!{sys.executable}
import datetime, plistlib, sys
args = sys.argv[1:]
if len(args) != 4 or args[0] != '-extract' or args[2] != 'raw':
    sys.exit(2)
try:
    with open(args[3], 'rb') as handle:
        value = plistlib.load(handle)[args[1]]
except Exception:
    sys.exit(1)
if isinstance(value, datetime.datetime):
    value = value.strftime('%Y-%m-%dT%H:%M:%SZ')
print(value)
"""
        for name, text in (("xcrun", xcrun), ("open", opener), ("plutil", plutil)):
            command = binary / name
            command.write_text(text)
            command.chmod(0o755)
        # 실제 ~/Library/Developer/CoreSimulator 대신 쓰는 빈 시뮬레이터 루트.
        self.simulators = self.root / "simulator devices"
        self.simulators.mkdir()
        ipa = self.root / "bazel-bin" / "apps" / "scenetrip-ios" / "bin.ipa"
        ipa.parent.mkdir(parents=True)
        with zipfile.ZipFile(ipa, "w") as archive:
            archive.writestr("Payload/bin.app/Info.plist", "test bundle")
            archive.writestr("Payload/bin.app/bin", "test executable")
        self.ipa = ipa
        self.env = {
            **os.environ,
            "PATH": f"{binary}:{os.environ['PATH']}",
            "HOME": str(self.root / "home"),
            "SCENETRIP_IOS_DEVICE": "",
            "SCENETRIP_SIMULATOR_ROOT": str(self.simulators),
            "TEST_AVAILABLE": "",
            "TEST_BOOTED": f"iPhone 17|{self.udid}",
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
        # 목록 조회를 뺀, 상태를 바꾸거나 기기를 고르는 호출.
        self.actions = [call for call in self.calls if call[1:3] != ["simctl", "list"]]
        return result

    def add_simulator(self, udid, name, booted_at, bundle_id=None):
        device = self.simulators / udid
        device.mkdir()
        info = {"UDID": udid, "name": name, "state": 1}
        if booted_at is not None:
            info["lastBootedAt"] = booted_at
        (device / "device.plist").write_bytes(plistlib.dumps(info))
        if bundle_id is not None:
            app = (
                device
                / "data/Containers/Bundle/Application"
                / f"{udid[:8]}-APP"
                / "SceneTrip.app"
            )
            app.mkdir(parents=True)
            (app / "Info.plist").write_bytes(
                plistlib.dumps(
                    {"CFBundleIdentifier": bundle_id}, fmt=plistlib.FMT_BINARY
                )
            )

    def assert_booted_then_installed(self, udid, device="booted"):
        self.assertEqual(self.actions[0], ["xcrun", "simctl", "boot", udid])
        self.assertEqual(self.actions[1], ["xcrun", "simctl", "bootstatus", udid, "-b"])
        self.assertEqual(self.actions[2][0], "open")
        self.assertIn("Simulator", self.actions[2])
        self.assertEqual(
            self.actions[3], ["xcrun", "simctl", "getenv", device, "SIMULATOR_UDID"]
        )
        self.assertEqual(self.actions[4][:4], ["xcrun", "simctl", "install", self.udid])
        self.assertEqual(self.actions[5][2], "launch")
        self.assertEqual(len(self.actions), 6)

    def test_same_bazel_ipa_is_installed_without_deleting_data(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            self.actions[0], ["xcrun", "simctl", "getenv", "booted", "SIMULATOR_UDID"]
        )
        self.assertEqual(self.actions[1][:4], ["xcrun", "simctl", "install", self.udid])
        self.assertEqual(
            self.actions[2],
            [
                "xcrun",
                "simctl",
                "launch",
                "--terminate-running-process",
                self.udid,
                "com.mz2az.scenetrip",
            ],
        )
        self.assertEqual(len(self.actions), 3)
        self.assertFalse(
            Path(self.actions[1][4]).parent.parent.exists(),
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
        self.assertEqual(self.actions[0][3], self.udid)
        self.assertEqual(self.actions[-1][6:], args)

    def test_unavailable_or_ambiguous_device_stops_before_install(self):
        result = self.run_script(TEST_DEVICE_STATUS="37")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SCENETRIP_IOS_DEVICE", result.stderr)
        self.assertEqual(len(self.actions), 1)

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
                self.assertEqual(len(self.actions), 1)
                self.assertEqual(list(self.temp.iterdir()), [])

    def test_missing_bundle_stops_before_install_and_cleans_temp(self):
        with zipfile.ZipFile(self.ipa, "w") as archive:
            archive.writestr("unexpected.txt", "not an app")
        result = self.run_script()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.actions), 1)
        self.assertIn("번들", result.stderr)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_install_failure_stops_before_launch_and_cleans_temp(self):
        result = self.run_script(TEST_INSTALL_STATUS="39")
        self.assertEqual(result.returncode, 39)
        self.assertEqual(len(self.actions), 2)
        self.assertFalse(Path(self.actions[-1][4]).parent.parent.exists())

    def test_launch_failure_is_not_swallowed(self):
        result = self.run_script(TEST_LAUNCH_STATUS="40")
        self.assertEqual(result.returncode, 40)
        self.assertEqual(len(self.actions), 3)
        self.assertFalse(Path(self.actions[1][4]).parent.parent.exists())

    def test_none_booted_boots_most_recent_simulator_with_app(self):
        older = "AAAAAAAA-0000-0000-0000-000000000001"
        newer = "BBBBBBBB-0000-0000-0000-000000000002"
        other_app = "CCCCCCCC-0000-0000-0000-000000000003"
        no_app = "DDDDDDDD-0000-0000-0000-000000000004"
        moment = datetime.datetime(2026, 10, 1, 9, 0, 0, tzinfo=datetime.timezone.utc)
        self.add_simulator(older, "iPhone 16", moment, "com.mz2az.scenetrip")
        self.add_simulator(
            newer,
            "iPhone 17 Pro",
            moment + datetime.timedelta(days=3),
            "com.mz2az.scenetrip",
        )
        self.add_simulator(
            other_app,
            "iPhone Air",
            moment + datetime.timedelta(days=5),
            "com.example.other",
        )
        self.add_simulator(no_app, "iPhone 15", moment + datetime.timedelta(days=7))
        result = self.run_script(
            TEST_BOOTED="",
            TEST_AVAILABLE=f"iPhone 15|{no_app}\niPhone 16|{older}\niPhone 17 Pro|{newer}",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_booted_then_installed(newer)

    def test_none_booted_without_app_boots_first_iphone_by_name(self):
        # 다른 앱만 깔린 시뮬레이터는 「앱이 깔린 것」으로 치지 않는다.
        self.add_simulator(
            "CCCCCCCC-0000-0000-0000-000000000003",
            "iPhone Air",
            datetime.datetime(2026, 10, 5, tzinfo=datetime.timezone.utc),
            "com.example.other",
        )
        first = "EEEEEEEE-0000-0000-0000-000000000016"
        available = "\n".join(
            [
                "iPad Air 11-inch (M3)|11111111-0000-0000-0000-000000000001",
                "iPhone 17 Pro|22222222-0000-0000-0000-000000000002",
                f"iPhone 16e|{first}",
                "iPhone 17|33333333-0000-0000-0000-000000000003",
            ]
        )
        result = self.run_script(TEST_BOOTED="", TEST_AVAILABLE=available)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_booted_then_installed(first)

    def test_specified_device_that_is_off_is_booted(self):
        chosen = "FFFFFFFF-0000-0000-0000-000000000005"
        result = self.run_script(
            "-demoDrive",
            "0",
            SCENETRIP_IOS_DEVICE=chosen,
            TEST_BOOTED="",
            TEST_AVAILABLE=f"iPhone 16e|11111111-0000-0000-0000-000000000001\niPhone 17|{chosen}",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_booted_then_installed(chosen, device=chosen)
        self.assertEqual(self.actions[-1][6:], ["-demoDrive", "0"])

    def test_two_booted_without_choice_stops_before_install_without_booting(self):
        result = self.run_script(
            TEST_BOOTED=f"iPhone 17|{self.udid}\niPhone 16e|11111111-0000-0000-0000-000000000001"
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SCENETRIP_IOS_DEVICE", result.stderr)
        verbs = [call[2] if call[0] == "xcrun" else call[0] for call in self.actions]
        self.assertNotIn("boot", verbs)
        self.assertNotIn("install", verbs)
        self.assertNotIn("launch", verbs)
        self.assertEqual(list(self.temp.iterdir()), [])

    def test_simulator_window_failure_does_not_fail_the_run(self):
        first = "EEEEEEEE-0000-0000-0000-000000000016"
        result = self.run_script(
            TEST_BOOTED="",
            TEST_AVAILABLE=f"iPhone 16e|{first}",
            TEST_OPEN_STATUS="1",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_booted_then_installed(first)

    def test_no_available_iphone_is_a_clear_error(self):
        result = self.run_script(
            TEST_BOOTED="",
            TEST_AVAILABLE="iPad Air 11-inch (M3)|11111111-0000-0000-0000-000000000001",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("시뮬레이터", result.stderr)
        self.assertIn("Xcode", result.stderr)
        self.assertEqual(self.actions, [])
        self.assertEqual(list(self.temp.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
