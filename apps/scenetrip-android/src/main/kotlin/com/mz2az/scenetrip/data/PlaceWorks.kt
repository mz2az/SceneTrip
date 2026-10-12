package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.ContentRef
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.sceneapi.client.model.Scene

/** 상세에서는 scenes가 정본이고, 목록에서는 contents가 정본이다(MZ2AZ-372). */
object PlaceWorks {
    fun refs(
        scenes: List<Scene>?,
        contents: List<ContentRef>?,
    ): List<ContentRef> {
        val found = scenes.orEmpty().map { ContentRef(it.contentId, it.contentTitle, it.posterUrl) }
        return found.ifEmpty { contents.orEmpty() }.distinctBy { it.contentId }
    }

    fun titles(detail: PlaceDetail): List<String> = refs(detail.scenes, detail.contents).map { it.title }.filter { it.isNotEmpty() }

    /** 빈 답도 답이다. 오류와 미응답만 다시 조회하고, 핀·POI는 조회하지 않는다. */
    fun toAsk(
        ids: List<Long>,
        listed: Set<Long>,
        answered: Set<Long>,
        asking: Set<Long>,
    ): List<Long> = ids.distinct().filter { it > 0 && it !in listed && it !in answered && it !in asking }
}
