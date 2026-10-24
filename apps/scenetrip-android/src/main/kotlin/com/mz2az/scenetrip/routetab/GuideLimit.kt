package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.UsageQuota
import com.mz2az.scenetrip.data.tr

/**
 * 가이드 챗봇의 **한도 안내와 남은 양** (MZ2AZ-366 C, iOS `GuideLimit`).
 *
 * 서버는 챗봇을 1 시간 창과 하루 창으로 센다. 넘으면 `429 GUIDE_LIMIT_REACHED` — **두 창의 `code` 가 같다.** 가르는 것은
 * `Retry-After`(그 창이 끝날 때까지의 초)의 크기뿐이다. 그래서 「어느 창인가」 를 묻지 않고 「언제 풀리나」 로 말한다 —
 * 1 시간 안에 풀리면 몇 분 뒤인지, 그보다 멀면 내일.
 *
 * 숫자(한도 크기)는 앱에 없다. 서버가 헤더로 준 것만 읽는다 — 요금제 설정이라 바뀐다.
 *
 * 전부 값만 보는 순수 함수다. 시각은 **연속 시계의 밀리초**(`SystemClock.elapsedRealtime`)이고 밖에서 넣는다 — 기기 시계를
 * 돌려도 잠금이 늘거나 줄지 않는다.
 */
object GuideLimit {
    /** `Retry-After`·`RateLimit-Reset` 이 이 초 이하면 「N분 뒤」, 넘으면 「내일」·「오늘」. */
    const val SHORT_WINDOW_SECONDS = 3600

    /** 남은 양 줄은 남은 수가 한도 크기의 이 몫 이하일 때만 보인다 — 넉넉할 때 숫자를 보이면 쓰기를 망설인다. */
    const val LOW_SHARE = 0.2

    /**
     * 한도 안내에 「AI 로 짜기」(일정짜기 마법사) 안내를 붙일지. **끄려면 여기 하나.**
     * 지금 에이전트 설정에서는 마법사도 모델을 부르므로 문구는 무료를 약속하지 않는다(iOS `Tuning.plannerHint`).
     */
    const val PLANNER_HINT = true

    enum class Window {
        /** 한 시간 안에 풀린다. */
        SHORT,

        /** 그보다 멀다. */
        DAY,

        /** 서버가 언제 풀리는지 말하지 않았다(`Retry-After` 없음) — 「내일」 이라고 지어내지 않는다. */
        UNKNOWN,
    }

    fun window(seconds: Int?): Window =
        when {
            seconds == null -> Window.UNKNOWN
            seconds <= SHORT_WINDOW_SECONDS -> Window.SHORT
            else -> Window.DAY
        }

    /** 남은 밀리초를 분으로 — 올림, 적어도 1. 「0분 뒤」 라고 말하지 않는다. */
    fun minutes(leftMillis: Long): Int = maxOf(1, ((leftMillis + 59_999) / 60_000).toInt())

    /** 한도에 걸린 상태 — 429 를 받은 순간에 만든다. */
    class Block(
        retryAfter: Int?,
        now: Long,
    ) {
        val window: Window = window(retryAfter)

        /** 풀리는 때 — 연속 시계의 밀리초. 서버가 `Retry-After` 를 주지 않았으면 모른다(null) — 보내는 것을 막지 않는다. */
        val until: Long? = retryAfter?.let { now + maxOf(0, it) * 1000L }

        override fun equals(other: Any?): Boolean = other is Block && other.window == window && other.until == until

        override fun hashCode(): Int = 31 * window.hashCode() + (until?.hashCode() ?: 0)

        /** 풀렸나. */
        fun isOver(now: Long): Boolean = until?.let { now >= it } ?: false

        /** 지금 보내면 뻔히 429 인가 — 그동안은 전송을 잠근다. */
        fun stopsSending(now: Long): Boolean = until != null && !isOver(now)

        /** 대화 안의 안내 한 줄. **iOS 와 같은 문구여야 한다.** */
        fun message(now: Long): String {
            val until = until
            return when {
                window == Window.SHORT && until != null -> {
                    tr("가이드에게 잠시 많이 물어봤어요. %d분 뒤에 다시 물어볼 수 있어요").format(minutes(until - now))
                }

                window == Window.DAY -> {
                    tr("오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요")
                }

                // 얼마나 기다릴지 모른다 — 「오늘」 도 「내일」 도 말하지 않는다.
                else -> {
                    tr("지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요")
                }
            }
        }
    }

    /** 입력창 위의 남은 양 한 줄. */
    sealed class Remaining {
        /** 보이지 않는다 — 헤더가 없다(옛 서버·아직 묻지 않음), 넉넉하다, 또는 그 창이 이미 다시 찼다. */
        object Hidden : Remaining()

        /** 얼마 안 남았다. [refillMinutes] 가 null 이면 오늘 안에는 다시 차지 않는다. */
        data class Few(
            val count: Int,
            val refillMinutes: Int?,
        ) : Remaining()

        /** 다 썼다 — 다음 질문은 한도 안내를 받는다. */
        data class None(
            val refillMinutes: Int?,
        ) : Remaining()

        val text: String?
            get() =
                when (this) {
                    Hidden -> {
                        null
                    }

                    is Few -> {
                        if (refillMinutes != null) {
                            counted("%1\$d번 더 물어볼 수 있어요 · %2\$d분 뒤 다시 채워져요", count).format(count, refillMinutes)
                        } else {
                            counted("오늘 %d번 더 물어볼 수 있어요", count).format(count)
                        }
                    }

                    is None -> {
                        if (refillMinutes != null) {
                            tr("지금은 더 물어볼 수 없어요 · %d분 뒤 다시 채워져요").format(refillMinutes)
                        } else {
                            tr("오늘은 더 물어볼 수 없어요")
                        }
                    }
                }

        /** 영어는 하나일 때 낱말이 달라진다(「1 question left」) — 번역 표의 `원문|하나`. */
        private fun counted(
            korean: String,
            count: Int,
        ): String = if (count == 1) tr(korean, "하나") else tr(korean)
    }

    /** 서버가 마지막으로 알려 준 값으로 남은 양 줄을 정한다. */
    fun remaining(
        quota: UsageQuota?,
        now: Long,
    ): Remaining {
        if (quota == null || quota.limit <= 0 || now >= quota.refillsAt) return Remaining.Hidden
        val leftMillis = quota.refillsAt - now
        val leftSeconds = ((leftMillis + 999) / 1000).toInt()
        // 서버가 주는 것은 가장 빠듯한 창의 값이라 1 시간 창일 수도 하루 창일 수도 있다 — 한 시간 안에 다시 차면 그때를 함께 말한다.
        val minutes = if (window(leftSeconds) == Window.SHORT) minutes(leftMillis) else null
        if (quota.remaining <= 0) return Remaining.None(minutes)
        if (quota.remaining.toDouble() > quota.limit * LOW_SHARE) return Remaining.Hidden
        return Remaining.Few(quota.remaining, minutes)
    }
}
