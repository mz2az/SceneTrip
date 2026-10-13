package com.mz2az.scenetrip

import com.mz2az.scenetrip.hometab.HomeShelf
import com.mz2az.scenetrip.sceneapi.client.model.ContentCategory
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LatestIosSyncTests {
    @Test
    fun homePreservesServerPopularityEvenWhenPlaceCountsDisagree() {
        val server = listOf(work(7, 1), work(3, 50), work(9, 2))
        assertEquals(server, HomeShelf.from(server))
    }

    @Test
    fun homeUsesOnlyTheFirstTwentyServerRows() {
        val server = (1L..28L).map { work(it, it.toInt()) }
        assertEquals(server.take(20), HomeShelf.from(server))
        assertEquals(emptyList<ContentSummary>(), HomeShelf.from(emptyList()))
    }

    @Test
    fun producerLinkUsesMatchingDetailEvenWhenItClearsTheSummaryUrl() {
        val summary = work(7, 1).copy(producerUrl = java.net.URI("https://producer.example/work"))
        assertEquals(
            summary.producerUrl.toString(),
            com.mz2az.scenetrip.searchtab.ProducerLink
                .destination(summary, null),
        )
        val detail =
            com.mz2az.scenetrip.sceneapi.client.model.ContentDetail(
                id = 7,
                category = ContentCategory.drama,
                title = "Work",
                placeCount = 1,
                producerUrl = null,
            )
        assertEquals(
            null,
            com.mz2az.scenetrip.searchtab.ProducerLink
                .destination(summary, detail),
        )
        assertEquals(
            summary.producerUrl.toString(),
            com.mz2az.scenetrip.searchtab.ProducerLink
                .destination(summary, detail.copy(id = 8)),
        )
        assertEquals(
            "http://producer.example/official",
            com.mz2az.scenetrip.searchtab.ProducerLink.destination(
                summary,
                detail.copy(producerUrl = java.net.URI("http://producer.example/official")),
            ),
        )
    }

    @Test
    fun webLinksRejectUnsafeMalformedAndCredentialUrlsWithoutChangingSignedQueries() {
        for (url in listOf(
            null,
            "",
            "file:///photo",
            "javascript:alert(1)",
            "https://",
            "https://user:pass@example.com/a",
            "https://example.com/bad%zz",
            "https://example.com/a b",
        )) {
            assertEquals(
                null,
                com.mz2az.scenetrip.data.WebAddress
                    .url(url),
            )
        }
        val signed = "http://localhost:9000/photo/a%2Fb?X-Amz-Signature=example&key=%2F%2b+"
        assertEquals(
            signed,
            com.mz2az.scenetrip.data.WebAddress
                .url(signed),
        )
    }

    @Test
    fun sceneUsesOnlyItsActualPhotoAndZoomBoundsAreFinite() {
        val scene =
            com.mz2az.scenetrip.sceneapi.client.model.Scene(
                1,
                "Scene",
                posterUrl = java.net.URI("https://producer.example/poster"),
            )
        assertEquals(
            null,
            com.mz2az.scenetrip.searchtab.ScenePhotoRules
                .photo(scene),
        )
        assertEquals(
            "https://producer.example/scene",
            com.mz2az.scenetrip.searchtab.ScenePhotoRules
                .photo(scene.copy(sceneImageUrl = java.net.URI("https://producer.example/scene"))),
        )
        val rules = com.mz2az.scenetrip.searchtab.ScenePhotoRules
        assertEquals(1f, rules.scale(Float.NaN))
        assertEquals(1f, rules.scale(Float.POSITIVE_INFINITY))
        assertEquals(1f, rules.scale(0.1f))
        assertEquals(4f, rules.scale(9f))
        assertEquals(400f to 200f, rules.fittedSize(800f, 400f, 400f, 800f))
        assertEquals(0f to 0f, rules.fittedSize(0f, 400f, 400f, 800f))
    }

    @Test
    fun zoomCannotPanBeyondPhotoAndResetRestoresItsWholeFrame() {
        val initial =
            com.mz2az.scenetrip.searchtab
                .SceneZoom()
        val moved = initial.transformed(8f, 9000f, -9000f, 800f, 400f, 400f, 800f)
        assertEquals(4f, moved.scale)
        assertEquals(600f, moved.x)
        assertEquals(0f, moved.y)
        assertEquals(initial, moved.transformed(1f, 40f, 80f, 800f, 400f, 400f, 800f))
        assertEquals(2f, initial.doubleTapScale())
        assertEquals(1f, moved.doubleTapScale())
    }

    @Test
    fun persistedThemeDefaultsToSystemAndOverridesItOnlyWhenSelected() {
        val modes = com.mz2az.scenetrip.data.AppThemeMode
        assertEquals(com.mz2az.scenetrip.data.AppThemeMode.SYSTEM, modes.from(null))
        assertEquals(com.mz2az.scenetrip.data.AppThemeMode.SYSTEM, modes.from("unsupported"))
        assertEquals(true, modes.from("system").isDark(true))
        assertEquals(false, modes.from("light").isDark(true))
        assertEquals(true, modes.from("dark").isDark(false))
    }

    private fun work(
        id: Long,
        count: Int,
    ) = ContentSummary(id = id, title = "Work $id", category = ContentCategory.drama, placeCount = count)
}
