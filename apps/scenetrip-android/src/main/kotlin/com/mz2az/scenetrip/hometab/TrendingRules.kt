package com.mz2az.scenetrip.hometab

import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary

/**
 * 「지금 뜨는 작품」 전체 보기 격자가 인기순 작품을 끝까지 받는 규칙 (MZ2AZ-547 H6, 웹 MZ2AZ-534 `trendingModel.ts`, iOS `TrendingRules.swift`).
 *
 * 새 끝점은 없다 — 선반과 같은 `GET /contents`(검색어 없음 = 인기도 내림차순)를 한도 100 으로 `offset` 이어 받아 `total` 까지.
 */
object TrendingRules {
    /** 한 번에 받는 수 — 계약의 `limit` 상한. */
    const val PAGE_LIMIT = 100

    /** 이어 받는 횟수 상한 — 서버가 total 을 잘못 줘도 끝없이 돌지 않게. */
    const val MAX_PAGES = 5

    /** 받은 쪽을 이어 붙인다 — 같은 작품이 두 쪽에 걸치면 먼저 받은 자리를 지킨다. */
    fun merge(
        collected: List<ContentSummary>,
        page: List<ContentSummary>,
    ): List<ContentSummary> {
        val seen = collected.map { it.id }.toMutableSet()
        val merged = collected.toMutableList()
        for (work in page) {
            if (seen.add(work.id)) merged += work
        }
        return merged
    }

    /** 한 쪽 더 받는가 — 방금 받은 쪽이 꽉 찼고([PAGE_LIMIT]) 아직 `total` 에 못 미칠 때. */
    fun shouldContinue(
        pageCount: Int,
        collected: Int,
        total: Int,
    ): Boolean = pageCount >= PAGE_LIMIT && collected < total

    /**
     * 끝까지 받아 고정 작품을 앞에 세운다. [fetchPage] (offset) 는 (그 쪽의 작품, total).
     * 쪽 받기가 실패하면 던진다(화면이 「다시 시도」 를 띄운다). 고정 작품 받기 실패는 조용히 넘어간다.
     */
    suspend fun load(
        fetchPage: suspend (Int) -> Pair<List<ContentSummary>, Int>,
        fetchDetail: suspend (Long) -> ContentDetail?,
    ): List<ContentSummary> {
        var works = emptyList<ContentSummary>()
        repeat(MAX_PAGES) {
            // 다음 쪽 위치는 걸러 낸 뒤의 수 — 웹(`offset: works.length`)·iOS 와 같다.
            val (page, total) = fetchPage(works.size)
            works = merge(works, page)
            if (!shouldContinue(page.size, works.size, total)) return PinnedWorks.apply(works, fetch = fetchDetail)
        }
        return PinnedWorks.apply(works, fetch = fetchDetail)
    }
}
