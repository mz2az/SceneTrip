package com.mz2az.scenetrip.routetab

/**
 * 가이드 입구는 언제나 떠 있는 해태 단추 하나 — 가이드 창이 열렸거나 핀을 찍는 동안만 숨긴다(iOS `RouteGuideEntry`).
 * 길 안내 중 안내 줄에 얼굴만 붙이던 자리(MZ2AZ-387)는 없앴다 — 단추가 사라지고 그 얼굴은 iOS 에서 눌리지 않았다(2026-11-11, 계획 §12).
 * 안내 중인지·목적지·카드는 자리를 바꾸지 않지만 부르는 쪽 모양을 지키려 받는다.
 */
enum class RouteGuideEntry {
    FLOATING,
    HIDDEN,
    ;

    companion object {
        @Suppress("UNUSED_PARAMETER")
        fun placement(
            guiding: Boolean,
            hasTarget: Boolean,
            hasCard: Boolean,
            panelOpen: Boolean,
            pinning: Boolean,
        ): RouteGuideEntry = if (panelOpen || pinning) HIDDEN else FLOATING
    }
}
