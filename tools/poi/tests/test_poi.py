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
from tools.poi.tests.test_names_brands import (
    BrandCandidates,
    BrandSourceRequired,
    BrandSourceShipped,
)
from tools.poi.tests.test_names_final import (
    FinalAddedSpellings,
    FinalEmptyEnglishIsNotBrand,
    FinalNonBranchWords,
    FinalNumberedBranch,
    FinalSharedStore,
)
from tools.poi.tests.test_names_regression import (
    RegressionBrandBareJeom,
    RegressionSeparatorSpacing,
)
from tools.poi.tests.test_names_regression2 import (
    Regression2BrandRest,
    Regression2IntendedBehaviour,
)
from tools.poi.tests.test_names_spec import (
    SpecBrandBoundary,
    SpecEnglishName,
    SpecNoDishTranslation,
    SpecRomanizeExamples,
    SpecRomanizeStandard,
)
from tools.poi.tests.test_pack import PackFile, PackImages, PackMain, PackSlim
from tools.poi.tests.test_spec import (
    SpecAccept,
    SpecCutToBuildingNumber,
    SpecFormatEnglish,
    SpecJibunRefs,
    SpecJusoApiCache,
    SpecRoadRefs,
    SpecStoreKeys,
)
from tools.poi.tests.test_tour_images import (
    TourImagesCredit,
    TourImagesHttps,
    TourImagesMain,
    TourImagesRow,
)
from tools.poi.tests.test_tourapi import (
    TourConstants,
    TourFetchOne,
    TourItemsOf,
    TourKeyRing,
    TourMain,
    TourOrder,
    TourRun,
)

__all__ = [
    "Accept",
    "BrandCandidates",
    "BrandSourceRequired",
    "BrandSourceShipped",
    "Cache",
    "CutToBuildingNumber",
    "FinalAddedSpellings",
    "FinalEmptyEnglishIsNotBrand",
    "FinalNonBranchWords",
    "FinalNumberedBranch",
    "FinalSharedStore",
    "FormatEnglish",
    "JibunRefs",
    "PackFile",
    "PackImages",
    "PackMain",
    "PackSlim",
    "Regression2BrandRest",
    "Regression2IntendedBehaviour",
    "RegressionBrandBareJeom",
    "RegressionSeparatorSpacing",
    "RoadRefs",
    "SpecAccept",
    "SpecBrandBoundary",
    "SpecCutToBuildingNumber",
    "SpecEnglishName",
    "SpecFormatEnglish",
    "SpecJibunRefs",
    "SpecJusoApiCache",
    "SpecNoDishTranslation",
    "SpecRoadRefs",
    "SpecRomanizeExamples",
    "SpecRomanizeStandard",
    "SpecStoreKeys",
    "StoreKeys",
    "TourConstants",
    "TourFetchOne",
    "TourImagesCredit",
    "TourImagesHttps",
    "TourImagesMain",
    "TourImagesRow",
    "TourItemsOf",
    "TourKeyRing",
    "TourMain",
    "TourOrder",
    "TourRun",
]

if __name__ == "__main__":
    unittest.main()
