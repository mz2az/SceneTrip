package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.SceneFlipRules
import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * 추천 코스 카드 — 사진을 누르면 뒤집혀 장면 그림 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §6).
 * 어떤 그림을 고를지, 처음 한 번 살짝 돌기를 어느 카드가 할지.
 */
class SceneFlipRulesTests {
    private fun scene(
        contentId: Long,
        vararg urls: String,
    ) = Scene(contentId, "작품$contentId", sceneImageUrls = urls.map(URI::create))

    private fun detail(vararg scenes: Scene) =
        PlaceDetail(
            id = 1,
            name = "촬영지",
            latitude = 37.5,
            longitude = 127.0,
            contents = emptyList(),
            scenes = scenes.toList(),
        )

    private fun url(s: String) = URI.create(s)

    // ---- 정해진 값 ----

    @Test
    fun timingsAndLimitsMatchPlan() {
        assertEquals(450L, SceneFlipRules.FLIP_MILLIS)
        assertEquals(600L, SceneFlipRules.PEEK_DELAY_MILLIS)
        assertEquals(25f, SceneFlipRules.PEEK_DEGREES)
        assertEquals(200L, SceneFlipRules.REDUCED_MOTION_FADE_MILLIS)
        assertEquals(4, SceneFlipRules.MAX_CONCURRENT_DETAIL_FETCHES)
    }

    // ---- 어떤 그림 ----

    @Test
    fun picksFirstPictureOfTheCardsWork() {
        val d =
            detail(
                scene(10, "https://example.com/other.jpg"),
                scene(20, "https://example.com/work-1.jpg", "https://example.com/work-2.jpg"),
            )
        assertEquals(url("https://example.com/work-1.jpg"), SceneFlipRules.sceneImageUrl(d, workId = 20))
    }

    @Test
    fun matchingSceneWithoutPicturesFallsBackToFirstSceneWithPictures() {
        val d =
            detail(
                scene(10),
                scene(20),
                scene(30, "https://example.com/third-1.jpg", "https://example.com/third-2.jpg"),
                scene(40, "https://example.com/fourth.jpg"),
            )
        assertEquals(url("https://example.com/third-1.jpg"), SceneFlipRules.sceneImageUrl(d, workId = 20))
    }

    @Test
    fun missingWorkIdUsesFirstSceneWithPictures() {
        val d = detail(scene(10), scene(20, "https://example.com/b.jpg"), scene(30, "https://example.com/c.jpg"))
        assertEquals(url("https://example.com/b.jpg"), SceneFlipRules.sceneImageUrl(d, workId = null))
    }

    @Test
    fun workNotAmongScenesUsesFirstSceneWithPictures() {
        val d = detail(scene(10, "https://example.com/a.jpg"), scene(20, "https://example.com/b.jpg"))
        assertEquals(url("https://example.com/a.jpg"), SceneFlipRules.sceneImageUrl(d, workId = 99))
    }

    @Test
    fun cardsWorkWinsOverEarlierSceneWithPictures() {
        // 앞 장면에 그림이 있어도 카드의 작품 장면이 먼저다.
        val d = detail(scene(10, "https://example.com/a.jpg"), scene(20, "https://example.com/b.jpg"))
        assertEquals(url("https://example.com/b.jpg"), SceneFlipRules.sceneImageUrl(d, workId = 20))
    }

    @Test
    fun noScenesMeansNoPicture() {
        assertNull(SceneFlipRules.sceneImageUrl(detail(), workId = 10))
        assertNull(SceneFlipRules.sceneImageUrl(detail(), workId = null))
    }

    @Test
    fun scenesWithoutAnyPictureMeansNoPicture() {
        assertNull(SceneFlipRules.sceneImageUrl(detail(scene(10), scene(20)), workId = 10))
        assertNull(SceneFlipRules.sceneImageUrl(detail(scene(10), scene(20)), workId = null))
    }

    // ---- 처음 한 번 살짝 돌기 ----

    @Test
    fun peeksFirstFlippableCard() {
        assertEquals(0, SceneFlipRules.peekIndex(listOf(true, true, false), hasFlipped = false, reduceMotion = false))
    }

    @Test
    fun peeksFirstFlippableEvenWhenNotFirstCard() {
        assertEquals(2, SceneFlipRules.peekIndex(listOf(false, false, true, true), hasFlipped = false, reduceMotion = false))
    }

    @Test
    fun noPeekOnceUserHasFlipped() {
        assertNull(SceneFlipRules.peekIndex(listOf(true, true), hasFlipped = true, reduceMotion = false))
    }

    @Test
    fun noPeekWithReducedMotion() {
        assertNull(SceneFlipRules.peekIndex(listOf(true, true), hasFlipped = false, reduceMotion = true))
        assertNull(SceneFlipRules.peekIndex(listOf(true), hasFlipped = true, reduceMotion = true))
    }

    @Test
    fun noPeekWithoutFlippableCards() {
        assertNull(SceneFlipRules.peekIndex(listOf(false, false), hasFlipped = false, reduceMotion = false))
        assertNull(SceneFlipRules.peekIndex(emptyList(), hasFlipped = false, reduceMotion = false))
    }
}
