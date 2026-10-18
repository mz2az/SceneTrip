"""Bazel 단위 테스트 진입점."""

import unittest

from tools.aws.tests.test_alb import AlbBoundaryTest, AlbChartTest, SignozAlbTest
from tools.aws.tests.test_alerts import (
    AlertsCliTest,
    AlertsFlowTest,
    AlertsRecipeTest,
    AlertsTemplateTest,
)
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
    TripGuideSecretTest,
    ValidateAuthSecretTest,
)
from tools.aws.tests.test_bedrock_role import (
    TripGuideChartTest,
    TripGuideSecretValidationTest,
)
from tools.aws.tests.test_bootstrap_delete import BootstrapDeleteTest
from tools.aws.tests.test_bootstrap_state import BootstrapStateTest
from tools.aws.tests.test_boundaries import BoundaryTest
from tools.aws.tests.test_commands import CliTest, FirstDeployEmptyStateTest
from tools.aws.tests.test_data_load import (
    DataLoadEntryPointTest,
    DataLoadJobTest,
    DataLoadNetworkPolicyTest,
    DataLoadPollingTest,
    DataLoadPublishTest,
    DataLoadRunTest,
    RemoteLoadScriptTest,
)
from tools.aws.tests.test_deploy import DeployTest, OrchestrationTest, SettingsTest
from tools.aws.tests.test_destroy import (
    DestroyServiceTest,
    ReleaseDetachedNatAddressesTest,
)
from tools.aws.tests.test_destroy_gateway import (
    DestroyGatewayTest,
    SignozGatewayTeardownTest,
)
from tools.aws.tests.test_destroy_plan import DestroyPlanTest
from tools.aws.tests.test_entrypoint import EntryPointTest
from tools.aws.tests.test_gateway_limits import GatewayLimitsChartTest
from tools.aws.tests.test_gateway_routes import GatewayContractRoutesTest
from tools.aws.tests.test_lifecycle import CloudflareTest, LifecycleTest, SignozDnsTest
from tools.aws.tests.test_loadtest_mode import LoadtestModeChartTest
from tools.aws.tests.test_media import MediaChartTest, MediaDeployValuesTest
from tools.aws.tests.test_node_packing import (
    SceneTripSpreadChartTest,
    SignozResourcesChartTest,
)
from tools.aws.tests.test_ops import (
    DriftTest,
    PruneAfterDestroyTest,
    RunnerSelectionTest,
    SharedMediaFlowTest,
    SharedMediaTemplateTest,
    SnapshotPruneTest,
)
from tools.aws.tests.test_prd_protection import (
    PrdTeardownProtectionTest,
    PrdWorkflowProtectionTest,
)
from tools.aws.tests.test_public_ingress import (
    PublicChartTest,
    PublicGatewayValuesTest,
)
from tools.aws.tests.test_signoz import (
    ClickhousePasswordTest,
    ExposeSignozTest,
    RemoveSignozTest,
    SignozConfigTest,
    SignozDeployTest,
    SignozExposeWiringTest,
    SignozIngressTest,
    SignozPublishTest,
    SignozRootPasswordTest,
    SignozTeardownTest,
    SignozTraceVerifyTest,
    SignozUiConfigTest,
    TripGuideTraceChartTest,
)
from tools.aws.tests.test_teardown_entrypoint import TeardownEntryPointTest
from tools.aws.tests.test_workflows import WorkflowSecurityTest

__all__ = [
    "AlbBoundaryTest",
    "AlbChartTest",
    "AlertsCliTest",
    "AlertsFlowTest",
    "AlertsRecipeTest",
    "AlertsTemplateTest",
    "AuthSecretTest",
    "BootstrapDeleteTest",
    "BootstrapStateTest",
    "BoundaryTest",
    "CliTest",
    "ClickhousePasswordTest",
    "CloudflareTest",
    "DataLoadEntryPointTest",
    "DataLoadJobTest",
    "DataLoadNetworkPolicyTest",
    "DataLoadPollingTest",
    "DataLoadPublishTest",
    "DataLoadRunTest",
    "DatabaseCredentialsGeneratedOnceTest",
    "DeployAppleWiringTest",
    "DeploySecretWiringTest",
    "DeployTest",
    "DestroyGatewayTest",
    "DestroyIdentityAuthTest",
    "DestroyPlanTest",
    "DestroyServiceTest",
    "DriftTest",
    "EntryPointTest",
    "ExposeSignozTest",
    "FirstDeployEmptyStateTest",
    "GatewayContractRoutesTest",
    "GatewayLimitsChartTest",
    "LegacyAuthSlotTest",
    "LifecycleTest",
    "LoadtestModeChartTest",
    "MediaChartTest",
    "MediaDeployValuesTest",
    "OrchestrationTest",
    "PrdTeardownProtectionTest",
    "PrdWorkflowProtectionTest",
    "PruneAfterDestroyTest",
    "PublicChartTest",
    "PublicGatewayValuesTest",
    "ReleaseDetachedNatAddressesTest",
    "RemoteLoadScriptTest",
    "RemoveSignozTest",
    "RetainedAuthSecretTest",
    "RunnerSelectionTest",
    "SceneApiAppleKeyTest",
    "SceneTripSpreadChartTest",
    "SettingsTest",
    "SharedMediaFlowTest",
    "SharedMediaTemplateTest",
    "SignozAlbTest",
    "SignozConfigTest",
    "SignozDeployTest",
    "SignozDnsTest",
    "SignozExposeWiringTest",
    "SignozGatewayTeardownTest",
    "SignozIngressTest",
    "SignozPublishTest",
    "SignozResourcesChartTest",
    "SignozRootPasswordTest",
    "SignozTeardownTest",
    "SignozTraceVerifyTest",
    "SignozUiConfigTest",
    "SnapshotPruneTest",
    "TeardownEntryPointTest",
    "TripGuideChartTest",
    "TripGuideSecretTest",
    "TripGuideSecretValidationTest",
    "TripGuideTraceChartTest",
    "ValidateAuthSecretTest",
    "ValidateEncryptionKeyTest",
    "WorkflowSecurityTest",
]

if __name__ == "__main__":
    unittest.main()
