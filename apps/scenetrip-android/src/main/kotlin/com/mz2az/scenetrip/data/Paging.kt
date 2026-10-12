package com.mz2az.scenetrip.data

/** 중복을 지운 행 수 대신 서버가 돌려준 행 수로 다음 offset을 계산한다(MZ2AZ-372). */
data class Paging(
    val offset: Int = 0,
    val total: Int? = null,
    val exhausted: Boolean = false,
) {
    val hasMore: Boolean get() = !exhausted && (total == null || offset < total)

    fun advanced(
        received: Int,
        total: Int,
    ): Paging = copy(offset = offset + received, total = total, exhausted = received == 0)

    fun shownTotal(loaded: Int): Int = maxOf(total ?: 0, loaded)

    companion object {
        fun <Item, Key> merged(
            existing: List<Item>,
            page: List<Item>,
            key: (Item) -> Key,
        ): List<Item> {
            val seen = existing.map(key).toMutableSet()
            return existing + page.filter { seen.add(key(it)) }
        }
    }
}
