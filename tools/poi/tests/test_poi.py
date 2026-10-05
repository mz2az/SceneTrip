"""Bazel 단위 테스트 진입점."""

import unittest

from tools.poi.tests.test_addresses import (
    CutToBuildingNumber,
    FormatEnglish,
    JibunRefs,
    RoadRefs,
    StoreKeys,
)
from tools.poi.tests.test_juso_api import Accept, Cache
from tools.poi.tests.test_spec import (
    SpecAccept,
    SpecCutToBuildingNumber,
    SpecFormatEnglish,
    SpecJibunRefs,
    SpecJusoApiCache,
    SpecRoadRefs,
    SpecStoreKeys,
)

__all__ = [
    "Accept",
    "Cache",
    "CutToBuildingNumber",
    "FormatEnglish",
    "JibunRefs",
    "RoadRefs",
    "SpecAccept",
    "SpecCutToBuildingNumber",
    "SpecFormatEnglish",
    "SpecJibunRefs",
    "SpecJusoApiCache",
    "SpecRoadRefs",
    "SpecStoreKeys",
    "StoreKeys",
]

if __name__ == "__main__":
    unittest.main()
