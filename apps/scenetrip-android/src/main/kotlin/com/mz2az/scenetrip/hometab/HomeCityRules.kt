package com.mz2az.scenetrip.hometab

import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity

/**
 * 도시 대표 사진 한 장 — 앱에 싣는 고정 8장 (MZ2AZ-547 H2, 웹 `proto/protoCovers.ts`, 사용자 선택 2026-11-09).
 *
 * [asset] 은 `res/drawable-nodpi` 의 파일 이름(확장자 없이)이다 — 화면이 `R.drawable` 로 바꾼다. [placeId] 는 DEV·PRD 같은 촬영지 번호.
 */
data class CityCover(
    /** 도시 열쇠 — `GET /places/cities` 의 `key`(한국어 이름). */
    val key: String,
    val cityKo: String,
    val cityEn: String,
    val placeId: Long,
    val placeKo: String,
    val placeEn: String,
    val workKo: String,
    val workEn: String,
    val asset: String,
)

object CityCovers {
    /** 사진 머리에 도는 순서 — 서울 · 부산 · 경주 · 제주 · 인천 · 수원 · 포항 · 대구. */
    val all: List<CityCover> =
        listOf(
            CityCover("서울", "서울", "Seoul", 186, "뚝섬한강공원", "Ttukseom Hangang Park", "마이데몬", "My Demon", "city_cover_seoul"),
            CityCover("부산", "부산", "Busan", 403, "감천문화마을", "Gamcheon Culture Village", "유미의 세포들", "Yumi's Cells", "city_cover_busan"),
            CityCover(
                "경주",
                "경주",
                "Gyeongju",
                390,
                "경주 오릉",
                "Gyeongju Oreung Royal Tombs",
                "21세기 대군부인",
                "Perfect Crown",
                "city_cover_gyeongju",
            ),
            CityCover(
                "제주",
                "제주",
                "Jeju",
                295,
                "군산오름",
                "Gunsan Oreum",
                "폭싹 속았수다",
                "When Life Gives You Tangerines",
                "city_cover_jeju",
            ),
            CityCover("인천", "인천", "Incheon", 199, "월미도", "Wolmido", "브로커", "Broker", "city_cover_incheon"),
            CityCover(
                "수원",
                "수원",
                "Suwon",
                410,
                "화성행궁",
                "Hwaseong Haenggung Palace",
                "왕의 남자",
                "King and the Clown",
                "city_cover_suwon",
            ),
            CityCover("포항", "포항", "Pohang", 195, "호미곶해맞이광장", "Homigot Sunrise Plaza", "스프링 피버", "Spring Fever", "city_cover_pohang"),
            CityCover(
                "대구",
                "대구",
                "Daegu",
                140,
                "서문시장",
                "Seomun Market",
                "김비서가 왜 그럴까",
                "What's Wrong with Secretary Kim",
                "city_cover_daegu",
            ),
        )

    /** 고른 대표 사진이 있는 도시면 그 사진. */
    fun fixed(key: String): CityCover? = all.firstOrNull { it.key == key }
}

/** 홈 맨 위 「어디로 여행 가세요?」 사진 카드의 규칙 (MZ2AZ-547 H1–H3). */
object HomeCityRules {
    /** 카드에 늘어놓는 도시 칩의 최대 수. */
    const val CHIP_LIMIT = 8

    /** 칩으로 올릴 도시 — **서버 순서 그대로**, 앞에서 [CHIP_LIMIT] 개. 촬영지 0곳인 도시는 뺀다. */
    fun chips(cities: List<PlaceCity>): List<PlaceCity> = cities.filter { it.placeCount > 0 }.take(CHIP_LIMIT)

    /** 도시 사진이 어디서 오는가. */
    sealed class Photo {
        /** 앱에 실린 고정 사진(`res/drawable-nodpi` 이름). */
        data class Asset(
            val name: String,
        ) : Photo()

        /** 그 도시 인기 1위 촬영지의 사진 주소. */
        data class Remote(
            val url: String,
        ) : Photo()
    }

    /** 도시의 사진 — 고정 사진이 먼저, 없으면 인기 1위 촬영지 사진(절대 http(s) 주소만), 그것도 없으면 null. */
    fun photo(
        cityKey: String,
        topPlaceImageUrl: String?,
    ): Photo? {
        CityCovers.fixed(cityKey)?.let { return Photo.Asset(it.asset) }
        val raw = topPlaceImageUrl ?: return null
        val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return Photo.Remote(raw)
    }

    /** 사진 위 작은 글 — 「도시 · 촬영지」. 앱 언어가 한국어가 아니면 영어 이름. */
    fun caption(
        cover: CityCover,
        lang: Lang,
    ): String = if (lang == Lang.ko) "${cover.cityKo} · ${cover.placeKo}" else "${cover.cityEn} · ${cover.placeEn}"
}

/** 사진 머리 슬라이드의 규칙 (MZ2AZ-547 H2) — 4초마다 겹쳐 바뀌고, 움직임 줄이기면 첫 장에 멈춘다. 시간은 밀리초. */
object CitySlideRules {
    /** 다음 장으로 넘어가는 간격. */
    const val INTERVAL_MILLIS = 4_000L

    /** 겹쳐 바뀌는 시간. */
    const val CROSSFADE_MILLIS = 1_200

    /** 현재 장이 천천히 커지는 시간과 배율. */
    const val ZOOM_MILLIS = 5_000
    const val ZOOM_SCALE = 1.06f

    /** 다음 장의 번호 — 끝에서 처음으로 돈다. 장이 없으면 0. */
    fun next(
        after: Int,
        count: Int,
    ): Int = if (count <= 0) 0 else (after + 1) % count

    /** 저절로 넘기는가 — 두 장 이상이고 움직임 줄이기가 꺼져 있을 때만. */
    fun advances(
        count: Int,
        reduceMotion: Boolean,
    ): Boolean = count >= 2 && !reduceMotion

    /** 이 장의 그림을 그려 두는가 — 지금 장과 앞·뒤 장만. */
    fun isMounted(
        index: Int,
        current: Int,
        count: Int,
    ): Boolean {
        if (count <= 0 || index !in 0 until count) return false
        val now = ((current % count) + count) % count
        return index == now || index == (now + 1) % count || index == (now - 1 + count) % count
    }
}
