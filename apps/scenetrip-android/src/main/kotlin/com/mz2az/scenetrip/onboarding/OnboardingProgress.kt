package com.mz2az.scenetrip.onboarding

/** 사용법의 페이지 범위와 한 번만 완료하는 규칙. */
class OnboardingProgress(
    val count: Int,
) {
    init {
        require(count > 0)
    }

    var page: Int = 0
        private set
    private var finished = false
    val isLast: Boolean get() = page == count - 1

    fun select(value: Int) {
        if (!finished) page = value.coerceIn(0, count - 1)
    }

    fun next() = select(page + 1)

    fun previous() = select(page - 1)

    fun finish(): Boolean {
        if (finished) return false
        finished = true
        return true
    }
}
