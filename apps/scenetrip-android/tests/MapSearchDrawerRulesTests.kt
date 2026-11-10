package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.MapSearchDrawerRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 지도 검색 결과 서랍 끌기 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §3).
 * 지도 높이 1000dp 로 잰다 — 낮은 서랍 420, 높은 서랍 780. 속도는 위(커지는 쪽)가 +.
 */
class MapSearchDrawerRulesTests {
    private val map = 1000f
    private val eps = 0.001f

    @Test
    fun heightsAreFortyTwoAndSeventyEightPercent() {
        assertEquals(420f, MapSearchDrawerRules.height(map, tall = false), eps)
        assertEquals(780f, MapSearchDrawerRules.height(map, tall = true), eps)
        assertEquals(336f, MapSearchDrawerRules.height(800f, tall = false), eps)
    }

    @Test
    fun snapMillisIsTwoEighty() {
        assertEquals(280, MapSearchDrawerRules.SNAP_MILLIS)
    }

    // ---- 손을 뗐을 때 — 느리면 가까운 쪽 ----

    @Test
    fun slowReleaseSnapsToNearest() {
        assertFalse(MapSearchDrawerRules.settle(map, height = 500f, velocity = 0f, wasTall = true))
        assertTrue(MapSearchDrawerRules.settle(map, height = 700f, velocity = 0f, wasTall = false))
        assertTrue(MapSearchDrawerRules.settle(map, height = 610f, velocity = 0f, wasTall = false))
        assertFalse(MapSearchDrawerRules.settle(map, height = 590f, velocity = 0f, wasTall = true))
    }

    @Test
    fun exactMiddleKeepsCurrentSide() {
        assertTrue(MapSearchDrawerRules.settle(map, height = 600f, velocity = 0f, wasTall = true))
        assertFalse(MapSearchDrawerRules.settle(map, height = 600f, velocity = 0f, wasTall = false))
    }

    @Test
    fun slowerThanThresholdStillSnapsToNearest() {
        assertFalse(MapSearchDrawerRules.settle(map, height = 430f, velocity = 599f, wasTall = true))
        assertTrue(MapSearchDrawerRules.settle(map, height = 770f, velocity = -599f, wasTall = false))
    }

    @Test
    fun releaseBeyondLimitsSnapsBack() {
        assertTrue(MapSearchDrawerRules.settle(map, height = 816f, velocity = 0f, wasTall = false))
        assertFalse(MapSearchDrawerRules.settle(map, height = 384f, velocity = 0f, wasTall = true))
    }

    // ---- 빠르게 튕기면 그 방향 ----

    @Test
    fun fastFlickUpGoesTall() {
        assertEquals(600f, MapSearchDrawerRules.FLICK_DP_PER_SECOND, eps)
        // 낮은 서랍 바로 위에서 위로 튕김 — 가까운 쪽은 낮음이지만 높음으로.
        assertTrue(MapSearchDrawerRules.settle(map, height = 430f, velocity = 600f, wasTall = false))
        assertTrue(MapSearchDrawerRules.settle(map, height = 430f, velocity = 2_000f, wasTall = false))
    }

    @Test
    fun fastFlickDownGoesShort() {
        assertFalse(MapSearchDrawerRules.settle(map, height = 770f, velocity = -600f, wasTall = true))
        assertFalse(MapSearchDrawerRules.settle(map, height = 770f, velocity = -2_000f, wasTall = true))
    }

    // ---- 끄는 동안 — 고무줄 ----

    @Test
    fun dragFollowsFingerInsideLimits() {
        assertEquals(600f, MapSearchDrawerRules.dragged(map, 600f), eps)
        assertEquals(420f, MapSearchDrawerRules.dragged(map, 420f), eps)
        assertEquals(780f, MapSearchDrawerRules.dragged(map, 780f), eps)
        // 밖으로 넘으면 늘어나는 것이 줄어든다.
        assertEquals(816f, MapSearchDrawerRules.dragged(map, 900f), eps)
    }

    @Test
    fun rubberBandAboveTop() {
        // 780 + (900 − 780) × 0.3
        assertEquals(816f, MapSearchDrawerRules.dragged(map, 900f), eps)
        val far = MapSearchDrawerRules.dragged(map, 1_000f)
        assertTrue(far > 780f && far < 1_000f)
    }

    @Test
    fun rubberBandBelowBottom() {
        // 420 − (420 − 300) × 0.3
        assertEquals(384f, MapSearchDrawerRules.dragged(map, 300f), eps)
        val far = MapSearchDrawerRules.dragged(map, 0f)
        assertTrue(far < 420f && far > 0f)
    }

    // ---- 손잡이 누르기 ----

    @Test
    fun tapTogglesBetweenHeights() {
        assertTrue(MapSearchDrawerRules.toggled(false))
        assertFalse(MapSearchDrawerRules.toggled(true))
    }
}
