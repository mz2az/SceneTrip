package com.mz2az.scenetrip.newcourse

import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import java.net.URI

// 추천 코스 카드 — 사진을 누르면 뒤집혀 장면 그림을 보이는 규칙
// (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §6).
// 화면은 이 규칙만 따른다 — 어떤 그림을 고를지, 처음 한 번 살짝 돌기를 할지는 여기서 정한다.

object SceneFlipRules {
    /** 사진 칸이 세로 축으로 도는 시간(ms). */
    const val FLIP_MILLIS = 450L

    /** 화면이 처음 뜬 뒤 첫 카드가 살짝 돌기까지(ms). */
    const val PEEK_DELAY_MILLIS = 600L

    /** 처음 한 번 살짝 도는 각도(도). */
    const val PEEK_DEGREES = 25f

    /** 동작 줄이기일 때 도는 대신 서서히 바뀌는 시간(ms). */
    const val REDUCED_MOTION_FADE_MILLIS = 200L

    /** `GET /places/{placeId}` 를 동시에 받는 최대 수. */
    const val MAX_CONCURRENT_DETAIL_FETCHES = 4

    /**
     * 카드 뒷면의 장면 그림. [detail] 의 `scenes` 가운데 `contentId` 가 [workId](카드의 `contents[0].id`)인 장면의
     * `sceneImageUrls` 첫 장. 그 장면에 그림이 없거나 [workId] 가 없으면 그림이 있는 첫 장면의 첫 장. 아무 데도 없으면 null(뒤집히지 않는다).
     */
    fun sceneImageUrl(
        detail: PlaceDetail,
        workId: Long?,
    ): URI? {
        val scenes = detail.scenes.orEmpty()
        val ofWork = workId?.let { id -> scenes.firstOrNull { it.contentId == id } }?.sceneImageUrls?.firstOrNull()
        return ofWork ?: scenes.firstOrNull { it.sceneImageUrls.isNotEmpty() }?.sceneImageUrls?.firstOrNull()
    }

    /**
     * 카드 사진 칸의 두 면 — 장소 사진([photoUrl], 쓸 수 없는 주소면 없는 것으로 — [NewCourseText.usablePhoto])과 장면 그림([sceneUrl]).
     * 모든 카드가 뒤집힌다. 앞면은 장소 사진, 없으면 장면 그림, 둘 다 없으면 빈 칸(「장소 사진이 아직 없어요」).
     * 뒷면은 앞면에 쓰지 않은 쪽, 없으면 「없음」 면(§6 표).
     */
    fun faces(
        photoUrl: String?,
        sceneUrl: String?,
    ): SceneFlipFaces {
        val photo = photoUrl?.takeIf { NewCourseText.usablePhoto(it) }
        return when {
            photo != null && sceneUrl != null -> SceneFlipFaces(front = photo, back = SceneFlipBack.Scene(sceneUrl), frontIsScene = false)
            sceneUrl != null -> SceneFlipFaces(front = sceneUrl, back = SceneFlipBack.NoPhoto, frontIsScene = true)
            else -> SceneFlipFaces(front = photo, back = SceneFlipBack.NoScene, frontIsScene = false)
        }
    }

    /**
     * 처음 한 번 살짝 돌 카드의 자리. [flippable] 은 카드 순서대로 뒤집힐 수 있는지.
     * 한 번이라도 뒤집었으면([hasFlipped]), 동작 줄이기면([reduceMotion]), 뒤집히는 카드가 없으면 null.
     */
    fun peekIndex(
        flippable: List<Boolean>,
        hasFlipped: Boolean,
        reduceMotion: Boolean,
    ): Int? {
        if (hasFlipped || reduceMotion) return null
        return flippable.indexOf(true).takeIf { it >= 0 }
    }
}

/** 카드 사진 칸의 두 면. */
data class SceneFlipFaces(
    /** 앞면 그림 주소 — 없으면 빈 칸(「장소 사진이 아직 없어요」). */
    val front: String?,
    val back: SceneFlipBack,
    /** 앞면이 장면 그림인가(장소 사진이 없어 대신 보일 때) — 알약을 「「작품」 속 장면」 으로. */
    val frontIsScene: Boolean,
)

/** 카드 뒷면. */
sealed class SceneFlipBack {
    /** 장면 그림. */
    data class Scene(
        val url: String,
    ) : SceneFlipBack()

    /** 「장소 사진이 아직 없어요」 — 장면 그림이 앞면에 갔을 때. */
    data object NoPhoto : SceneFlipBack()

    /** 「장면 그림이 아직 없어요」. */
    data object NoScene : SceneFlipBack()
}
