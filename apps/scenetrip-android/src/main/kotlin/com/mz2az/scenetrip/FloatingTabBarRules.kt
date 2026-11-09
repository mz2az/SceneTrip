package com.mz2az.scenetrip

/**
 * 떠 있는 알약 탭바의 치수와 규칙 (MZ2AZ-547 T1–T3, 웹 `features/proto/ProtoTabBar.tsx`, iOS `FloatingTabBarRules.swift`).
 *
 * 탭 셋이 **같은 폭**이고 고른 탭 뒤에 보라 알약이 미끄러진다. 탭 내용은 알약 뒤까지 내려가 비치고,
 * 목록은 바닥을 [CLEARANCE] 만큼(+기기 내비게이션 막대) 비운다. 값은 dp(Float) — Compose 형을 쓰지 않아 JVM 시험이 선다.
 */
object FloatingTabBarRules {
    /** 화면 좌우 여백. */
    const val SIDE_MARGIN = 16f

    /** 기기 내비게이션 막대 위로 띄우는 높이. */
    const val BOTTOM_GAP = 8f

    /** 알약 높이. */
    const val HEIGHT = 58f

    /** 알약 안쪽 여백 — 고른 탭 알약이 이만큼 안에 들어앉는다. */
    const val INNER_PADDING = 5f

    /** 목록이 바닥에서 비우는 높이(내비게이션 막대 위). 웹 `--proto-tab-inset` 의 74. */
    const val CLEARANCE = 74f

    /** 고른 탭 알약이 미끄러지는 시간(밀리초). */
    const val SLIDE_MILLIS = 380

    /** 고른 탭 알약의 폭 — 알약 안쪽(좌우 여백을 뺀) 폭을 탭 수로 나눈다. */
    fun selectionWidth(
        barWidth: Float,
        tabCount: Int,
    ): Float = if (tabCount <= 0) 0f else maxOf(0f, barWidth - INNER_PADDING * 2) / tabCount

    /** 고른 탭 알약의 왼쪽 끝 x(알약의 왼쪽 끝 기준). 0번 탭은 안쪽 여백에서 시작한다. */
    fun selectionOffset(
        index: Int,
        barWidth: Float,
        tabCount: Int,
    ): Float = INNER_PADDING + index * selectionWidth(barWidth, tabCount)
}
