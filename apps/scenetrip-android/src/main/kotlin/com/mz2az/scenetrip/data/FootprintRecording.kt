package com.mz2az.scenetrip.data

data class FootprintState(
    val points: List<FootprintPoint> = emptyList(),
    val recording: Boolean = false,
    /** 「지도에 발자취 보기」 — 켜야 여행 지도에 발자취 단추가 나온다. */
    val enabled: Boolean = false,
)

/** 기록 동의와 계정 이탈 시 삭제를 저장 매체와 무관하게 적용한다 (MZ2AZ-348). */
class FootprintRecording(
    points: List<FootprintPoint> = emptyList(),
    recording: Boolean = false,
    enabled: Boolean = false,
    private val publish: (FootprintState) -> Unit = {},
) {
    var state = FootprintState(points.toList(), recording, enabled)
        private set

    fun consent(value: Boolean) = update(state.copy(recording = value))

    fun setEnabled(value: Boolean) = update(state.copy(enabled = value))

    fun record(
        latitude: Double,
        longitude: Double,
        at: Long,
    ) {
        if (!state.recording || !KoreaBounds.contains(latitude, longitude)) return
        state.points.lastOrNull()?.let { last ->
            if (haversineKm(last.latitude, last.longitude, latitude, longitude) * 1000 < MIN_STEP_METERS) return
        }
        update(state.copy(points = state.points + FootprintPoint(latitude, longitude, at)))
    }

    fun clear() = update(state.copy(points = emptyList()))

    /**
     * 계정이 이 기기를 떠났다(로그아웃·탈퇴·세션 소실). 기록을 지우고 기록 동의와 「지도에 발자취 보기」를 끈다 —
     * 다음 사람이 앞사람의 길을 보거나 앞사람의 동의로 기록되면 안 된다 (MZ2AZ-348, iOS `forgetOwner`).
     */
    fun forgetOwner() = update(FootprintState())

    private fun update(value: FootprintState) {
        state = value
        publish(value)
    }

    companion object {
        private const val MIN_STEP_METERS = 25.0
    }
}
