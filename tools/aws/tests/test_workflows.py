"""배포 권한이 검증되지 않은 커밋의 코드에 전달되지 않는지 검사한다."""

import os
import unittest
from pathlib import Path


class WorkflowSecurityTest(unittest.TestCase):
    def test_verify_is_an_explicit_read_only_workflow_operation(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        source = (root / ".github/workflows/aws-deploy.yml").read_text()
        self.assertIn("options: [plan, apply, verify]", source)
        self.assertIn(
            "if: inputs.operation == 'verify'\n        run: just aws-verify", source
        )
        self.assertIn("if: always() && inputs.operation == 'apply'", source)

    def test_candidate_code_only_runs_after_trusted_preflight(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        for name, job_name in (
            ("aws-deploy.yml", "deploy"),
            ("aws-bootstrap.yml", "bootstrap"),
        ):
            with self.subTest(workflow=name):
                source = (root / ".github/workflows" / name).read_text()
                header, jobs = source.split("jobs:\n", 1)
                preflight, privileged = jobs.split(f"  {job_name}:\n", 1)
                self.assertNotIn("id-token: write", header)
                self.assertNotIn("id-token: write", preflight)
                self.assertNotIn("    environment:", preflight)
                self.assertIn("          ref: main\n", preflight)
                self.assertIn('run: just aws-preflight "$REQUESTED_SHA"', preflight)
                self.assertIn("      sha: ${{ steps.verify.outputs.sha }}", preflight)
                self.assertIn("    needs: preflight\n", privileged)
                self.assertIn("      id-token: write\n", privileged)
                self.assertIn(
                    "          ref: ${{ needs.preflight.outputs.sha }}", privileged
                )
                self.assertNotIn("inputs.commit_sha", privileged)
                self.assertNotIn("secrets.", preflight)
