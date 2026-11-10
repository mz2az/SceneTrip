package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.SceneFlipBack
import com.mz2az.scenetrip.newcourse.SceneFlipFaces
import com.mz2az.scenetrip.newcourse.SceneFlipRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 추천 코스 카드 사진 칸의 두 면 — 모든 카드가 뒤집히고, 없는 쪽은 「없음」 면
 * (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §6 표). iOS `SceneFlipRulesTests` 의 faces 시험을 옮겼다.
 *
 * | 카드 | 앞면 | 뒷면 |
 * | 사진 + 장면 그림 | 사진 | 장면 그림 |
 * | 장면 그림만 | 장면 그림(「작품」 속 장면) | 「장소 사진이 아직 없어요」 |
 * | 사진만 | 사진 | 「장면 그림이 아직 없어요」 |
 * | 둘 다 없음 | 빈 칸(「장소 사진이 아직 없어요」) | 「장면 그림이 아직 없어요」 |
 */
class SceneFlipFacesTests {
    private val photo = "https://img.example.com/place.jpg"
    private val sceneArt = "https://img.example.com/scene.png"

    @Test
    fun photoAndSceneFrontPhotoBackScene() {
        assertEquals(
            SceneFlipFaces(front = photo, back = SceneFlipBack.Scene(sceneArt), frontIsScene = false),
            SceneFlipRules.faces(photoUrl = photo, sceneUrl = sceneArt),
        )
    }

    @Test
    fun sceneOnlyFrontSceneBackNoPhoto() {
        assertEquals(
            SceneFlipFaces(front = sceneArt, back = SceneFlipBack.NoPhoto, frontIsScene = true),
            SceneFlipRules.faces(photoUrl = null, sceneUrl = sceneArt),
        )
    }

    @Test
    fun photoOnlyFrontPhotoBackNoScene() {
        assertEquals(
            SceneFlipFaces(front = photo, back = SceneFlipBack.NoScene, frontIsScene = false),
            SceneFlipRules.faces(photoUrl = photo, sceneUrl = null),
        )
    }

    /** 둘 다 없어도 뒤집힌다 — 앞은 빈 칸, 뒤는 「장면 그림이 아직 없어요」. */
    @Test
    fun neitherEmptyFrontBackNoScene() {
        assertEquals(
            SceneFlipFaces(front = null, back = SceneFlipBack.NoScene, frontIsScene = false),
            SceneFlipRules.faces(photoUrl = null, sceneUrl = null),
        )
    }

    @Test
    fun httpPhotoCountsAsPhoto() {
        val plain = "http://img.example.com/place.jpg"
        assertEquals(
            SceneFlipFaces(front = plain, back = SceneFlipBack.Scene(sceneArt), frontIsScene = false),
            SceneFlipRules.faces(photoUrl = plain, sceneUrl = sceneArt),
        )
        assertEquals(
            SceneFlipFaces(front = plain, back = SceneFlipBack.NoScene, frontIsScene = false),
            SceneFlipRules.faces(photoUrl = plain, sceneUrl = null),
        )
    }

    @Test
    fun emptyPhotoTreatedAsMissing() {
        assertEquals(
            SceneFlipFaces(front = sceneArt, back = SceneFlipBack.NoPhoto, frontIsScene = true),
            SceneFlipRules.faces(photoUrl = "", sceneUrl = sceneArt),
        )
        assertEquals(
            SceneFlipFaces(front = null, back = SceneFlipBack.NoScene, frontIsScene = false),
            SceneFlipRules.faces(photoUrl = "", sceneUrl = null),
        )
    }

    /** http(s) 가 아닌 주소는 쓸 수 없는 사진 — 없는 것과 같다. */
    @Test
    fun nonHttpPhotoTreatedAsMissing() {
        for (bad in listOf("ftp://img.example.com/place.jpg", "file:///tmp/place.jpg", "place.jpg", "not a url")) {
            assertEquals(
                SceneFlipFaces(front = sceneArt, back = SceneFlipBack.NoPhoto, frontIsScene = true),
                SceneFlipRules.faces(photoUrl = bad, sceneUrl = sceneArt),
                "사진 주소 $bad",
            )
            assertEquals(
                SceneFlipFaces(front = null, back = SceneFlipBack.NoScene, frontIsScene = false),
                SceneFlipRules.faces(photoUrl = bad, sceneUrl = null),
                "사진 주소 $bad",
            )
        }
    }

    /** 호스트가 없는 http(s) 주소도 쓸 수 없는 사진. */
    @Test
    fun photoWithoutHostTreatedAsMissing() {
        for (bad in listOf("https://", "http://", "https:///place.jpg")) {
            assertEquals(
                SceneFlipFaces(front = sceneArt, back = SceneFlipBack.NoPhoto, frontIsScene = true),
                SceneFlipRules.faces(photoUrl = bad, sceneUrl = sceneArt),
                "사진 주소 $bad",
            )
            assertEquals(
                SceneFlipFaces(front = null, back = SceneFlipBack.NoScene, frontIsScene = false),
                SceneFlipRules.faces(photoUrl = bad, sceneUrl = null),
                "사진 주소 $bad",
            )
        }
    }
}
