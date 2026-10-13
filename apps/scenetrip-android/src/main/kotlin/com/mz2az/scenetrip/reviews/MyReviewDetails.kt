package com.mz2az.scenetrip.reviews

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.sceneapi.client.model.PoiDetail
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTarget
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType

sealed interface MyReviewDetail {
    data class Place(
        val value: PlaceDetail,
    ) : MyReviewDetail

    data class Poi(
        val value: PoiDetail,
    ) : MyReviewDetail
}

interface ReviewDetailClient {
    fun place(id: Long): PlaceDetail

    fun poi(id: Long): PoiDetail
}

class ServerReviewDetailClient : ReviewDetailClient {
    override fun place(id: Long) = PlacesApi(API_BASE).getPlace(id, acceptLanguage = AppLanguage.current)

    override fun poi(id: Long) = PoisApi(API_BASE).getPoi(id, acceptLanguage = AppLanguage.current)
}

/** 같은 숫자여도 종류별 창구가 다르며 다른 ID 응답은 표시하지 않는다. */
class MyReviewDetails(
    private val client: ReviewDetailClient,
) {
    suspend fun load(target: ReviewTarget): Result<MyReviewDetail> =
        apiResult {
            require(target.id > 0)
            when (target.type) {
                ReviewTargetType.place -> client.place(target.id).also { require(it.id == target.id) }.let(MyReviewDetail::Place)
                ReviewTargetType.poi -> client.poi(target.id).also { require(it.id == target.id) }.let(MyReviewDetail::Poi)
            }
        }
}
