package com.mz2az.scenetrip.reviews

import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.sceneapi.client.api.ReviewsApi
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewInput
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort

/** 리뷰 화면 한 벌을 촬영지·편의시설에서 같이 쓴다. 생성 클라이언트만 호출한다. */
data class ReviewSubject(
    val id: Long,
    val name: String,
    val isPoi: Boolean = false,
) {
    private val api get() = ReviewsApi(API_BASE)

    fun list(
        sort: ReviewSort,
        offset: Int,
    ) = if (isPoi) api.listPoiReviews(id, sort = sort, offset = offset) else api.listPlaceReviews(id, sort = sort, offset = offset)

    fun photos(
        offset: Int,
        limit: Int = 20,
    ) = if (isPoi) api.listPoiPhotos(id, offset = offset, limit = limit) else api.listPlacePhotos(id, offset = offset, limit = limit)

    fun mine(): Review? =
        try {
            if (isPoi) api.getMyPoiReview(id) else api.getMyPlaceReview(id)
        } catch (error: Exception) {
            if (NetworkFailure.of(error).code == "REVIEW_NOT_FOUND") null else throw error
        }

    fun save(input: ReviewInput) = if (isPoi) api.putMyPoiReview(id, input) else api.putMyPlaceReview(id, input)

    fun delete() = if (isPoi) api.deleteMyPoiReview(id) else api.deleteMyPlaceReview(id)
}
