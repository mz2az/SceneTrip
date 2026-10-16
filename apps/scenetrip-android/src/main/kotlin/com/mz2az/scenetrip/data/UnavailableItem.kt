package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentDetail
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseItem
import com.mz2az.scenetrip.sceneapi.client.model.CourseItemSource

/**
 * 서버의 숨김·폐업 표시. 항목을 지우거나 저장 ID를 바꾸는 근거로 쓰지 않는다(MZ2AZ-458).
 * iOS `Models/UnavailableItem.swift`의 `UnavailableItem`.
 *
 * 값이 없거나 `false`면 정상이다 — 서버가 이 칸을 빼고 보내는 것이 기본이다.
 */
enum class UnavailableItem {
    PLACE,
    CONTENT,
    CLOSED_POI,
    ;

    /** 문구는 언어에 따라 달라지므로 값이 아니라 계산 프로퍼티다. */
    val message: String
        get() =
            when (this) {
                PLACE -> tr("더 이상 소개하지 않는 장소예요")
                CONTENT -> tr("더 이상 소개하지 않는 작품이에요")
                CLOSED_POI -> tr("폐업했어요")
            }

    companion object {
        fun notice(item: CartItem): UnavailableItem? = if (item.delisted == true) PLACE else null

        fun notice(item: ContentSummary): UnavailableItem? = if (item.delisted == true) CONTENT else null

        fun notice(item: CourseItem): UnavailableItem? =
            when (item.source) {
                CourseItemSource.place -> if (item.delisted == true) PLACE else null
                CourseItemSource.poi -> if (item.closed == true) CLOSED_POI else null
                CourseItemSource.customPin -> null
            }

        /** 새로 담기만 막는다. 이미 담은 것을 빼는 동작은 항상 가능하다. */
        fun canToggle(
            delisted: Boolean?,
            saved: Boolean,
        ): Boolean = saved || delisted != true

        /** 상세를 다시 받으면 누락도 false다. 옛 요약의 true를 계속 붙들지 않는다. */
        fun contentDelisted(
            summary: ContentSummary,
            detail: ContentDetail?,
        ): Boolean = detail?.let { it.delisted == true } ?: (summary.delisted == true)

        /**
         * 409 중 숨김·폐업으로 거절된 것의 안내문. `DUPLICATE_CART_ITEM`·그 밖의 409는 null이다 —
         * 이미 담긴 것이거나 다른 충돌이라 이 문구를 쓰면 안 된다.
         */
        fun rejection(
            status: Int?,
            code: String?,
        ): String? {
            if (status != 409) return null
            return when (code) {
                "PLACE_DELISTED" -> tr("목록에서 빠진 장소라 담을 수 없어요.")
                "CONTENT_DELISTED" -> tr("목록에서 빠진 작품이라 찜할 수 없어요.")
                "POI_CLOSED" -> tr("폐업한 곳이라 코스에 넣을 수 없어요.")
                else -> null
            }
        }
    }
}
