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
 * 카테고리 칩.
 *
 * **이름과 분류를 iOS `Models/SceneData.swift` 의 `CategoryChip` 에서 그대로
 * 가져온다.** 처음에는 「자연·문화·도시·식음료」 로 지어 썼는데, iOS 는
 * 「음식점·카페 / 명소·자연 / 거리·다리 / 건물·시설」 이라 **같은 장소가 서로 다른
 * 칩에 들어갔다.** 같은 데이터를 쓰는 두 앱이 다른 분류를 보여 주면 같은 앱이 아니다.
 *
 * 어느 쪽도 아닌 분류는 「건물·시설」 로 떨어진다 — iOS 와 같은 기본값이다.
 */
object CategoryChip {
    const val ALL = "전체"

    private val groups: List<Pair<String, Set<String>>> =
        listOf(
            "음식점·카페" to setOf("음식점", "카페", "바", "편의점", "마트", "시장"),
            "명소·자연" to
                setOf(
                    "명소",
                    "자연",
                    "공원",
                    "해변",
                    "항구",
                    "전망대",
                    "사찰",
                    "성당",
                    "고궁",
                    "한옥",
                    "한옥마을",
                    "마을",
                    "테마파크",
                    "체험시설",
                    "캠핑장",
                ),
            "거리·다리" to setOf("거리", "다리", "역/교통", "공항"),
            "건물·시설" to
                setOf(
                    "건물",
                    "호텔",
                    "병원",
                    "학교",
                    "박물관/미술관",
                    "서점",
                    "상점",
                    "백화점",
                    "쇼핑몰",
                    "경기장",
                    "스포츠시설",
                    "예식장",
                    "장례식장",
                    "세트장",
                    "관공서",
                ),
        )

    val names: List<String> = listOf(ALL) + groups.map { it.first }

    fun of(placeType: String?): String {
        if (placeType == null) return "건물·시설"
        return groups.firstOrNull { placeType in it.second }?.first ?: "건물·시설"
    }
}
