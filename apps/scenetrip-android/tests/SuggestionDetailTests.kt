package com.mz2az.scenetrip

import com.mz2az.scenetrip.sceneapi.client.model.EntityType
import com.mz2az.scenetrip.sceneapi.client.model.Suggestion
import com.mz2az.scenetrip.searchtab.SuggestionDetail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** 자동완성 장소 행의 보조 문구 (MZ2AZ-450). iOS `SuggestionDetailTests` 와 같은 표다. */
class SuggestionDetailTests {
    private fun place(
        placeType: String?,
        contentTitle: String?,
        name: String = "몬드리안서울 이태원",
        subtitle: String? = "서울 용산구",
    ) = Suggestion(
        type = EntityType.place,
        id = 1,
        name = name,
        subtitle = subtitle,
        placeType = placeType,
        contentTitle = contentTitle,
    )

    @Test fun placeDetailJoinsAvailablePartsOrFallsBackToSubtitle() {
        val cases =
            listOf(
                Triple("hotel", "눈물의 여왕", "호텔 · 눈물의 여왕"),
                Triple("hotel", null, "호텔"),
                Triple(null, "눈물의 여왕", "눈물의 여왕"),
                Triple(null, null, "서울 용산구"),
                Triple("unknown_type", "눈물의 여왕", "눈물의 여왕"),
                Triple("unknown_type", null, "서울 용산구"),
                Triple("", "", "서울 용산구"),
                Triple(" \n", " \n", "서울 용산구"),
                Triple("hotel", "", "호텔"),
                Triple("", "눈물의 여왕", "눈물의 여왕"),
            )
        for ((placeType, contentTitle, expected) in cases) {
            assertEquals(
                expected,
                SuggestionDetail.text(place(placeType, contentTitle), korean = true),
                "placeType=$placeType contentTitle=$contentTitle",
            )
        }
    }

    @Test fun englishLabelKeepsServerLocalizedTitle() {
        val item =
            place(
                "hotel",
                "Queen of Tears",
                name = "Mondrian Seoul Itaewon",
                subtitle = "Yongsan-gu, Seoul",
            )
        assertEquals("Hotel · Queen of Tears", SuggestionDetail.text(item, korean = false))
    }

    @Test fun legacyKoreanTypeLabelIsFoldedToCode() {
        assertEquals("Hospital · 드라마", SuggestionDetail.text(place("병원", "드라마"), korean = false))
    }

    @Test fun nonPlaceRowsKeepSubtitle() {
        for (type in listOf(EntityType.content, EntityType.person)) {
            val item =
                Suggestion(
                    type = type,
                    id = 1,
                    name = "이름",
                    subtitle = "기존 보조 문구",
                    placeType = "hotel",
                    contentTitle = "눈물의 여왕",
                )
            assertEquals("기존 보조 문구", SuggestionDetail.text(item, korean = true))
        }
    }

    @Test fun missingSubtitleAndPartsReturnEmptyText() {
        val item = Suggestion(type = EntityType.place, id = 1, name = "장소")
        assertEquals("", SuggestionDetail.text(item, korean = true))
    }
}
