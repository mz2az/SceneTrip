"""배포 권한이 검증되지 않은 커밋의 코드에 전달되지 않는지 검사한다."""

import os
import unittest
from pathlib import Path


class WorkflowSecurityTest(unittest.TestCase):
    def test_teardown_is_manual_scoped_and_serializes_with_deployment(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        source = (root / ".github/workflows/aws-destroy.yml").read_text()
        self.assertIn("workflow_dispatch:", source)
        self.assertNotIn("  push:", source)
        self.assertNotIn("  pull_request:", source)
        self.assertIn("group: aws-${{ inputs.environment }}", source)
        self.assertIn("cancel-in-progress: false", source)
        self.assertIn("options: [service, bootstrap]", source)
        self.assertIn("options: [plan, destroy]", source)
        header, jobs = source.split("jobs:\n", 1)
        preflight, service = jobs.split("  service:\n", 1)
        self.assertNotIn("id-token: write", header + preflight)
        self.assertNotIn("    environment:", preflight)
        self.assertIn("ref: main", preflight)
        self.assertIn('just aws-preflight "$REQUESTED_SHA"', preflight)
        for scope, operation, role in (
            ("service", "aws-destroy", "AWS_DEPLOY_ROLE_ARN"),
            ("bootstrap", "aws-bootstrap-delete", "AWS_BOOTSTRAP_ROLE_ARN"),
        ):
            body = (
                service.split("  bootstrap:\n")[0]
                if scope == "service"
                else service.split("  bootstrap:\n")[1]
            )
            self.assertIn("needs: preflight", body)
            self.assertIn(f"inputs.scope == '{scope}'", body)
            self.assertIn("github.ref == 'refs/heads/main'", body)
            self.assertIn("environment: ${{ inputs.environment }}", body)
            self.assertIn("ref: ${{ needs.preflight.outputs.sha }}", body)
            self.assertNotIn("inputs.commit_sha", body)
            self.assertIn("role-to-assume: ${{ vars." + role + " }}", body)
            self.assertLess(
                body.index("just aws-teardown-validate"),
                body.index("aws-actions/configure-aws-credentials"),
            )
            self.assertIn("if: inputs.operation == 'plan'", body)
            self.assertIn("if: inputs.operation == 'destroy'", body)
            self.assertIn("just --yes " + operation, body)
            self.assertNotIn("always()", body)
            duration = "7200" if scope == "service" else "3600"
            self.assertIn("role-duration-seconds: " + duration, body)

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

    def test_lifecycle_is_manual_main_only_and_does_not_deadlock(self):
        root = Path(os.environ["TEST_SRCDIR"]) / os.environ["TEST_WORKSPACE"]
        source = (root / ".github/workflows/dev-lifecycle.yml").read_text()
        header, job = source.split("jobs:\n", 1)
        self.assertIn("workflow_dispatch:", header)
        for trigger in ("  push:", "  pull_request:", "  schedule:"):
            self.assertNotIn(trigger, header)
        self.assertNotIn("id-token: write", header)
        # 호출한 배포·삭제가 aws-<env> 그룹을 쓰므로 같은 그룹이면 서로를 기다린다.
        self.assertIn("group: lifecycle-${{ inputs.environment }}", header)
        self.assertNotIn("group: aws-", header)
        self.assertIn("if: github.ref == 'refs/heads/main'", job)
        self.assertIn("environment: ${{ inputs.environment }}", job)
        self.assertIn("role-to-assume: ${{ vars.AWS_LIFECYCLE_ROLE_ARN }}", job)
        self.assertNotIn("AWS_DEPLOY_ROLE_ARN", job)
        self.assertNotIn("AWS_BOOTSTRAP_ROLE_ARN", job)
        self.assertNotIn("inputs.commit_sha", job)
        self.assertEqual(job.count("secrets."), 1)
        self.assertIn("secrets.CLOUDFLARE_API_TOKEN", job)
        self.assertIn("persist-credentials: false", job)
        run_step, stop_step = job.split("runner 끄기", 1)
        self.assertIn(
            'just aws-lifecycle "$TARGET_ENVIRONMENT" "$LIFECYCLE_ACTION"', run_step
        )
        self.assertIn("if: always()", stop_step)
        self.assertIn("stop-runner", stop_step)
        self.assertNotIn("CLOUDFLARE_API_TOKEN", stop_step)
