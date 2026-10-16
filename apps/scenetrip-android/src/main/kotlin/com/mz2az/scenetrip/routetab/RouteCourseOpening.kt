package com.mz2az.scenetrip.routetab

/** [RouteCourseOpening]이 화면에 보이는 값. Compose 상태로 감싸 읽는 쪽이 [RouteCourseOpening]의 `changed`로 받는다. */
data class RouteOpeningState(
    /** 편집 화면에 올릴 코스. 상세를 받은 사본만 들어온다. */
    val editing: RouteCourse? = null,
    /** 마지막으로 열기에 실패한 코스. 「다시 시도」가 이것을 다시 연다. */
    val failedCourse: RouteCourse? = null,
    val isLoading: Boolean = false,
)

/**
 * 목록 요약은 편집하지 않는다. **상세 조회에 성공한 코스만** 편집 화면으로 넘긴다. iOS
 * `RouteTab/RouteCourseOpening.swift`를 옮긴 것이다.
 *
 * 상세를 못 받았을 때 요약(일차 속이 빈 코스)으로 편집 화면을 열면, 사용자가 「저장」을 눌러 서버 코스를
 * 빈 일정으로 덮어쓸 수 있다. 그래서 실패하면 편집 화면은 열지 않고 [RouteOpeningState.failedCourse]에
 * 남겨 목록이 「코스를 불러오지 못했어요」와 「다시 시도」를 보이게 한다.
 *
 * Compose 상태를 직접 들지 않는다(JVM 단위 시험에서 Looper 가 없다) — 값이 바뀔 때마다 [changed]로 알린다.
 */
class RouteCourseOpening(
    private val changed: (RouteOpeningState) -> Unit = {},
) {
    var state = RouteOpeningState()
        private set

    val editing: RouteCourse? get() = state.editing
    val failedCourse: RouteCourse? get() = state.failedCourse
    val isLoading: Boolean get() = state.isLoading

    private fun publish(value: RouteOpeningState) {
        state = value
        changed(value)
    }

    /** [detail]은 `RouteStore.routeCourse`다 — 실패하면 null. 같은 열기가 진행 중이면 무시한다. */
    suspend fun open(
        course: RouteCourse,
        detail: suspend (RouteCourse) -> RouteCourse?,
    ) {
        if (isLoading) return
        publish(RouteOpeningState(isLoading = true))
        var next = RouteOpeningState()
        try {
            val loaded = detail(course)
            // 서버 코스인데 상세로 만든 사본이 아니면(요약을 그대로 돌려준 경우) 저장이 막히므로 열지 않는다.
            next = if (loaded == null || !loaded.canSave) RouteOpeningState(failedCourse = course) else RouteOpeningState(editing = loaded)
        } finally {
            publish(next)
        }
    }

    suspend fun retry(detail: suspend (RouteCourse) -> RouteCourse?) {
        val course = failedCourse ?: return
        open(course, detail)
    }

    /** 편집 화면이 닫혔다. */
    fun close() {
        publish(state.copy(editing = null))
    }

    /** 상세 조회가 필요 없는 코스(마법사가 방금 만든 초안·저장 결과)를 바로 편집 화면에 올린다. */
    fun show(course: RouteCourse) {
        publish(RouteOpeningState(editing = course))
    }
}
