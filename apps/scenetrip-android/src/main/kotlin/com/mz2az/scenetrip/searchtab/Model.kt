package com.mz2az.scenetrip.searchtab

import com.naver.maps.geometry.LatLng

/**
 * 검색 탭이 쓰는 자료형과 **임시 고정 데이터**.
 *
 * ## 이것은 API 클라이언트가 아니다
 *
 * 앱은 API 클라이언트를 손으로 쓰지 않는다 (CLAUDE.md §5). iOS 는 계약에서 생성한
 * `SceneApiClient` 를 쓰고, Android 도 같은 계약에서 생성한 코틀린 클라이언트를 쓸
 * 것이다 — `contracts/openapi/BUILD.bazel` 이 그 자리를 이미 비워 두고 있다.
 *
 * 아직 없는 이유는 기술적인 걸림돌 하나다. 생성기의 산출물은 **디렉터리(트리
 * 아티팩트)** 인데 `kt_android_library` 의 `srcs` 는 개별 파일을 요구하고, Bazel 은
 * 분석 시점에 트리 안을 열거하지 못한다. Swift 는 `merge_tree_sources` 로 한 파일에
 * 합쳐 피했지만 코틀린은 `package` 선언이 파일마다 있어 그 방법이 통하지 않는다.
 * srcjar 로 감싸는 길이 유력하며, 그것이 다음 작업이다.
 *
 * **그때까지 화면은 아래 고정 데이터로 짓는다.** 자료형 이름과 필드는 계약과 같게
 * 맞춰 두었으므로, 클라이언트가 들어오면 이 파일만 지우면 된다.
 */
data class PlaceSummary(
    val id: Long,
    val name: String,
    val type: String,
    val address: String,
    val lat: Double,
    val lng: Double,
) {
    val position: LatLng get() = LatLng(lat, lng)
}

data class ContentSummary(
    val id: Long,
    val title: String,
    val year: Int,
    val type: String,
    val placeCount: Int,
)

/**
 * 목록을 좁히는 칩. iOS `CategoryChip` 과 **같은 분류여야 한다** — 두 앱이 다른
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
        fun of(type: String): CategoryChip =
            when (type) {
                "자연", "해변", "산" -> NATURE
                "문화", "역사", "전통" -> CULTURE
                "카페", "식당" -> FOOD
                else -> CITY
            }
    }
}

/**
 * 임시 고정 데이터. 실제 촬영지 열 곳을 지역을 흩어 골랐다 — 서울만 넣으면
 * 첫 화면이 남한 전체인 것이 무의미해진다.
 */
object Fixtures {
    val places =
        listOf(
            PlaceSummary(1, "북촌한옥마을", "문화", "서울 종로구 계동길", 37.5826, 126.9831),
            PlaceSummary(2, "남산서울타워", "도시", "서울 용산구 남산공원길", 37.5512, 126.9882),
            PlaceSummary(3, "광화문광장", "역사", "서울 종로구 세종대로", 37.5720, 126.9769),
            PlaceSummary(4, "감천문화마을", "문화", "부산 사하구 감내2로", 35.0975, 129.0107),
            PlaceSummary(5, "청사포다릿돌전망대", "해변", "부산 해운대구 청사포로", 35.1585, 129.1959),
            PlaceSummary(6, "주문진 방파제", "해변", "강원 강릉시 주문진읍", 37.8925, 128.8318),
            PlaceSummary(7, "아바이마을", "문화", "강원 속초시 청호동", 38.2015, 128.5946),
            PlaceSummary(8, "동피랑벽화마을", "문화", "경남 통영시 동피랑1길", 34.8451, 128.4249),
            PlaceSummary(9, "섭지코지", "자연", "제주 서귀포시 성산읍", 33.4239, 126.9310),
            PlaceSummary(10, "카페 드 파리", "카페", "서울 마포구 와우산로", 37.5533, 126.9250),
        )

    val contents =
        listOf(
            ContentSummary(1, "도깨비", 2016, "드라마", 12),
            ContentSummary(2, "이태원 클라쓰", 2020, "드라마", 9),
            ContentSummary(3, "케이팝 데몬 헌터스", 2025, "영화", 7),
            ContentSummary(4, "우리들의 블루스", 2022, "드라마", 6),
        )
}
