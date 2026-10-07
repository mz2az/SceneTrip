"""Bazel 단위 테스트 진입점."""

import unittest

from tools.aws.tests.test_alb import AlbBoundaryTest, AlbChartTest
from tools.aws.tests.test_apple_secret import (
    DeployAppleWiringTest,
    LegacyAuthSlotTest,
    SceneApiAppleKeyTest,
    ValidateEncryptionKeyTest,
)
from tools.aws.tests.test_auth_secret import (
    AuthSecretTest,
    DatabaseCredentialsGeneratedOnceTest,
    DeploySecretWiringTest,
    DestroyIdentityAuthTest,
    RetainedAuthSecretTest,
    ValidateAuthSecretTest,
)
from tools.aws.tests.test_bootstrap_delete import BootstrapDeleteTest
from tools.aws.tests.test_bootstrap_state import BootstrapStateTest
from tools.aws.tests.test_boundaries import BoundaryTest
from tools.aws.tests.test_commands import CliTest
from tools.aws.tests.test_deploy import DeployTest, OrchestrationTest, SettingsTest
from tools.aws.tests.test_destroy import DestroyServiceTest
from tools.aws.tests.test_destroy_gateway import DestroyGatewayTest
from tools.aws.tests.test_destroy_plan import DestroyPlanTest
from tools.aws.tests.test_entrypoint import EntryPointTest
from tools.aws.tests.test_lifecycle import CloudflareTest, LifecycleTest
from tools.aws.tests.test_media import MediaChartTest, MediaDeployValuesTest
from tools.aws.tests.test_public_ingress import (
    PublicChartTest,
    PublicGatewayValuesTest,
)
from tools.aws.tests.test_teardown_entrypoint import TeardownEntryPointTest
from tools.aws.tests.test_workflows import WorkflowSecurityTest

__all__ = [
    "AlbBoundaryTest",
    "AlbChartTest",
    "AuthSecretTest",
    "BootstrapDeleteTest",
    "BootstrapStateTest",
    "BoundaryTest",
    "CliTest",
    "CloudflareTest",
    "DatabaseCredentialsGeneratedOnceTest",
    "DeployAppleWiringTest",
    "DeploySecretWiringTest",
    "DeployTest",
    "DestroyGatewayTest",
    "DestroyIdentityAuthTest",
    "DestroyPlanTest",
    "DestroyServiceTest",
    "EntryPointTest",
    "LegacyAuthSlotTest",
    "LifecycleTest",
    "MediaChartTest",
    "MediaDeployValuesTest",
    "OrchestrationTest",
    "PublicChartTest",
    "PublicGatewayValuesTest",
    "RetainedAuthSecretTest",
    "SceneApiAppleKeyTest",
    "SettingsTest",
    "TeardownEntryPointTest",
    "ValidateAuthSecretTest",
    "ValidateEncryptionKeyTest",
    "WorkflowSecurityTest",
]

if __name__ == "__main__":
    unittest.main()
