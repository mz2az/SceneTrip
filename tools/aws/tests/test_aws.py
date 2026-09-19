"""Bazel 단위 테스트 진입점."""

import unittest

from tools.aws.tests.test_alb import AlbBoundaryTest, AlbChartTest
from tools.aws.tests.test_boundaries import BoundaryTest
from tools.aws.tests.test_commands import CliTest
from tools.aws.tests.test_deploy import DeployTest, OrchestrationTest, SettingsTest
from tools.aws.tests.test_entrypoint import EntryPointTest
from tools.aws.tests.test_workflows import WorkflowSecurityTest

__all__ = [
    "AlbBoundaryTest",
    "AlbChartTest",
    "BoundaryTest",
    "CliTest",
    "DeployTest",
    "EntryPointTest",
    "OrchestrationTest",
    "SettingsTest",
    "WorkflowSecurityTest",
]

if __name__ == "__main__":
    unittest.main()
