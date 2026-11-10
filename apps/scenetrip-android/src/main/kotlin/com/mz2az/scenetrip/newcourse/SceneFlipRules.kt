package com.mz2az.scenetrip.newcourse

import com.mz2az.scenetrip.sceneapi.client.model.PlaceDetail
import java.net.URI

// 추천 코스 카드 — 사진을 누르면 뒤집혀 장면 그림을 보이는 규칙
// (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §6).
// 화면은 이 규칙만 따른다 — 어떤 그림을 고를지, 처음 한 번 살짝 돌기를 할지는 여기서 정한다.
// TODO(MZ2AZ-547): 시험 먼저(TEST 단계) — 아래는 아직 틀린 값을 돌려주는 자리표시다.

object SceneFlipRules {
    /** 사진 칸이 세로 축으로 도는 시간(ms). */
    const val FLIP_MILLIS = 0L

    /** 화면이 처음 뜬 뒤 첫 카드가 살짝 돌기까지(ms). */
    const val PEEK_DELAY_MILLIS = 0L

    /** 처음 한 번 살짝 도는 각도(도). */
    const val PEEK_DEGREES = 0f

    /** 동작 줄이기일 때 도는 대신 서서히 바뀌는 시간(ms). */
    const val REDUCED_MOTION_FADE_MILLIS = 0L

    /** `GET /places/{placeId}` 를 동시에 받는 최대 수. */
    const val MAX_CONCURRENT_DETAIL_FETCHES = 0

    /**
     * 카드 뒷면의 장면 그림. [detail] 의 `scenes` 가운데 `contentId` 가 [workId](카드의 `contents[0].id`)인 장면의
     * `sceneImageUrls` 첫 장. 그 장면에 그림이 없거나 [workId] 가 없으면 그림이 있는 첫 장면의 첫 장. 아무 데도 없으면 null(뒤집히지 않는다).
     */
    @Suppress("UNUSED_PARAMETER")
    fun sceneImageUrl(
        detail: PlaceDetail,
        workId: Long?,
    ): URI? = null

    /**
     * 처음 한 번 살짝 돌 카드의 자리. [flippable] 은 카드 순서대로 뒤집힐 수 있는지.
     * 한 번이라도 뒤집었으면([hasFlipped]), 동작 줄이기면([reduceMotion]), 뒤집히는 카드가 없으면 null.
     */
    @Suppress("UNUSED_PARAMETER")
    fun peekIndex(
        flippable: List<Boolean>,
        hasFlipped: Boolean,
        reduceMotion: Boolean,
    ): Int? = null
}
