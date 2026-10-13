package com.mz2az.scenetrip.routetab

enum class VisitStatus { PENDING, FAILED, SAVED }

data class VisitKey(
    val courseId: Long,
    val itemId: Long,
)

/** 방문 저장 결과와 화면 완료 상태를 분리하는 흐름 (MZ2AZ-225). */
class VisitSync(
    private val persist: suspend (Long, Long) -> Result<Unit>,
    private val epoch: () -> Int = { 0 },
    private val changed: (Map<VisitKey, VisitStatus>) -> Unit = {},
) {
    var states: Map<VisitKey, VisitStatus> = emptyMap()
        private set
    private var generation = 0

    suspend fun save(
        courseId: Long,
        itemId: Long,
    ): Boolean {
        val key = VisitKey(courseId, itemId)
        if (states[key] == VisitStatus.PENDING) return false
        if (states[key] == VisitStatus.SAVED) return true
        val before = epoch()
        val revision = generation
        states = states + (key to VisitStatus.PENDING)
        changed(states)
        val result =
            try {
                persist(courseId, itemId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (before == epoch() && revision == generation) {
                    states = states + (key to VisitStatus.FAILED)
                    changed(states)
                }
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        if (before != epoch() || revision != generation) return false
        states = states + (key to if (result.isSuccess) VisitStatus.SAVED else VisitStatus.FAILED)
        changed(states)
        return result.isSuccess
    }

    fun reset() {
        generation++
        states = emptyMap()
        changed(states)
    }
}
