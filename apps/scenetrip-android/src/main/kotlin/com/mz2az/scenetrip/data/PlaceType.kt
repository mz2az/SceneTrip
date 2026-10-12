package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.Lang

/** 촬영지 분류 코드·옛 이름을 같은 표시말과 검색 갈래로 바꾼다. */
object PlaceType {
    enum class Group(
        val label: String,
    ) {
        FOOD("음식점·카페"),
        SIGHT("명소·자연"),
        STREET("거리·다리"),
        FACILITY("건물·시설"),
        OTHER("기타"),
    }

    data class Entry(
        val code: String,
        val korean: String,
        val english: String,
        val group: Group,
    )

    val entries =
        listOf(
            Entry("restaurant", "음식점", "Restaurant", Group.FOOD),
            Entry("cafe", "카페", "Café", Group.FOOD),
            Entry("bar", "바", "Bar", Group.FOOD),
            Entry("market", "시장", "Market", Group.FOOD),
            Entry("store_daily", "편의점·마트", "Store", Group.FOOD),
            Entry("landmark", "명소", "Landmark", Group.SIGHT),
            Entry("nature", "자연", "Nature", Group.SIGHT),
            Entry("park", "공원", "Park", Group.SIGHT),
            Entry("beach", "해변", "Beach", Group.SIGHT),
            Entry("port", "항구", "Port", Group.SIGHT),
            Entry("viewpoint", "전망대", "Viewpoint", Group.SIGHT),
            Entry("temple", "사찰", "Temple", Group.SIGHT),
            Entry("church", "성당", "Church", Group.SIGHT),
            Entry("palace", "고궁", "Palace", Group.SIGHT),
            Entry("hanok", "한옥", "Hanok", Group.SIGHT),
            Entry("village", "마을", "Village", Group.SIGHT),
            Entry("theme_park", "테마파크", "Theme park", Group.SIGHT),
            Entry("experience", "체험시설", "Activity", Group.SIGHT),
            Entry("camping", "캠핑장", "Campsite", Group.SIGHT),
            Entry("street", "거리", "Street", Group.STREET),
            Entry("bridge", "다리", "Bridge", Group.STREET),
            Entry("station", "역·교통", "Station", Group.STREET),
            Entry("airport", "공항", "Airport", Group.STREET),
            Entry("building", "건물", "Building", Group.FACILITY),
            Entry("hotel", "호텔", "Hotel", Group.FACILITY),
            Entry("hospital", "병원", "Hospital", Group.FACILITY),
            Entry("school", "학교", "School", Group.FACILITY),
            Entry("museum", "박물관·미술관", "Museum", Group.FACILITY),
            Entry("bookstore", "서점", "Bookstore", Group.FACILITY),
            Entry("shop", "상점", "Shop", Group.FACILITY),
            Entry("mall", "쇼핑몰", "Shopping mall", Group.FACILITY),
            Entry("stadium", "경기장", "Stadium", Group.FACILITY),
            Entry("wedding_hall", "예식장", "Wedding hall", Group.FACILITY),
            Entry("funeral_hall", "장례식장", "Funeral hall", Group.FACILITY),
            Entry("set", "세트장", "Film set", Group.FACILITY),
            Entry("public_office", "관공서", "Public office", Group.FACILITY),
            Entry("theater", "극장·공연장", "Theater", Group.FACILITY),
            Entry("library", "도서관", "Library", Group.FACILITY),
        )

    private val legacy =
        mapOf(
            "음식점" to "restaurant",
            "카페" to "cafe",
            "바" to "bar",
            "시장" to "market",
            "편의점" to "store_daily",
            "마트" to "store_daily",
            "자연" to "nature",
            "공원" to "park",
            "해변" to "beach",
            "항구" to "port",
            "전망대" to "viewpoint",
            "사찰" to "temple",
            "성당" to "church",
            "고궁" to "palace",
            "한옥" to "hanok",
            "한옥마을" to "hanok",
            "마을" to "village",
            "테마파크" to "theme_park",
            "체험시설" to "experience",
            "캠핑장" to "camping",
            "명소" to "landmark",
            "거리" to "street",
            "다리" to "bridge",
            "역/교통" to "station",
            "공항" to "airport",
            "건물" to "building",
            "호텔" to "hotel",
            "병원" to "hospital",
            "학교" to "school",
            "박물관/미술관" to "museum",
            "서점" to "bookstore",
            "상점" to "shop",
            "백화점" to "mall",
            "쇼핑몰" to "mall",
            "경기장" to "stadium",
            "스포츠시설" to "stadium",
            "예식장" to "wedding_hall",
            "장례식장" to "funeral_hall",
            "세트장" to "set",
            "관공서" to "public_office",
            "극장" to "theater",
            "공연장" to "theater",
            "도서관" to "library",
            "촬영장소" to "landmark",
            "페리,해운" to "port",
            "생선회" to "restaurant",
            "막국수" to "restaurant",
            "촬영지" to "landmark",
            "호수,연못,저수지" to "nature",
            "골목길" to "street",
            "영상테마파크" to "set",
            "문화센터" to "building",
            "기업,빌딩" to "building",
            "근린공원" to "park",
            "복합문화공간" to "building",
            "고등학교" to "school",
            "지역" to "street",
            "곰탕,설렁탕" to "restaurant",
            "갤러리카페" to "cafe",
            "관광농원,팜스테이" to "nature",
            "한식" to "restaurant",
            "중식" to "restaurant",
            "일식" to "restaurant",
            "양식" to "restaurant",
            "아시아음식" to "restaurant",
            "분식" to "restaurant",
            "패스트푸드" to "restaurant",
            "카페,디저트" to "cafe",
            "술집" to "bar",
            "여행,명소" to "landmark",
            "숙박" to "hotel",
            "교통시설" to "station",
            "문화,예술" to "museum",
            "쇼핑,유통" to "shop",
            "생활,편의" to "store_daily",
            "교육,학문" to "school",
            "의료" to "hospital",
            "공공,사회기관" to "public_office",
            "스포츠,오락" to "stadium",
            "자연명소" to "nature",
            "지역명소" to "landmark",
            "자연공원" to "landmark",
            "시민공원" to "landmark",
            "광장" to "landmark",
            "궁궐" to "landmark",
            "박물관" to "landmark",
            "문화,유적" to "landmark",
            "체험마을" to "landmark",
            "강,하천" to "landmark",
            "지하철,전철" to "station",
            "교량명" to "bridge",
        )
    private val byCode = entries.associateBy { it.code }
    val pinKinds = listOf("숙소") + Group.entries.filter { it != Group.OTHER }.map { it.label }
    private val pinCodes =
        mapOf(
            "lodging" to "숙소",
            "food" to Group.FOOD.label,
            "attraction" to Group.SIGHT.label,
            "street" to Group.STREET.label,
            "building" to Group.FACILITY.label,
        )

    fun entry(raw: String?): Entry? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return byCode[value.lowercase(java.util.Locale.ROOT)] ?: legacy[value]?.let { byCode[it] }
    }

    fun label(
        raw: String?,
        korean: Boolean = AppLanguage.current == Lang.ko,
    ): String? = entry(raw)?.let { if (korean) it.korean else it.english }

    fun group(raw: String?): Group = entry(raw)?.group ?: Group.OTHER

    fun pinKind(raw: String?): String? = if (raw in pinKinds) raw else pinCodes[raw]

    fun stopLabel(
        raw: String?,
        pinned: Boolean,
        korean: Boolean = AppLanguage.current == Lang.ko,
    ): String? = if (pinned) pinKind(raw)?.let { tr(it) } else label(raw, korean)
}
