package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.PlaceType
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.naver.maps.geometry.LatLng

// 계약이 준 값을 화면 문자열로 만드는 자리.
//
// **자료형을 새로 만들지 않는다.** 계약에서 생성한 `ContentSummary`·`PlaceSummary`
// 를 그대로 쓴다 — 앱이 자기 모델을 따로 두면 계약이 바뀌었을 때 컴파일이 아니라
// 화면에서 드러난다. iOS 도 생성 타입을 그대로 쓴다.
//
// 조립 순서는 iOS `Rows.swift` 와 **같아야 한다.**

/** 지도에 꽂을 좌표. */
val PlaceSummary.position: LatLng get() = LatLng(latitude, longitude)

/** 작품 행 둘째 줄 — 방송사 · 연도 · 장르. */
val ContentSummary.meta: String
    get() =
        listOfNotNull(
            broadcaster,
            releaseYear?.toString(),
            genres?.joinToString(" "),
        ).filter { it.isNotEmpty() }.joinToString(" · ")

/** 장소 행 셋째 줄 — 이 장소가 나온 작품들 · 분류. */
val PlaceSummary.worksAndType: String
    get() =
        listOf(
            contents.orEmpty().joinToString(", ") { it.title },
            PlaceType.label(type, korean = AppLanguage.current == Lang.ko).orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * 카테고리 칩.
 *
 * **이름과 분류를 iOS `Models/SceneData.swift` 의 `CategoryChip` 에서 그대로
 * 가져온다.** 처음에는 「자연·문화·도시·식음료」 로 지어 썼는데, iOS 는
 * 「음식점·카페 / 명소·자연 / 거리·다리 / 건물·시설」 이라 **같은 장소가 서로 다른
 * 칩에 들어갔다.** 같은 데이터를 쓰는 두 앱이 다른 분류를 보여 주면 같은 앱이 아니다.
 *
 * 표에 없는 분류는 「기타」에 포함한다.
 */
object CategoryChip {
    const val ALL = "전체"
    val names: List<String> =
        listOf(ALL) +
            com.mz2az.scenetrip.data.PlaceType.Group.entries
                .map { it.label }

    fun of(placeType: String?): String =
        com.mz2az.scenetrip.data.PlaceType
            .group(placeType)
            .label
}
