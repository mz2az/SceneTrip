package com.mz2az.scenetrip

import com.mz2az.scenetrip.reviews.MyReviewDetail
import com.mz2az.scenetrip.reviews.MyReviewDetails
import com.mz2az.scenetrip.reviews.ReviewDetailClient
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiDetail
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTarget
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyReviewDetailsTests {
    private class Client(
        var replyId: Long = 7,
    ) : ReviewDetailClient {
        val calls = mutableListOf<String>()

        override fun place(id: Long): PlaceDetail {
            calls += "place:$id"
            return PlaceDetail(id = replyId, name = "촬영지", latitude = 37.5, longitude = 127.0, contents = emptyList(), scenes = emptyList())
        }

        override fun poi(id: Long): PoiDetail {
            calls += "poi:$id"
            return PoiDetail(
                id = replyId,
                name = "편의시설",
                category = "카페",
                categoryGroup = PoiCategoryGroup.food,
                latitude = 37.5,
                longitude = 127.0,
                images = emptyList(),
            )
        }
    }

    @Test fun equalNumericIdsStillFetchDifferentTypedDetails() =
        runBlocking {
            val client = Client()
            val details = MyReviewDetails(client)
            assertTrue(details.load(ReviewTarget(ReviewTargetType.place, 7, "옛 이름")).getOrThrow() is MyReviewDetail.Place)
            assertTrue(details.load(ReviewTarget(ReviewTargetType.poi, 7, "옛 이름")).getOrThrow() is MyReviewDetail.Poi)
            assertEquals(listOf("place:7", "poi:7"), client.calls)
        }

    @Test fun mismatchedReplyCannotDisplayAnUnrelatedPlaceOrPoi() =
        runBlocking {
            val client = Client(8)
            val details = MyReviewDetails(client)
            for (type in ReviewTargetType.entries) assertTrue(details.load(ReviewTarget(type, 7, "이름")).isFailure)
        }

    @Test fun invalidIdsCannotReachEitherEndpoint() =
        runBlocking {
            val client = Client()
            val details = MyReviewDetails(client)
            assertTrue(details.load(ReviewTarget(ReviewTargetType.place, 0, "이름")).isFailure)
            assertTrue(client.calls.isEmpty())
        }
}
