package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.tr
import kotlin.math.ceil

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

    /** 반경 안에 있은 시간(ms). 반경 밖이거나 아직 안 들어왔으면 null — 안내 띠가 「도착까지 N분」을 보이는 데 쓴다. */
    fun dwelt(now: Long): Long? = enteredAt?.let { (now - it).coerceAtLeast(0) }

    companion object {
        const val RADIUS_METERS = 100.0
        const val DWELL_MILLIS = 300_000L

        /**
         * 안내 띠의 머무름 힌트 (MZ2AZ-478, iOS `TripSession.dwellHint`) — 반경 안이면 「머무르면 도착 · N분」, 아니면 규칙 한 줄.
         * [dwelt] 는 반경 안에 있은 시간(ms), 반경 밖이면 null.
         */
        fun hint(dwelt: Long?): String {
            if (dwelt != null) {
                val left = maxOf(0L, ceil((DWELL_MILLIS - dwelt) / 60_000.0).toLong()).toInt()
                return if (left == 0) tr("도착 확인 중") else tr("머무르면 도착 · %d분").format(left)
            }
            return tr("반경 %d m 에 머무르면 스탬프").format(RADIUS_METERS.toInt())
        }
    }
}
