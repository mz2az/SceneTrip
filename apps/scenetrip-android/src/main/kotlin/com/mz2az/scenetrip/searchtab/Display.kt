package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
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
            type.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * 카테고리 칩. iOS `CategoryChip` 과 **같은 분류여야 한다** — 두 앱이 다른
 * 카테고리를 보여 주면 같은 데이터를 쓰는 의미가 없다 (MZ2AZ-196 이 그 문제다).
 */
enum class CategoryChip(
    val label: String,
) {
    ALL("전체"),
    NATURE("자연"),
    CULTURE("문화"),
    CITY("도시"),
    FOOD("식음료"),
    ;

    companion object {
        fun of(type: String?): CategoryChip =
            when (type) {
                "자연", "해변", "산", "공원" -> NATURE
                "문화", "역사", "전통", "박물관" -> CULTURE
                "카페", "식당", "음식점" -> FOOD
                else -> CITY
            }
    }
}
