package com.mz2az.scenetrip.hometab

import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary

/**
 * 「지금 뜨는 작품」 맨 앞에 무조건 세우는 작품 (MZ2AZ-547 H6, 웹 MZ2AZ-534 `pinnedWorks.ts`, iOS `PinnedWorks.swift`, 사용자 결정 2026-11-07).
 *
 * 나머지는 서버 인기순 그대로이고 고정 작품만 빼서 앞에 둔다(중복 없음). 받은 목록에 없으면 `GET /contents/{id}` 로
 * 한 번 받아 넣는다. 그것도 실패하거나 숨긴 작품이면 조용히 기존 순서다. 홈 선반과 전체 보기 격자가 같이 쓴다.
 */
object PinnedWorks {
    /** 맨 앞 고정 작품 id — 적힌 순서대로. 32 = 「케이팝 데몬 헌터스」(DEV·PRD 같은 id). */
    val ids: List<Long> = listOf(32L)

    /** 목록에 없는 고정 작품 id — 따로 받아야 하는 것(적힌 순서). */
    fun missing(
        list: List<ContentSummary>,
        ids: List<Long>,
    ): List<Long> {
        val present = list.map { it.id }.toSet()
        return ids.filter { it !in present }
    }

    /** 상세 → 목록 줄. 숨긴 작품(`delisted == true`)이면 null. */
    fun summary(detail: ContentDetail): ContentSummary? {
        if (detail.delisted == true) return null
        return ContentSummary(
            id = detail.id,
            category = detail.category,
            title = detail.title,
            placeCount = detail.placeCount,
            posterUrl = detail.posterUrl,
            producerUrl = detail.producerUrl,
            broadcaster = detail.broadcaster,
            releaseYear = detail.releaseYear,
            genres = detail.genres,
            delisted = detail.delisted,
        )
    }

    /** 고정 작품을 적힌 순서로 앞에(목록에 있으면 그것, 없으면 [fetched] 의 것, 둘 다 없으면 건너뜀), 나머지는 받은 순서 그대로. */
    fun arrange(
        list: List<ContentSummary>,
        fetched: Map<Long, ContentSummary>,
        ids: List<Long>,
    ): List<ContentSummary> {
        val front = mutableListOf<ContentSummary>()
        for (id in ids) {
            if (front.any { it.id == id }) continue
            (list.firstOrNull { it.id == id } ?: fetched[id])?.let { front += it }
        }
        val frontIds = front.map { it.id }.toSet()
        return front + list.filter { it.id !in frontIds }
    }

    /** 목록에 없는 고정 작품을 [fetch] 로 받아 [arrange] 한다. [fetch] 가 null 이면(실패) 그 작품은 건너뛴다. */
    suspend fun apply(
        list: List<ContentSummary>,
        ids: List<Long> = this.ids,
        fetch: suspend (Long) -> ContentDetail?,
    ): List<ContentSummary> {
        val fetched = mutableMapOf<Long, ContentSummary>()
        for (id in missing(list, ids)) {
            fetch(id)?.let(::summary)?.let { fetched[id] = it }
        }
        return arrange(list, fetched, ids)
    }
}
