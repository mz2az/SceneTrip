package com.mz2az.scenetrip.data

/** iOS와 같은 요청별 재전송 규칙. 유료 호출·일회용 토큰·빠른 토글을 중복 처리하지 않는다. */
object RetryRules {
    enum class Lane { READ, WRITE, UNSAFE, CHAT, NEVER }

    data class Policy(
        val lane: Lane,
        val retries: Int,
        val timeoutSeconds: Int,
        val budgetSeconds: Int,
    )

    data class History(
        val retries: Int = 0,
        val retried500: Boolean = false,
        val waited: Boolean = false,
        val mayHaveReached: Boolean = false,
        val polls: Int = 0,
    )

    data class Step(
        val waitMillis: Long,
        val forLimit: Boolean = false,
        val poll: Boolean = false,
    )

    fun policy(
        method: String,
        path: String,
        keyed: Boolean = false,
    ): Policy {
        val lane =
            when {
                method == "POST" && path.endsWith("/guide/chat") && keyed -> Lane.CHAT

                listOf(
                    "/guide/chat",
                    "/guide/plan",
                    "/navigation/next-leg",
                    "/auth/refresh",
                    "/auth/sign-out",
                ).any { path.endsWith(it) } -> Lane.NEVER

                method == "DELETE" && path.endsWith("/me") && !path.endsWith("/reviews/me") -> Lane.NEVER

                method in listOf("GET", "HEAD") -> Lane.READ

                method in listOf("PUT", "DELETE") && !unawaited(path) -> Lane.WRITE

                else -> Lane.UNSAFE
            }
        return when (lane) {
            Lane.READ -> Policy(lane, 3, 15, 25)
            Lane.WRITE -> Policy(lane, 2, 30, 45)
            Lane.UNSAFE -> Policy(lane, 2, 30, 45)
            Lane.CHAT -> Policy(lane, 1, 50, 110)
            Lane.NEVER -> Policy(lane, 0, if (path.contains("/guide/")) 60 else 30, if (path.contains("/guide/")) 60 else 30)
        }
    }

    private fun unawaited(path: String): Boolean =
        path.contains("/favorites/") || path.contains("/cart/items/") ||
            listOf("/visit", "/progress", "/likes", "/me/nickname").any { path.endsWith(it) }

    fun next(
        policy: Policy,
        history: History,
        status: Int?,
        code: String?,
        retryAfter: Int?,
        unreached: Boolean,
        elapsedMillis: Long,
        keyAgeMillis: Long = elapsedMillis,
    ): Step? {
        if (status == 409 && code == "IDEMPOTENCY_IN_PROGRESS") {
            return if (policy.lane == Lane.CHAT && history.polls < 20 && keyAgeMillis + 3000 < 55_000 &&
                elapsedMillis + 3000 < 110_000
            ) {
                Step(3000, poll = true)
            } else {
                null
            }
        }
        if (policy.lane == Lane.NEVER || history.retries >= policy.retries) return null
        if (status == 429) {
            if (code != null && code != "RATE_LIMITED") return null
            val seconds = retryAfter ?: 1
            if (history.waited || seconds > 60 || elapsedMillis > policy.budgetSeconds * 1000L - 3000) return null
            return Step(seconds.coerceAtLeast(0) * 1000L, forLimit = true)
        }
        if (status == null && !unreached && policy.lane == Lane.UNSAFE) return null
        if (status != null && (policy.lane == Lane.UNSAFE || status !in listOf(500, 502, 503, 504))) return null
        if (status == 500 && history.retried500) return null
        val delay = (1L shl history.retries.coerceAtMost(2)) * 1000
        return if (elapsedMillis + delay < policy.budgetSeconds * 1000L) Step(delay) else null
    }

    fun record(
        history: History,
        step: Step,
        status: Int?,
        unreached: Boolean,
    ): History =
        history.copy(
            retries = history.retries + if (step.poll) 0 else 1,
            polls = history.polls + if (step.poll) 1 else 0,
            retried500 = history.retried500 || status == 500,
            waited = history.waited || step.forLimit,
            mayHaveReached = history.mayHaveReached || (!unreached && !step.forLimit),
        )
}
