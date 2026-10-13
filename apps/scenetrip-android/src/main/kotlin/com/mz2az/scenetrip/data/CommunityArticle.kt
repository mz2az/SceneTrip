package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import java.text.Normalizer
import java.util.UUID

data class CommunityPhotoPlacement(
    val afterParagraph: Int,
    val caption: String = "",
    val credit: String = "",
    val aiGenerated: Boolean = false,
) {
    val captionLength get() = CommunityArticle.normalized(caption).length
    val creditLength get() = CommunityArticle.normalized(credit).length
    val metadataValid get() = captionLength <= CAPTION_LIMIT && creditLength <= CREDIT_LIMIT

    companion object {
        const val CAPTION_LIMIT = 500
        const val CREDIT_LIMIT = 1000
    }
}

/** 사진 UUID를 현재 첨부 인덱스로 바꾼다. 옛 글의 배치는 읽기 시에만 계산한다. */
object CommunityArticle {
    const val PARAGRAPH_LIMIT = 64

    fun normalized(value: String): String =
        Normalizer.normalize(value.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC).trim()

    fun paragraphs(body: String): List<String> =
        body
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split("\n\n")
            .map(::normalized)
            .filter(String::isNotEmpty)

    fun plainBody(body: String): String = paragraphs(body).joinToString("\n\n")

    fun canCompose(body: String): Boolean =
        paragraphs(body).size in 1..PARAGRAPH_LIMIT && plainBody(body).length <= CommunityRules.BODY_LIMIT

    fun placement(
        id: UUID,
        index: Int,
        paragraphCount: Int,
        placements: Map<UUID, CommunityPhotoPlacement>,
    ): CommunityPhotoPlacement =
        (placements[id] ?: CommunityPhotoPlacement(minOf(index + 1, paragraphCount))).let {
            it.copy(afterParagraph = it.afterParagraph.coerceIn(0, paragraphCount))
        }

    fun retained(
        placements: Map<UUID, CommunityPhotoPlacement>,
        photoIds: List<UUID>,
    ): Map<UUID, CommunityPhotoPlacement> =
        placements.filterKeys {
            it in
                photoIds
        }

    fun blocks(
        body: String,
        photoIds: List<UUID>,
        placements: Map<UUID, CommunityPhotoPlacement>,
    ): List<TripPostBlock> {
        val parts = paragraphs(body)
        return buildList {
            for (position in 0..parts.size) {
                if (position > 0) add(TripPostBlock(TripPostBlockKind.text, text = parts[position - 1]))
                photoIds.forEachIndexed { index, id ->
                    val place = placement(id, index, parts.size, placements)
                    if (place.afterParagraph == position) {
                        add(
                            TripPostBlock(
                                TripPostBlockKind.photo,
                                photoIndex = index,
                                caption = normalized(place.caption).ifEmpty { null },
                                credit = normalized(place.credit).ifEmpty { null },
                                aiGenerated = true.takeIf { place.aiGenerated },
                            ),
                        )
                    }
                }
            }
        }
    }

    fun legacy(
        body: String,
        photoCount: Int,
    ): List<TripPostBlock> {
        val parts = paragraphs(body)
        var next = 0
        return buildList {
            parts.forEachIndexed { index, text ->
                add(TripPostBlock(TripPostBlockKind.text, text = text))
                val target = (index + 1) * photoCount / parts.size
                while (next < target) add(TripPostBlock(TripPostBlockKind.photo, photoIndex = next++))
            }
            while (next < photoCount) add(TripPostBlock(TripPostBlockKind.photo, photoIndex = next++))
        }
    }

    fun validBlocks(
        body: String,
        photoCount: Int,
        blocks: List<TripPostBlock>?,
    ): Boolean {
        if (photoCount !in 0..CommunityRules.PHOTO_LIMIT) return false
        if (blocks == null) return true
        if (blocks.isEmpty() || blocks.size > PARAGRAPH_LIMIT + CommunityRules.PHOTO_LIMIT) return false
        val texts = mutableListOf<String>()
        val photos = mutableListOf<Int>()
        for (block in blocks) {
            when (block.kind) {
                TripPostBlockKind.text -> {
                    val text = block.text ?: return false
                    if (normalized(text).isEmpty() || text.length > CommunityRules.BODY_LIMIT || block.photoIndex != null ||
                        block.caption != null ||
                        block.credit != null ||
                        block.aiGenerated != null
                    ) {
                        return false
                    }
                    texts += normalized(text)
                }

                TripPostBlockKind.photo -> {
                    val index = block.photoIndex ?: return false
                    if (index !in 0 until photoCount || block.text != null ||
                        (block.caption?.length ?: 0) > CommunityPhotoPlacement.CAPTION_LIMIT ||
                        (block.credit?.length ?: 0) > CommunityPhotoPlacement.CREDIT_LIMIT
                    ) {
                        return false
                    }
                    photos += index
                }
            }
        }
        return texts.size in 1..PARAGRAPH_LIMIT && texts.joinToString("\n\n") == normalized(body) &&
            photos.sorted() == (0 until photoCount).toList()
    }
}
