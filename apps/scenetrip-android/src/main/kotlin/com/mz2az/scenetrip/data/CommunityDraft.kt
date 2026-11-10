package com.mz2az.scenetrip.data

import java.util.UUID

/**
 * 블로그형 글쓰기의 초안 한 항목 — 글 조각 또는 사진 (MZ2AZ-547 K11, iOS `CommunityDraftItem`, 웹 `community/articleDraft.ts`).
 */
sealed interface CommunityDraftItem {
    val id: UUID

    data class Text(
        override val id: UUID,
        val text: String,
    ) : CommunityDraftItem

    data class Photo(
        override val id: UUID,
    ) : CommunityDraftItem
}

/** 「사진 넣기」 의 결과 — 나눈 목록과 사진을 넣을 자리(그 항목 뒤, −1 이면 맨 앞). */
data class CommunityDraftSplit(
    val items: List<CommunityDraftItem>,
    val insertAfter: Int,
)

/** 마지막으로 글을 쓰던 조각과 그 안의 커서(UTF-16 위치). */
data class CommunityDraftCaret(
    val id: UUID,
    val at: Int,
)

/**
 * 글 조각과 사진이 쓴 순서대로 놓인 목록의 규칙 (iOS `CommunityDraft`).
 *
 * 저장 형식은 그대로다(계약의 `body` + `bodyBlocks`, MZ2AZ-428). 이 목록에서 본문([story])과 사진마다 「몇 번째 문단 뒤」([placements])를
 * 계산해 기존 [CommunityArticle.blocks] 에 넘긴다. 글 조각 하나에 빈 줄이 있으면 문단이 여럿이다.
 */
object CommunityDraft {
    /** 글 조각을 쓴 순서대로 빈 줄("\n\n")로 이은 본문. 조각마다 앞뒤 빈칸을 떼고 빈 조각은 건너뛴다. */
    fun story(items: List<CommunityDraftItem>): String =
        items
            .filterIsInstance<CommunityDraftItem.Text>()
            .map { CommunityArticle.normalized(it.text) }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    /**
     * 사진마다 「앞에 있는 문단 수」([CommunityArticle.paragraphs] 로 센다) = 그 사진이 들어갈 자리(`afterParagraph`).
     * 설명·출처·AI 표시는 [meta] 에서(없으면 빈 값·false). meta 의 `afterParagraph` 는 무시한다.
     */
    fun placements(
        items: List<CommunityDraftItem>,
        meta: Map<UUID, CommunityPhotoPlacement>,
    ): Map<UUID, CommunityPhotoPlacement> {
        val out = LinkedHashMap<UUID, CommunityPhotoPlacement>()
        var before = 0
        for (item in items) {
            when (item) {
                is CommunityDraftItem.Text -> {
                    before += CommunityArticle.paragraphs(item.text).size
                }

                is CommunityDraftItem.Photo -> {
                    out[item.id] =
                        (meta[item.id] ?: CommunityPhotoPlacement(before)).copy(afterParagraph = before)
                }
            }
        }
        return out
    }

    /** 목록의 사진 순서 — 첫 사진이 대표(목록 썸네일)다. */
    fun photoOrder(items: List<CommunityDraftItem>): List<UUID> = items.filterIsInstance<CommunityDraftItem.Photo>().map { it.id }

    /**
     * [at] 번째 항목 뒤(−1 이면 맨 앞)에 사진들을 넣는다. 이미 있는 사진 id·같은 id 두 번은 다시 넣지 않는다.
     * 넣을 자리는 0..items.size 로 자른다. 넣을 것이 없으면 그대로.
     */
    fun insertPhotos(
        items: List<CommunityDraftItem>,
        at: Int,
        ids: List<UUID>,
    ): List<CommunityDraftItem> {
        val present = photoOrder(items).toMutableSet()
        val fresh = ids.filter { present.add(it) }.map { CommunityDraftItem.Photo(it) }
        if (fresh.isEmpty()) return items
        val cut = (at + 1).coerceIn(0, items.size)
        return items.subList(0, cut) + fresh + items.subList(cut, items.size)
    }

    /** 사진 칸에서 빠진 사진을 목록에서도 뺀다(글 조각은 그대로). */
    fun dropMissingPhotos(
        items: List<CommunityDraftItem>,
        slotIds: List<UUID>,
    ): List<CommunityDraftItem> {
        val keep = slotIds.toSet()
        return items.filter { it !is CommunityDraftItem.Photo || it.id in keep }
    }

    /**
     * 모양을 다듬는다 — 글 조각이 하나도 없으면 빈 조각 하나를 끝에, 사진이 맨 끝이면 그 뒤에 빈 조각(이어서 쓰게),
     * 빈(공백뿐인) 글 조각이 둘 이어지면 하나로(앞의 것을 남긴다). 새 조각의 id 는 [newId] 로 만든다.
     */
    fun normalize(
        items: List<CommunityDraftItem>,
        newId: () -> UUID,
    ): List<CommunityDraftItem> {
        val out = mutableListOf<CommunityDraftItem>()
        for (item in items) {
            if (isBlankText(item) && out.lastOrNull()?.let(::isBlankText) == true) continue
            out += item
        }
        if (out.none { it is CommunityDraftItem.Text } || out.lastOrNull() is CommunityDraftItem.Photo) {
            out += CommunityDraftItem.Text(newId(), "")
        }
        return out
    }

    /** [from] 의 항목을 [to] 자리로 옮긴다(빼고 그 자리에 끼운다). 둘 중 하나라도 범위 밖이면 그대로. */
    fun moveItem(
        items: List<CommunityDraftItem>,
        from: Int,
        to: Int,
    ): List<CommunityDraftItem> {
        if (from !in items.indices || to !in items.indices) return items
        val next = items.toMutableList()
        next.add(to, next.removeAt(from))
        return next
    }

    /**
     * 「사진 넣기」 의 자리 — 마지막으로 글을 쓰던 조각과 커서(UTF-16 위치)에서 그 조각을 둘로 나눈다.
     * - 커서가 없거나 그 id 의 항목이 없으면: 목록 그대로, 마지막 항목 뒤(`items.size - 1`).
     * - 그 항목이 사진이면: 그대로, 그 사진 뒤.
     * - 커서 뒤가 (빈칸 빼고) 비었으면: 나누지 않고 그 조각 뒤(index).
     * - 커서 앞이 (빈칸 빼고) 비었으면: 나누지 않고 그 조각 앞(index − 1).
     * - 그 밖: 앞부분(끝 빈칸 뗌)은 그 조각 id 그대로, 뒷부분(앞 빈칸 뗌)은 [newId] 로 새 조각을 바로 뒤에. 그 조각 뒤(index).
     */
    fun split(
        items: List<CommunityDraftItem>,
        caret: CommunityDraftCaret?,
        newId: () -> UUID,
    ): CommunityDraftSplit {
        val index = caret?.let { c -> items.indexOfFirst { it.id == c.id } } ?: -1
        if (caret == null || index < 0) return CommunityDraftSplit(items, items.size - 1)
        val piece = items[index] as? CommunityDraftItem.Text ?: return CommunityDraftSplit(items, index)
        val cut = caret.at.coerceIn(0, piece.text.length)
        val before = piece.text.substring(0, cut).trimEnd()
        val after = piece.text.substring(cut).trimStart()
        if (after.isEmpty()) return CommunityDraftSplit(items, index)
        if (before.isEmpty()) return CommunityDraftSplit(items, index - 1)
        val next = items.toMutableList()
        next[index] = CommunityDraftItem.Text(piece.id, before)
        next.add(index + 1, CommunityDraftItem.Text(newId(), after))
        return CommunityDraftSplit(next, index)
    }

    /**
     * 사진 칸 순서를 [ids] 순서로 맞춘다(iOS `ReviewPhotoDraft.reorder`) — 본문에 놓인 순서가 사진 순서다.
     * [ids] 에 없는 것은 원래 순서대로 뒤에 남는다. 없는 id 는 무시한다.
     */
    fun <T> reorder(
        slots: List<T>,
        ids: List<UUID>,
        idOf: (T) -> UUID,
    ): List<T> {
        val rest = slots.toMutableList()
        val ordered = mutableListOf<T>()
        for (id in ids) {
            val index = rest.indexOfFirst { idOf(it) == id }
            if (index >= 0) ordered += rest.removeAt(index)
        }
        return ordered + rest
    }

    private fun isBlankText(item: CommunityDraftItem): Boolean =
        item is CommunityDraftItem.Text && CommunityArticle.normalized(item.text).isEmpty()
}
