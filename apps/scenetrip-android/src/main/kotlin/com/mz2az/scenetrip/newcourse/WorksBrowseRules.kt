package com.mz2az.scenetrip.newcourse

import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary

// 작품 고르기 단계(「누구의 장면을 따라갈까요?」)의 쪽 넘김·검색·고른 것 유지 규칙
// (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §1).
// 화면은 이 규칙만 따른다 — 판단은 전부 여기서 한다.
//
// TODO(MZ2AZ-547): 아래는 시험을 먼저 쓰기 위한 자리표시(stub)다. 값은 일부러 틀리다.

object WorksBrowseRules {
    /** 한 번에 받는 작품 수(`GET /contents?limit=30`). */
    const val PAGE_SIZE = 30

    /** 검색칸 입력이 멎고 묻기까지(ms). */
    const val SEARCH_DEBOUNCE_MILLIS = 300L

    /** 계약의 `q` 최대 길이. */
    const val MAX_QUERY_LENGTH = 100

    /** 고를 때 함께 보이는 포스터 열 수 — 「한 줄 안」 을 셀 때 쓴다. */
    const val COLUMNS = 3

    /** 검색칸 글 → 서버에 보낼 `q`. 앞뒤 공백을 떼고, 비거나 공백뿐이면 null(원래 목록), 길면 [MAX_QUERY_LENGTH] 자에서 자른다. */
    fun searchQuery(text: String): String? = text

    /** 다음 쪽의 `offset` — 이미 받은 수. [total] 에 닿았으면(이상이면) null(그만 받는다). total 을 모르면 받은 수. */
    fun nextOffset(
        loaded: Int,
        total: Int?,
    ): Int? = 0

    /** 목록 끝에서 한 줄([columns] 개) 안이 보이면 다음 쪽을 받는다. 받은 것이 없으면 false. */
    fun nearEnd(
        lastVisibleIndex: Int,
        loaded: Int,
        columns: Int = COLUMNS,
    ): Boolean = false

    /** 받은 쪽을 뒤에 붙인다 — 이미 있는 id 는 다시 넣지 않는다(앞의 것을 둔다). */
    fun appended(
        existing: List<ContentSummary>,
        page: List<ContentSummary>,
    ): List<ContentSummary> = existing

    /** 보여 줄 순서 — 찜한 작품 먼저, 그다음 나머지. 각 무리 안은 서버 순서 그대로. */
    fun ordered(
        works: List<ContentSummary>,
        liked: Set<Long>,
    ): List<ContentSummary> = works

    /**
     * 고른 작품을 기억해 둔다 — 검색 결과·다음 쪽에서 사라져도 제목을 쓸 수 있게.
     * 지금 고른 것([selected])만 남기고, [seen](지금 화면에 받은 것)에서 고른 것을 새로 채운다(같은 id 는 새 값).
     */
    fun rememberPicked(
        remembered: Map<Long, ContentSummary>,
        selected: Set<Long>,
        seen: List<ContentSummary>,
    ): Map<Long, ContentSummary> = remembered

    /**
     * AI 일정 요청(`POST /guide/plan`)에 넣을 작품 제목 — 고른 순서대로, 기억에 없는 id 는 뺀다.
     * 하나도 못 쓰면(안 골랐으면) 인기 작품 앞 3개의 제목.
     */
    fun requestTitles(
        selected: Set<Long>,
        remembered: Map<Long, ContentSummary>,
        popular: List<ContentSummary>,
    ): List<String> = emptyList()

    /** 검색 결과가 없을 때 — 「「q」 에 맞는 작품이 없어요」. */
    fun noMatch(query: String): String = tr("")
}
