package com.mz2az.scenetrip

import com.mz2az.scenetrip.searchtab.MapInsetMode
import com.mz2az.scenetrip.searchtab.MapInsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MapInsetsTests {
    @Test fun routeUsesTheActualSixtyPercentPanelWhileSearchKeepsItsCap() {
        assertEquals(600, MapInsets.cameraBottom(1000, 600, 0, 120, MapInsetMode.ROUTE))
        assertEquals(480, MapInsets.cameraBottom(1000, 600, 0, 120, MapInsetMode.SEARCH))
    }

    @Test fun expandedRoutePanelStillLeavesRoomForFitPaddingAndPins() {
        assertEquals(830, MapInsets.cameraBottom(1000, 999, 50, 120, MapInsetMode.ROUTE))
        assertEquals(600, MapInsets.cameraBottom(800, 600, 80, 120, MapInsetMode.ROUTE))
    }

    @Test fun invalidOrTinyDimensionsNeverProduceNegativeCameraPadding() {
        assertEquals(0, MapInsets.cameraBottom(0, 600, 0, 120, MapInsetMode.ROUTE))
        assertEquals(0, MapInsets.cameraBottom(80, 600, 0, 120, MapInsetMode.ROUTE))
        assertEquals(0, MapInsets.cameraBottom(1000, -10, 0, 120, MapInsetMode.SEARCH))
        assertEquals(0, MapInsets.cameraBottom(1000, 600, 1200, 120, MapInsetMode.ROUTE))
    }

    @Test fun densityScalingPreservesTheSameVisibleRegion() {
        assertEquals(1800, MapInsets.cameraBottom(3000, 1800, 0, 360, MapInsetMode.ROUTE))
        assertEquals(1440, MapInsets.cameraBottom(3000, 1800, 0, 360, MapInsetMode.SEARCH))
    }
}
