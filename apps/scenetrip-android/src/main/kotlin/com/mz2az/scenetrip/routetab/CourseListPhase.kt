package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.RouteStore

/**
 * 코스 탭이 무엇을 그릴지. **빈 상태는 정말 비었다고 답을 받았을 때만** 그린다 — 아직 못 받았거나(받는 중)
 * 못 받았을 때(실패)는 「아직 만든 코스가 없습니다」 로 속이지 않는다. 홈의 코스 카드가 쓰는 [RouteStore.CourseListState] 와 같은 기준이다.
 */
enum class CourseListPhase {
    LIST,
    LOADING,
    FAILED,
    EMPTY,
    ;

    companion object {
        fun of(
            hasCourses: Boolean,
            state: RouteStore.CourseListState,
            loading: Boolean,
        ): CourseListPhase =
            when {
                hasCourses -> LIST
                state == RouteStore.CourseListState.LOADED -> EMPTY
                loading || state == RouteStore.CourseListState.UNKNOWN -> LOADING
                else -> FAILED
            }
    }
}
