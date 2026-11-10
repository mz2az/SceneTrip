package com.mz2az.scenetrip.routetab

// 지도를 고른 곳·코스 장소로 옮기는 움직임 (MZ2AZ-547, iOS `RouteTab/MapCameraMotionRules.swift`,
// 계획 docs/project/plans/motion-works-drawer-parity.md §10-3).
// 가까우면 부드럽게, 아주 멀면 한 번에 — 2026-11-10 사용자 지적: 「너무 정신없이 휙휙 간다」.
//
// 지금은 시험을 먼저 쓰려고 둔 자리표시(일부러 틀린 값)다 — 구현은 다음 커밋에서.

/** 카메라를 옮기는 방법. */
sealed interface MapCameraMotion {
    /** [durationMillis] 동안 감속 곡선(`easeOut`)으로. */
    data class Smooth(
        val durationMillis: Long,
    ) : MapCameraMotion

    /** 움직임 없이 한 번에. */
    data object Jump : MapCameraMotion
}

object MapCameraMotionRules {
    /** 부드럽게 옮길 최대 거리 — 지금 화면 너비의 이 배수까지. */
    const val SMOOTH_WITHIN_SCREENS: Double = 0.0

    /** 부드럽게 옮기는 시간(ms). */
    const val SMOOTH_DURATION_MILLIS: Long = 0L

    /** 지금 화면 가운데에서 가려는 곳까지 [distanceMeters], 지금 화면 너비가 [visibleWidthMeters] 일 때의 움직임. */
    @Suppress("UNUSED_PARAMETER")
    fun motion(
        distanceMeters: Double,
        visibleWidthMeters: Double,
    ): MapCameraMotion = MapCameraMotion.Jump
}
