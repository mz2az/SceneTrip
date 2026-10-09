package com.mz2az.scenetrip.routetab

/** 가이드 입구를 한 곳에만 둔다. iOS MZ2AZ-387의 안내 배너·카드 전환 규칙. */
enum class RouteGuideEntry {
    FLOATING,
    TRIP_HEADER,
    HIDDEN,
    ;

    companion object {
        fun placement(
            guiding: Boolean,
            hasTarget: Boolean,
            hasCard: Boolean,
            panelOpen: Boolean,
            pinning: Boolean,
        ): RouteGuideEntry =
            when {
                panelOpen || pinning -> HIDDEN
                hasCard -> FLOATING
                guiding && hasTarget -> TRIP_HEADER
                else -> FLOATING
            }
    }
}
