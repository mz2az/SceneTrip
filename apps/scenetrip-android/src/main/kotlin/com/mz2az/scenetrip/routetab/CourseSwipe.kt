package com.mz2az.scenetrip.routetab

/** 손을 뗐을 때 줄이 멈추는 곳. */
enum class SwipeSettle {
    /** 제자리로 돌아간다. */
    CLOSE,

    /** 삭제 단추가 드러난 채 멈춘다. */
    OPEN,

    /** 끝까지 밀었다 — 단추를 안 눌러도 첫 동작을 바로 부르고 줄은 제자리로 돌아간다(iOS `allowsFullSwipe`). */
    FULL,
}

/**
 * 코스 목록 줄의 밀기 규칙. iOS `.swipeActions` 처럼 **줄을 밀면 단추가 먼저 드러나고**, 단추를 눌러야 동작한다.
 * 드러난 폭이 단추 영역의 절반을 넘으면 열린 채 멈추고, 줄 폭의 절반을 넘게 끝까지 밀면 바로 동작한다.
 */
object CourseSwipe {
    fun settle(
        revealed: Float,
        reveal: Float,
        full: Float,
    ): SwipeSettle =
        when {
            full > 0f && revealed > full * 0.5f -> SwipeSettle.FULL
            revealed > reveal * 0.5f -> SwipeSettle.OPEN
            else -> SwipeSettle.CLOSE
        }
}
