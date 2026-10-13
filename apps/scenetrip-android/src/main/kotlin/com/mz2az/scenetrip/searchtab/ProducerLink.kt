package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.WebAddress
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary

/** 상세 응답이 정본이며 제작사 주소를 포스터나 이름에서 추정하지 않는다 (MZ2AZ-392). */
object ProducerLink {
    fun destination(
        summary: ContentSummary,
        detail: ContentDetail?,
    ): String? = WebAddress.url(if (detail?.id == summary.id) detail.producerUrl?.toString() else summary.producerUrl?.toString())
}
