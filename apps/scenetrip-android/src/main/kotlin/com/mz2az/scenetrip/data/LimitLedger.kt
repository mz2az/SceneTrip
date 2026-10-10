package com.mz2az.scenetrip.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.Response

/** 순수 HTTP 사용량을 화면에 노출한다. 계정 변경 때만 초기화한다. */
object LimitLedger : RetryLedger {
    private val ledger = UsageLedger()
    private var state by mutableStateOf(UsageSnapshot())
    override val generation: Int get() = ledger.generation
    override val knowsLimits: Boolean get() = ledger.knowsLimits
    val guideBlock: UsageBlock? get() = state.guideBlock
    val navigationBlock: UsageBlock? get() = state.navigationBlock
    val guideQuota: UsageQuota? get() = state.guideQuota

    @Synchronized fun reset() {
        ledger.reset()
        state = ledger.snapshot
    }

    override fun keyStarted(
        key: String,
        now: Long,
    ): Long = ledger.keyStarted(key, now)

    @Synchronized override fun observe(
        response: Response,
        requestedGeneration: Int,
        now: Long,
    ) {
        ledger.observe(response, requestedGeneration, now)
        state = ledger.snapshot
    }
}
