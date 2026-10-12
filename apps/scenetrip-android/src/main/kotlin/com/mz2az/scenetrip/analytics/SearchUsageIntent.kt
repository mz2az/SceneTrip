package com.mz2az.scenetrip.analytics

import com.mz2az.scenetrip.sceneapi.client.model.UsageEntityType
import com.mz2az.scenetrip.sceneapi.client.model.UsageEvent
import com.mz2az.scenetrip.sceneapi.client.model.UsageEventType

/** 확정한 검색과 첫 성공 응답의 서버 총수를 연결한다. 조회·재시도에는 만들지 않는다(MZ2AZ-408). */
data class SearchUsageIntent private constructor(
    val query: String,
    val target: UsageEntityType,
) {
    fun event(
        contentsTotal: Int,
        placesTotal: Int,
    ): UsageEvent? {
        val count = if (target == UsageEntityType.content) contentsTotal else placesTotal
        return count.takeIf { it >= 0 }?.let { UsageEvent(UsageEventType.search, query = query, resultCount = it) }
    }

    companion object {
        fun make(
            query: String,
            target: UsageEntityType,
        ): SearchUsageIntent? {
            val term = query.trim()
            if (term.length !in 1..100 || target !in listOf(UsageEntityType.content, UsageEntityType.place)) return null
            return SearchUsageIntent(term, target)
        }
    }
}
