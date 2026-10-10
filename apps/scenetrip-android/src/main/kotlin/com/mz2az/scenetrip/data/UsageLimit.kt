package com.mz2az.scenetrip.data

/** 잠금·남은 양은 서버 헤더로만 만든다. 시각은 연속 시계의 밀리초다. */
data class UsageBlock(
    val retryAfter: Int?,
    val receivedAt: Long,
) {
    val until: Long? get() = retryAfter?.let { receivedAt + it.coerceAtLeast(0) * 1000L }

    fun blocked(now: Long): Boolean = until?.let { now < it } ?: false

    fun lifted(now: Long): Boolean = until?.let { now >= it } ?: false

    fun minutes(now: Long): Int = (((until ?: now) - now + 59_999) / 60_000).toInt().coerceAtLeast(1)
}

data class UsageQuota(
    val limit: Int,
    val remaining: Int,
    val refillsAt: Long,
) {
    fun visible(now: Long): Boolean = limit > 0 && remaining in 0..limit && remaining <= limit * 0.2 && now < refillsAt
}
