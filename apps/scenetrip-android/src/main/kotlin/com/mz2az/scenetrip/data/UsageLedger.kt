package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.auth.AuthRules
import okhttp3.Response

/** HTTP 사용량 저장소는 Android 화면·시계에 의존하지 않는다. */
interface RetryLedger {
    val generation: Int
    val knowsLimits: Boolean

    fun keyStarted(
        key: String,
        now: Long,
    ): Long

    fun observe(
        response: Response,
        requestedGeneration: Int,
        now: Long,
    )
}

data class UsageSnapshot(
    val guideBlock: UsageBlock? = null,
    val navigationBlock: UsageBlock? = null,
    val guideQuota: UsageQuota? = null,
)

class UsageLedger : RetryLedger {
    private val keys = LinkedHashMap<String, Long>()

    @Volatile override var generation = 0
        private set

    @Volatile override var knowsLimits = false
        private set

    @Volatile var snapshot = UsageSnapshot()
        private set

    @Synchronized override fun keyStarted(
        key: String,
        now: Long,
    ): Long {
        keys[key]?.let { return it }
        if (keys.size >= 100) keys.remove(keys.keys.first())
        return now.also { keys[key] = it }
    }

    @Synchronized fun reset() {
        generation += 1
        knowsLimits = false
        snapshot = UsageSnapshot()
        keys.clear()
    }

    @Synchronized override fun observe(
        response: Response,
        requestedGeneration: Int,
        now: Long,
    ) {
        if (generation != requestedGeneration) return
        val path = response.request.url.encodedPath
        val code = if (response.code == 429) AuthRules.apiCode(response.peekBody(16_384).string()) else null
        val retry = response.header("Retry-After")?.toIntOrNull()
        val limit = response.header("RateLimit-Limit")?.toIntOrNull()
        val remaining = response.header("RateLimit-Remaining")?.toIntOrNull()
        val reset = response.header("RateLimit-Reset")?.toLongOrNull()
        val valid =
            limit != null && limit > 0 && remaining != null && remaining in 0..limit && reset != null &&
                reset in 0..((Long.MAX_VALUE - now) / 1000)
        if (valid) knowsLimits = true
        var next = snapshot
        if (path.endsWith("/guide/chat")) {
            if (code == "GUIDE_LIMIT_REACHED") {
                next = next.copy(guideBlock = UsageBlock(retry, now))
            } else if (response.isSuccessful) {
                next = next.copy(guideBlock = null)
            }
            if (valid) next = next.copy(guideQuota = UsageQuota(limit!!, remaining!!, now + reset!! * 1000L))
        }
        if (path.endsWith("/navigation/next-leg") &&
            code == "NAVIGATION_LIMIT_REACHED"
        ) {
            next = next.copy(navigationBlock = UsageBlock(retry, now))
        }
        snapshot = next
    }
}
