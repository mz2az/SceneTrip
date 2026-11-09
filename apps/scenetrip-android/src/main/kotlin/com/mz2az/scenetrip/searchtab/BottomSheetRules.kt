package com.mz2az.scenetrip.searchtab

/**
 * [BottomSheet] 의 단 높이 계산 — 떠 있는 탭바 뒤까지 내려가는 서랍을 위해 떼어 냈다 (MZ2AZ-547 T3, iOS `BottomSheetRules.swift`).
 * 값은 dp(Float).
 */
object BottomSheetRules {
    /** 최대 단의 높이 — 위 검색바(`topInset`) 바로 아래까지. 화면이 작아도 80 은 남긴다. */
    fun maxHeight(
        total: Float,
        topInset: Float,
    ): Float = maxOf(80f, total - topInset)

    /**
     * 한 단의 높이(dp).
     *
     * - `bottomClearance`: 화면 바닥에서 떠 있는 탭바가 차지하는 높이(알약 몫 + 내비게이션 막대). 서랍은 그 뒤까지 내려가지만
     *   접힘·중간 단은 **그 위에 보이는 몫**이 예전과 같은 비율이 되게 잰다 — `clearance + (total − ratioInset − clearance) × fraction`.
     *   0 이면 예전 계산(`(total − ratioInset) × fraction`) 그대로다.
     * - `ratioInset`: 비율의 바탕에서 뺄 위쪽 높이(상태줄, Android 만 있는 값). 기본 0.
     * - 최대 단은 [maxHeight] 그대로다.
     */
    fun height(
        fraction: Float,
        isExpanded: Boolean,
        total: Float,
        topInset: Float,
        bottomClearance: Float,
        ratioInset: Float = 0f,
    ): Float {
        if (isExpanded) return maxHeight(total, topInset)
        return bottomClearance + maxOf(0f, total - ratioInset - bottomClearance) * fraction
    }
}
