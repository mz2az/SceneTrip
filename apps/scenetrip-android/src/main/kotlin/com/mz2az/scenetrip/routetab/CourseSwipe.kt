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

    /** 이 줄을 밀기 시작했거나 열렸다 — 열린 줄은 하나뿐이라 앞 줄은 닫힌다(iOS 는 한 줄만 열려 있다). */
    fun claim(
        openId: Long?,
        id: Long,
    ): Long = id

    /** 이 줄이 닫혔다(제자리·단추·탭·확인 창으로 넘어감). 다른 줄이 이미 열려 있으면 그것을 건드리지 않는다. */
    fun release(
        openId: Long?,
        id: Long,
    ): Long? = if (openId == id) null else openId

    /**
     * 줄 아래 구분선을 그리는가. 마지막 줄 아래에는 없고, **열린 줄 아래에는 숨긴다** — 열린 줄은 알약으로 줄어들어
     * 선이 전체 폭으로 남으면 휴지통 알약 아래까지 이어진다. (iOS 에서 줄이 둘 이상일 때의 모양은 참조 스크린샷에 없어
     * 가장 작은 변경으로 이렇게 했다 — iOS 근거 없음.)
     */
    fun showsDivider(
        openId: Long?,
        id: Long,
        isLast: Boolean,
    ): Boolean = !isLast && openId != id
}
