package com.mz2az.scenetrip.routetab

/**
 * 해태 칩이 바닥 카드와 단추를 비켜 서는 자리 계산 (MZ2AZ-367·368, iOS `RouteGuideFloatingChip.offsetX/offsetY`). 값은 모두 px 이고
 * 기본 자리(오른쪽 아래)에서 잰 이동이다 — 가로는 왼쪽이 음수, 세로는 위쪽이 음수.
 */
object GuideChipPlacement {
    /** 덮으면 안 되는 단추의 자리 — 칩과 같은 잣대(오른쪽 가장자리·바닥에서 잰 거리)로. */
    data class Clearance(
        /** 단추 왼쪽 변이 오른쪽 가장자리에서 얼마나 떨어져 있나. */
        val left: Float,
        /** 단추 아랫변·윗변의 높이(바닥에서). */
        val low: Float,
        val high: Float,
    )

    /**
     * 카드가 떠 있으면 그 윗변보다 [gap] 만큼 위에 선다. **사용자가 이미 더 위로 옮겨 뒀으면 그대로** — 비켜 둔 자리를 다시 옮기지 않는다.
     * 카드가 닫히면 제자리로 돌아온다(저장된 자리는 안 바꾼다).
     */
    fun offsetY(
        stored: Float,
        cardTop: Float?,
        bottom: Float,
        gap: Float,
    ): Float = if (cardTop == null) stored else minOf(stored, -(cardTop + gap - bottom))

    /**
     * 카드를 비켜 올라선 자리가 단추와 겹치면 **그 단추 왼쪽으로** [gap] 만큼 더 비킨다. 위로 더 올리지 않는다 — 그 위는 일차 ＋/− 단추다.
     * 카드가 없거나, 사용자가 스스로 카드보다 위에 둔 자리는 건드리지 않는다.
     */
    fun offsetX(
        storedX: Float,
        storedY: Float,
        cardTop: Float?,
        clear: Clearance?,
        bottom: Float,
        edge: Float,
        side: Float,
        gap: Float,
    ): Float {
        if (cardTop == null || clear == null) return storedX
        // 우리가 올린 것이 아니면(이미 더 위) 손대지 않는다.
        if (storedY <= -(cardTop + gap - bottom)) return storedX
        // 올라선 동그라미(접혀서 [side] 정사각)의 아랫변 높이는 카드 윗변 + gap 이다.
        val foot = cardTop + gap
        val overlapsY = foot < clear.high && foot + side > clear.low
        // 단추는 오른쪽 끝까지 가므로, 동그라미 오른쪽 변이 단추 왼쪽 변보다 오른쪽이면 겹친다.
        val overlapsX = edge - storedX < clear.left
        if (!overlapsY || !overlapsX) return storedX
        return -(clear.left + gap - edge)
    }
}
