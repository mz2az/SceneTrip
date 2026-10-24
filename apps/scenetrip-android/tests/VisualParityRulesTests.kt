package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.CartClient
import com.mz2az.scenetrip.data.CartSession
import com.mz2az.scenetrip.data.PlaceType
import com.mz2az.scenetrip.data.PlaceWorks
import com.mz2az.scenetrip.onboarding.OnboardingProgress
import com.mz2az.scenetrip.routetab.PoiMarkerRules
import com.mz2az.scenetrip.routetab.PoiText
import com.mz2az.scenetrip.routetab.RouteDayEstimate
import com.mz2az.scenetrip.routetab.RouteDedupe
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.routetab.cardPlace
import com.mz2az.scenetrip.routetab.courseStop
import com.mz2az.scenetrip.routetab.label
import com.mz2az.scenetrip.routetab.sameTarget
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentRef
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.OffsetDateTime

class VisualParityRulesTests {
    @Test
    fun courseCandidatesDedupeAcrossDaysAndKeepPlaceAndPoiIdNamespacesSeparate() {
        val place = RouteStop(place = PlaceSummary(id = 7, name = "First", latitude = 37.5, longitude = 127.0))
        val poi = poi(7, longitude = 127.1).courseStop()
        val renamedPoi = poi.copy(place = poi.place.copy(name = "Translated POI"))
        val samePlace = place.copy(place = place.place.copy(name = "Translated place"))
        val pin = place.copy(isPinned = true)
        val fresh = RouteDedupe.fresh(listOf(samePlace, pin, poi, renamedPoi, poi), listOf(place))
        assertEquals(listOf(poi), fresh)
        assertEquals(emptyList<RouteStop>(), RouteDedupe.fresh(listOf(place, poi), listOf(place, poi)))
    }

    @Test
    fun samePhysicalPlaceCannotBeAddedTwiceThroughDifferentIds() {
        val first = RouteStop(place = PlaceSummary(id = 1, name = "Cafe", latitude = 37.5, longitude = 127.0))
        val duplicate = first.copy(place = first.place.copy(id = 2))
        assertTrue(first.sameTarget(duplicate))
        assertTrue(first.sameTarget(first.copy(poiId = 8, isPinned = true)))
        assertFalse(first.sameTarget(first.copy(place = first.place.copy(id = 3, longitude = 127.1))))
    }

    @Test
    fun missingCourseWorksSkipPinsKnownEmptyAnswersAndDuplicateRequests() {
        assertEquals(listOf(8L), PlaceWorks.toAsk(listOf(-1, 0, 3, 4, 5, 8, 8), setOf(3), setOf(4), setOf(5)))
        val a = ContentRef(contentId = 2, title = "A")
        val b = ContentRef(contentId = 3, title = "B")
        assertEquals(listOf(a, b), PlaceWorks.refs(null, listOf(a, a, b)))
        assertEquals(emptyList<ContentRef>(), PlaceWorks.refs(null, null))
        val sceneB = Scene(3, "B", emptyList())
        val sceneA = Scene(2, "A", emptyList())
        assertEquals(listOf(b, a), PlaceWorks.refs(listOf(sceneB, sceneA, sceneB), listOf(a)))
    }

    @Test
    fun placeCategoriesResolveCodesAndLegacyNamesWithoutShowingUnknownCodes() {
        assertEquals(38, PlaceType.entries.size)
        for (entry in PlaceType.entries) {
            assertEquals(entry.korean, PlaceType.label(entry.code.uppercase(), korean = true))
            assertEquals(entry.english, PlaceType.label(" ${entry.code} ", korean = false))
            assertEquals(entry.group, PlaceType.group(entry.code))
        }
        assertEquals("Café", PlaceType.label("카페,디저트", korean = false))
        assertEquals("역·교통", PlaceType.label("지하철,전철", korean = true))
        assertNull(PlaceType.label("alien_category", korean = false))
        assertNull(PlaceType.label(null, korean = true))
        assertEquals(PlaceType.Group.OTHER, PlaceType.group("unknown"))
        assertEquals("숙소", PlaceType.pinKind("lodging"))
        assertEquals("거리·다리", PlaceType.pinKind("거리·다리"))
        assertNull(PlaceType.pinKind("unknown"))
    }

    @Test
    fun stayChoicesKeepCustomSavedDurationsSelectable() {
        assertEquals(RouteStop.STAY_OPTIONS, RouteStop.stayChoices(30))
        assertEquals(listOf(15, 20, 30, 45, 60, 90, 120, 180), RouteStop.stayChoices(20))
    }

    @Test
    fun poiCourseLabelsKeepTranslationAcrossCardRoundTrip() {
        val guide =
            poi(
                7,
            ).copy(name = "커피집", displayName = "Coffee House", nameRoman = "Keopijip", categoryLabel = "Cafe", displayAddress = "Seoul")
        val stop = guide.courseStop()
        assertEquals(PoiText("Coffee House", "Keopijip", "Cafe", "Seoul"), stop.poiText)
        assertEquals("Coffee House", stop.label(korean = false).title)
        assertEquals("커피집", stop.label(korean = false).reading)
        assertEquals("Cafe", stop.label(korean = false).category)
        assertEquals("Seoul", stop.label(korean = false).address)
        assertEquals("커피집", stop.label(korean = true).title)
        assertNull(stop.label(korean = true).reading)
        val card = stop.cardPlace()!!
        assertEquals(guide.displayName, card.displayName)
        assertEquals(guide.displayAddress, card.displayAddress)
        assertEquals(stop.poiText, card.courseStop().poiText)
    }

    @Test
    fun linkedFilmingPlaceKeepsItsIdentityAndOriginalTitle() {
        val stop = poi(7).copy(placeId = 99, displayName = "Translated").courseStop()
        assertEquals(99L, stop.savablePlaceId)
        assertNull(stop.poiId)
        assertNull(stop.poiText)
        assertEquals("POI 7", stop.label(korean = false).title)
    }

    @Test
    fun failedCartRemovalKeepsItemsAndDoesNotFetchAgain() =
        runBlocking {
            val client = FakeCartClient()
            val store = CartSession(client)
            store.refresh()
            client.removeFailure = IOException("offline")
            store.remove(1)
            assertEquals(listOf(1L), store.items.map { it.placeId })
            assertTrue(store.contains(1))
            assertEquals(1, client.fetches)
            assertTrue(store.toast != null)
        }

    @Test
    fun successfulCartRemovalStaysRemovedWhenRefreshFails() =
        runBlocking {
            val client = FakeCartClient()
            val store = CartSession(client)
            store.refresh()
            client.fetchFailure = IOException("offline after delete")
            store.remove(1)
            assertTrue(store.items.isEmpty())
            assertFalse(store.contains(1))
            assertEquals(2, client.fetches)
            assertTrue(store.toast != null)
        }

    private class FakeCartClient : CartClient {
        var fetches = 0
        var removeFailure: Exception? = null
        var fetchFailure: Exception? = null

        override fun fetch(): List<CartItem> {
            fetches++
            fetchFailure?.let { throw it }
            return listOf(CartItem(placeId = 1, name = "A", addedAt = OffsetDateTime.parse("2026-10-01T00:00:00Z")))
        }

        override fun add(
            placeId: Long,
            sourceContentId: Long?,
        ) = Unit

        override fun remove(placeId: Long) {
            removeFailure?.let { throw it }
        }
    }

    @Test
    fun onboardingKeepsAllPagesReachableAndCompletesOnce() {
        val progress = OnboardingProgress(4)
        progress.previous()
        assertEquals(0, progress.page)
        repeat(10) { progress.next() }
        assertEquals(3, progress.page)
        assertTrue(progress.isLast)
        progress.select(-2)
        assertEquals(0, progress.page)
        assertTrue(progress.finish())
        assertFalse(progress.finish())
        progress.next()
        assertEquals(0, progress.page)
    }

    @Test
    fun movementEstimateFollowsDwellOnlyWhileTargetsStayTheSame() {
        val a = RouteStop(place = PlaceSummary(id = 1, name = "A", latitude = 37.5, longitude = 127.0))
        val b = RouteStop(place = a.place.copy(id = 2, name = "B", longitude = 127.1), stayMinutes = 60)
        val estimate = RouteDayEstimate(listOf(a, b), 120)
        assertEquals(135, estimate.total(listOf(a.copy(stayMinutes = 45), b)))
        assertEquals(120, estimate.total(listOf(a.copy(visited = true), b)))
        assertNull(estimate.total(listOf(b, a)))
        assertNull(estimate.total(listOf(a.copy(poiId = 1), b)))
        assertNull(RouteDayEstimate(listOf(a, b), 20).total(listOf(a, b)))
    }

    // 편의시설은 묶지 않고 각각 실제 좌표에 그린다(MZ2AZ-482 C9, iOS 403 `PoiMarkerRulesTests`).
    @Test
    fun coincidentPoisRemainSeparateAtTheirOriginalCoordinates() {
        val places = (1L..12L).map { poi(it) }
        val result = PoiMarkerRules.validPlaces(places)
        assertEquals(places, result)
        assertEquals(12, result.map { it.id }.toSet().size)
    }

    @Test
    fun nearbyPoisKeepTheirOriginalCoordinatesAndOrder() {
        val places = listOf(poi(2), poi(1, longitude = 127.000001))
        assertEquals(places, PoiMarkerRules.validPlaces(places))
    }

    @Test
    fun invalidCoordinatesAndDuplicateIdsAreExcluded() {
        val valid = poi(1)
        val result =
            PoiMarkerRules.validPlaces(
                listOf(
                    valid,
                    valid,
                    poi(2, latitude = Double.NaN),
                    poi(3, longitude = Double.POSITIVE_INFINITY),
                    poi(4, latitude = 95.0),
                ),
            )
        assertEquals(listOf(valid), result)
        assertTrue(PoiMarkerRules.validPlaces(emptyList()).isEmpty())
    }

    private fun poi(
        id: Long,
        latitude: Double = 37.5,
        longitude: Double = 127.0,
    ): GuidePlace =
        GuidePlace(
            id = id,
            name = "POI $id",
            category = "카페",
            categoryGroup = PoiCategoryGroup.food,
            latitude = latitude,
            longitude = longitude,
            source = GuidePlaceSource.poi,
        )
}
