package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.MapCameraMotion
import com.mz2az.scenetrip.routetab.MapCameraMotionRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 지도를 고른 곳·코스 장소로 옮기는 움직임 — 지금 화면 가운데에서 그 곳까지가 화면 너비의 3배 이하면
 * 0.6초 감속 곡선으로 부드럽게, 그보다 멀면 한 번에(계획 docs/project/plans/motion-works-drawer-parity.md §10-3).
 * iOS `MapCameraMotionRulesTests` 를 옮겼다. 시간은 ms 다.
 */
class MapCameraMotionRulesTests {
    /** 지금 화면 너비 1km 로 잡는다 — 3배는 3km. */
    private val width = 1000.0

    private val smooth = MapCameraMotion.Smooth(durationMillis = 600L)

    @Test
    fun constants() {
        assertEquals(3.0, MapCameraMotionRules.SMOOTH_WITHIN_SCREENS)
        assertEquals(600L, MapCameraMotionRules.SMOOTH_DURATION_MILLIS)
    }

    /** 화면 너비 3배 안 — 0.6초 동안 부드럽게. */
    @Test
    fun withinThreeScreensIsSmooth() {
        assertEquals(smooth, MapCameraMotionRules.motion(distanceMeters = 1500.0, visibleWidthMeters = width))
        assertEquals(smooth, MapCameraMotionRules.motion(distanceMeters = 200.0, visibleWidthMeters = width))
    }

    /** 딱 3배(「3배 이하」)도 부드럽게. */
    @Test
    fun exactlyThreeScreensIsSmooth() {
        assertEquals(smooth, MapCameraMotionRules.motion(distanceMeters = 3000.0, visibleWidthMeters = width))
    }

    /** 3배를 넘으면 움직임 없이 한 번에. */
    @Test
    fun beyondThreeScreensJumps() {
        assertEquals(MapCameraMotion.Jump, MapCameraMotionRules.motion(distanceMeters = 3001.0, visibleWidthMeters = width))
        assertEquals(MapCameraMotion.Jump, MapCameraMotionRules.motion(distanceMeters = 50_000.0, visibleWidthMeters = width))
    }

    /** 기준은 화면 너비에 따라 바뀐다 — 같은 5km 도 넓게 보는 화면(2km)에선 부드럽게, 좁게 보는 화면(1km)에선 한 번에. */
    @Test
    fun limitScalesWithVisibleWidth() {
        assertEquals(smooth, MapCameraMotionRules.motion(distanceMeters = 5000.0, visibleWidthMeters = 2000.0))
        assertEquals(MapCameraMotion.Jump, MapCameraMotionRules.motion(distanceMeters = 5000.0, visibleWidthMeters = 1000.0))
    }

    /** 이미 그 자리(거리 0)면 3배 이하이므로 부드럽게. */
    @Test
    fun zeroDistanceIsSmooth() {
        assertEquals(smooth, MapCameraMotionRules.motion(distanceMeters = 0.0, visibleWidthMeters = width))
    }

    /** 부드럽게 옮길 때의 시간은 [MapCameraMotionRules.SMOOTH_DURATION_MILLIS] 와 같다. */
    @Test
    fun smoothUsesDurationConstant() {
        val motion = MapCameraMotionRules.motion(distanceMeters = 1000.0, visibleWidthMeters = width)
        assertTrue(motion is MapCameraMotion.Smooth, "화면 너비 1배 거리는 부드럽게 옮겨야 한다")
        val duration = (motion as MapCameraMotion.Smooth).durationMillis
        assertEquals(MapCameraMotionRules.SMOOTH_DURATION_MILLIS, duration)
        assertEquals(600L, duration)
    }
}
