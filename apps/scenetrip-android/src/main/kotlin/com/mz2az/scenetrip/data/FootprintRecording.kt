package com.mz2az.scenetrip.data

data class FootprintState(
    val points: List<FootprintPoint> = emptyList(),
    val recording: Boolean = false,
)

/** 기록 동의와 계정 이탈 시 삭제를 저장 매체와 무관하게 적용한다 (MZ2AZ-348). */
class FootprintRecording(
    points: List<FootprintPoint> = emptyList(),
    recording: Boolean = false,
    private val publish: (FootprintState) -> Unit = {},
) {
    var state = FootprintState(points.toList(), recording)
        private set

    fun consent(value: Boolean) = update(state.copy(recording = value))

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

    fun forgetOwner() = update(FootprintState())

    private fun update(value: FootprintState) {
        state = value
        publish(value)
    }

    companion object {
        private const val MIN_STEP_METERS = 25.0
    }
}
