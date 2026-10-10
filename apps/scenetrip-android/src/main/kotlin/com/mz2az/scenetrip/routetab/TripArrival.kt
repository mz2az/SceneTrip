package com.mz2az.scenetrip.routetab

/** GPS가 반경 안에 연속 5분 머물러야 도착한다. 수동 확인은 이 판정을 우회한다. */
class TripArrival {
    private var enteredAt: Long? = null

    fun reset() {
        enteredAt = null
    }

    fun update(
        meters: Double,
        now: Long,
    ): Boolean {
        if (!meters.isFinite() || meters < 0 || meters > RADIUS_METERS) {
            reset()
            return false
        }
        val entered = enteredAt ?: now.also { enteredAt = it }
        return now - entered >= DWELL_MILLIS
    }

    companion object {
        const val RADIUS_METERS = 100.0
        const val DWELL_MILLIS = 300_000L
    }
}
