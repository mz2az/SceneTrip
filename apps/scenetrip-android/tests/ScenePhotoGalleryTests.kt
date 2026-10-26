package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.searchtab.ScenePhotoRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** 장면 사진 여러 장 (MZ2AZ-430). iOS `ScenePhotoRulesTests` 의 배열 규칙과 같은 값이다. */
class ScenePhotoGalleryTests {
    private fun scene(
        urls: List<String>,
        legacy: String? = null,
        poster: String? = null,
    ) = Scene(
        7,
        "작품",
        sceneImageUrl = legacy?.let(URI::create),
        sceneImageUrls = urls.map(URI::create),
        posterUrl = poster?.let(URI::create),
    )

    @Test
    fun arrayPreservesServerOrderIncludingRepeatedImages() {
        val urls = listOf("https://example.com/two.jpg", "https://example.com/one.jpg", "https://example.com/two.jpg")
        val scene = scene(urls, legacy = "https://example.com/legacy.jpg")
        assertEquals(urls, ScenePhotoRules.photos(scene))
        assertEquals(urls[0], ScenePhotoRules.photo(scene))
    }

    @Test
    fun emptyArrayNeverFallsBackToLegacyImageOrPoster() {
        val scene = scene(emptyList(), legacy = "https://example.com/legacy.jpg", poster = "https://example.com/poster.jpg")
        assertTrue(ScenePhotoRules.photos(scene).isEmpty())
        assertNull(ScenePhotoRules.photo(scene))
        assertNull(ScenePhotoRules.countBadge(scene))
    }

    @Test
    fun invalidEntriesAreSkippedWithoutReorderingValidPhotos() {
        val scene =
            scene(listOf("file:///tmp/local.jpg", "https://example.com/one.jpg", "ftp://example.com/x.jpg", "https://example.com/two.jpg"))
        assertEquals(listOf("https://example.com/one.jpg", "https://example.com/two.jpg"), ScenePhotoRules.photos(scene))
    }

    @Test
    fun singlePhotoKeepsTheOldBehaviorWithoutCountOrButtons() {
        val scene = scene(listOf("https://example.com/one.jpg"))
        assertEquals("https://example.com/one.jpg", ScenePhotoRules.photo(scene))
        assertNull(ScenePhotoRules.countBadge(scene))
    }

    @Test
    fun countBadgeShowsFirstPhotoOfTotalAndHasEnglishForTheViewerStrings() {
        val scene = scene(listOf("https://example.com/a.jpg", "https://example.com/b.jpg", "https://example.com/c.jpg"))
        assertEquals("사진 1 / 3", ScenePhotoRules.countBadge(scene))
        assertEquals("Photo 1 of 3", requireNotNull(englishOf("사진 %d / %d")).format(1, 3))
        assertEquals("Previous photo", englishOf("이전 사진"))
        assertEquals("Next photo", englishOf("다음 사진"))
    }

    @Test
    fun stepsStopAtBothEndsAndTolerateNoPhotos() {
        assertEquals(1, ScenePhotoRules.step(0, 1, 3))
        assertEquals(2, ScenePhotoRules.step(1, 1, 3))
        assertEquals(2, ScenePhotoRules.step(2, 1, 3))
        assertEquals(0, ScenePhotoRules.step(0, -1, 3))
        assertEquals(0, ScenePhotoRules.step(5, 0, 0))
    }

    @Test
    fun lateResponsesFromPagesNoLongerShownAreIgnored() {
        val page = 1 to "https://example.com/two.jpg"
        assertTrue(ScenePhotoRules.accepts(page, page))
        assertFalse(ScenePhotoRules.accepts(page, 2 to "https://example.com/two.jpg"), "같은 주소가 반복돼도 번호가 다르면 다른 쪽")
        assertFalse(ScenePhotoRules.accepts(page, 1 to "https://example.com/other.jpg"))
    }
}
