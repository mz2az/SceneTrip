"""Bazel 단위 테스트 진입점."""

import unittest

from tools.aws.tests.test_alb import AlbBoundaryTest, AlbChartTest
from tools.aws.tests.test_bootstrap_delete import BootstrapDeleteTest
from tools.aws.tests.test_bootstrap_state import BootstrapStateTest
from tools.aws.tests.test_boundaries import BoundaryTest
from tools.aws.tests.test_commands import CliTest
from tools.aws.tests.test_deploy import DeployTest, OrchestrationTest, SettingsTest
from tools.aws.tests.test_destroy import DestroyServiceTest
from tools.aws.tests.test_destroy_gateway import DestroyGatewayTest
from tools.aws.tests.test_destroy_plan import DestroyPlanTest
from tools.aws.tests.test_entrypoint import EntryPointTest
from tools.aws.tests.test_teardown_entrypoint import TeardownEntryPointTest
from tools.aws.tests.test_workflows import WorkflowSecurityTest

__all__ = [
    "AlbBoundaryTest",
    "AlbChartTest",
    "BootstrapDeleteTest",
    "BootstrapStateTest",
    "BoundaryTest",
    "CliTest",
    "DeployTest",
    "DestroyGatewayTest",
    "DestroyPlanTest",
    "DestroyServiceTest",
    "EntryPointTest",
    "OrchestrationTest",
    "SettingsTest",
    "TeardownEntryPointTest",
    "WorkflowSecurityTest",
]

if __name__ == "__main__":
    unittest.main()
