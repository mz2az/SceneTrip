"""클러스터 없이 `just loadtest-mode` 스크립트의 입력 경계와 helm 인자를 검증한다(MZ2AZ-502).

명세: docs/project/plans/load-test.md §3 「시험 모드 켜고 끄기」.
- 환경은 dev 만. prd·그 밖은 helm 을 부르기 전에 거절한다.
- 상태는 on|off. on 은 시험 토큰의 aud(LOADTEST_GOOGLE_CLIENT_ID)와 공개 JWK 파일(LOADTEST_GOOGLE_JWK)이 필요하다.
- 지금 kube 컨텍스트가 DEV 클러스터(scenetrip-dev)가 아니면 거절한다. 접속은 스스로 열지 않는다.
- 이미 깔린 릴리스(scenetrip, 네임스페이스 scenetrip)의 값을 그대로 두고 loadtest 값만 바꿔 helm upgrade --wait.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

DEV_CONTEXT = "arn:aws:eks:ap-northeast-2:123456789012:cluster/scenetrip-dev"
PRD_CONTEXT = "arn:aws:eks:ap-northeast-2:123456789012:cluster/scenetrip-prd"


class LoadtestModeScriptTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        scripts = self.root / "tools" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("_lib.sh", "loadtest-mode.sh"):
            source = Path(__file__).parent / name
            self.assertTrue(source.is_file(), f"명령 구현이 없습니다: {name}")
            shutil.copyfile(source, scripts / name)
        self.script = scripts / "loadtest-mode.sh"
        self.calls = self.root / "helm-args"
        helm = self.root / "fake-helm"
        helm.write_text(
            '#!/bin/bash\nprintf "%s\\n" "$@" > "$TEST_HELM_ARGS"\nexit 0\n'
        )
        helm.chmod(0o755)
        kubectl = self.root / "fake-kubectl"
        kubectl.write_text(
            '#!/bin/bash\nif [ "$1 $2" = "config current-context" ]; then printf "%s\\n" "$TEST_CONTEXT"; exit 0; fi\n'
            "exit 9\n"
        )
        kubectl.chmod(0o755)
        self.jwk = self.root / "keys" / "jwk.json"
        self.jwk.parent.mkdir()
        self.jwk.write_text('{"kty":"RSA","kid":"k","n":"AQAB","e":"AQAB"}\n')
        self.env = {
            **os.environ,
            "HELM": str(helm),
            "KUBECTL": str(kubectl),
            "TEST_HELM_ARGS": str(self.calls),
            "TEST_CONTEXT": DEV_CONTEXT,
            "LOADTEST_GOOGLE_CLIENT_ID": "load-test.apps.googleusercontent.com",
            "LOADTEST_GOOGLE_JWK": str(self.jwk),
        }
        self.env.pop("LOADTEST_KUBE_CONTEXT", None)

    def run_script(self, *args, cwd=None, **env):
        merged = {**self.env, **env}
        for key in [k for k, v in merged.items() if v is None]:
            merged.pop(key)
        return subprocess.run(
            ["bash", str(self.script), *args],
            cwd=cwd or self.root,
            env=merged,
            capture_output=True,
            text=True,
            check=False,
        )

    def helm_args(self):
        self.assertTrue(self.calls.exists(), "helm 이 불리지 않았습니다")
        return self.calls.read_text().splitlines()

    def assert_refused(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse(self.calls.exists(), "거절해야 할 때 helm 이 불렸습니다")

    def assert_flag(self, args, flag, value):
        self.assertIn(flag, args)
        pairs = [args[i + 1] for i, a in enumerate(args[:-1]) if a == flag]
        self.assertIn(value, pairs, f"{flag} {value} 가 없습니다: {args}")

    def test_prd_is_refused_before_helm(self):
        for state in ("on", "off"):
            with self.subTest(state=state):
                result = self.run_script("prd", state)
                self.assert_refused(result)
                self.assertIn("dev", result.stderr)

    def test_unknown_environment_and_state_are_refused(self):
        for args in (("stage", "on"), ("dev", "maybe"), ("dev",), ()):
            with self.subTest(args=args):
                self.assert_refused(self.run_script(*args))

    def test_on_requires_client_id_and_jwk_file(self):
        for env in (
            {"LOADTEST_GOOGLE_CLIENT_ID": None},
            {"LOADTEST_GOOGLE_CLIENT_ID": ""},
            {"LOADTEST_GOOGLE_JWK": None},
            {"LOADTEST_GOOGLE_JWK": str(self.root / "missing.json")},
        ):
            with self.subTest(env=env):
                self.assert_refused(self.run_script("dev", "on", **env))

    def test_on_rejects_client_id_that_helm_would_split(self):
        for value in ("a,b", "a=b", "a b"):
            with self.subTest(value=value):
                self.assert_refused(
                    self.run_script("dev", "on", LOADTEST_GOOGLE_CLIENT_ID=value)
                )

    def test_non_dev_context_is_refused(self):
        for context in (PRD_CONTEXT, "kind-scenetrip", ""):
            with self.subTest(context=context):
                self.assert_refused(self.run_script("dev", "on", TEST_CONTEXT=context))

    def test_on_upgrades_existing_release_with_loadtest_values(self):
        result = self.run_script("dev", "on")
        self.assertEqual(result.returncode, 0, result.stderr)
        args = self.helm_args()
        self.assertEqual(args[:2], ["upgrade", "scenetrip"])
        self.assertEqual(Path(args[2]), self.root / "platform/helm/scenetrip")
        self.assertNotIn("--install", args)
        self.assert_flag(args, "--namespace", "scenetrip")
        self.assert_flag(args, "--kube-context", DEV_CONTEXT)
        # 깔린 값(이미지·DB·도메인…)은 그대로 둔다
        self.assertTrue(
            "--reuse-values" in args or "--reset-then-reuse-values" in args, args
        )
        self.assertIn("--wait", args)
        self.assert_flag(args, "--set", "loadtest.enabled=true")
        self.assertTrue(
            "loadtest.googleClientId=load-test.apps.googleusercontent.com"
            in [args[i + 1] for i, a in enumerate(args[:-1]) if a.startswith("--set")]
        )
        self.assert_flag(args, "--set-file", f"loadtest.googleJwkJson={self.jwk}")

    def test_relative_jwk_path_is_made_absolute(self):
        result = self.run_script(
            "dev", "on", cwd=self.jwk.parent, LOADTEST_GOOGLE_JWK="jwk.json"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        files = [
            a.split("=", 1)[1]
            for i, a in enumerate(self.helm_args())
            if i and self.helm_args()[i - 1] == "--set-file"
        ]
        self.assertEqual(len(files), 1)
        self.assertTrue(Path(files[0]).is_absolute(), files)
        self.assertEqual(Path(files[0]).resolve(), self.jwk.resolve())

    def test_off_needs_no_test_inputs_and_turns_mode_off(self):
        result = self.run_script(
            "dev", "off", LOADTEST_GOOGLE_CLIENT_ID=None, LOADTEST_GOOGLE_JWK=None
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        args = self.helm_args()
        self.assertEqual(args[:2], ["upgrade", "scenetrip"])
        self.assert_flag(args, "--namespace", "scenetrip")
        self.assert_flag(args, "--set", "loadtest.enabled=false")
        self.assertNotIn("--set-file", args)
        self.assertIn("--wait", args)

    def test_explicit_context_overrides_current_context(self):
        result = self.run_script(
            "dev", "off", TEST_CONTEXT=PRD_CONTEXT, LOADTEST_KUBE_CONTEXT=DEV_CONTEXT
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_flag(self.helm_args(), "--kube-context", DEV_CONTEXT)

    def test_helm_failure_is_reported(self):
        failing = self.root / "failing-helm"
        failing.write_text("#!/bin/bash\nexit 7\n")
        failing.chmod(0o755)
        result = self.run_script("dev", "on", HELM=str(failing))
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()
