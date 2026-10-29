package com.mz2az.scenetrip

import com.mz2az.scenetrip.reviews.ReviewRules
import com.mz2az.scenetrip.routetab.PoiInfoRules
import com.mz2az.scenetrip.sceneapi.client.infrastructure.Serializer
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoLicense
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.sceneapi.client.model.PoiDetail
import com.mz2az.scenetrip.sceneapi.client.model.PoiInfo
import com.mz2az.scenetrip.sceneapi.client.model.PoiInfoText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/**
 * MZ2AZ-470 — 편의시설 상세의 관광공사 안내와 사진 출처. iOS `PoiInfoTests`·`PoiPhotoTests`를 옮겼다. 앱 언어는 전역이라
 * 규칙에 언어를 인자로 넘겨 확인한다.
 */
class PoiInfoTests {
    private val fields = listOf("overview", "useTime", "restDate", "parking", "firstMenu", "useFee")

    private fun detail(extra: String = ""): PoiDetail =
        Serializer.moshi.adapter(PoiDetail::class.java).fromJson(
            "{\"id\":7,\"name\":\"명소\",\"category\":\"관광지\",\"categoryGroup\":\"sight\"," +
                "\"latitude\":37.5,\"longitude\":127.0,\"images\":[]$extra}",
        )!!

    @Test
    fun absentNullAndEmptyInfoHideTheArea() {
        val nulls = (fields + "homepage").joinToString(",") { "\"$it\":null" }
        for (extra in listOf("", ",\"info\":null", ",\"info\":{}", ",\"info\":{$nulls}")) {
            val info = detail(extra).info
            assertTrue(PoiInfoRules.rows(info, Lang.ko).isEmpty(), extra)
            assertFalse(PoiInfoRules.isVisible(info, Lang.ko), extra)
        }
    }

    @Test
    fun eachOmittedOrNullFieldHidesOnlyThatRow() {
        for (field in fields) {
            for (asNull in listOf(false, true)) {
                val body =
                    fields.filter { asNull || it != field }.joinToString(",") {
                        if (it == field) "\"$it\":null" else "\"$it\":{\"text\":\"$it\",\"lang\":\"ko\"}"
                    }
                val rows = PoiInfoRules.rows(detail(",\"info\":{$body}").info, Lang.ko)
                assertEquals(fields.size - 1, rows.size, field)
                assertFalse(rows.any { it.text == field }, field)
            }
        }
    }

    @Test
    fun blankTextIsSkippedButFreeFormLineBreaksSurvive() {
        val text = "10:30~21:00\n라스트오더 20:30\n예약 문의"
        val info =
            PoiInfo(
                overview = PoiInfoText("  \n ", Lang.ko),
                useTime = PoiInfoText(text, Lang.ko),
            )
        val rows = PoiInfoRules.rows(info, Lang.ko)
        assertEquals(1, rows.size)
        assertEquals("이용 시간", rows[0].label)
        assertEquals(text, rows[0].text)
    }

    @Test
    fun languageTagsArePerFieldAndUseTheAppLanguage() {
        val info = PoiInfo(overview = PoiInfoText("Overview", Lang.en), parking = PoiInfoText("주차 가능", Lang.ko))
        val english = PoiInfoRules.rows(info, Lang.en)
        assertNull(english[0].languageTag)
        assertEquals("Korean", english[1].languageTag)
        val korean = PoiInfoRules.rows(info, Lang.ko)
        assertEquals("영어", korean[0].languageTag)
        assertNull(korean[1].languageTag)
        val other = PoiInfo(overview = PoiInfoText("a", Lang.ja), parking = PoiInfoText("b", Lang.zhMinusHant))
        assertEquals(listOf("일본어", "중국어 번체"), PoiInfoRules.rows(other, Lang.ko).map { it.languageTag })
        assertEquals(listOf("Japanese", "Traditional Chinese"), PoiInfoRules.rows(other, Lang.en).map { it.languageTag })
    }

    @Test
    fun labelsUseExistingKoreanAndEnglishStrings() {
        val value = PoiInfoText("안내", Lang.ko)
        val info = PoiInfo(value, value, value, value, value, value)
        assertEquals(listOf("개요", "이용 시간", "쉬는 날", "주차", "대표 메뉴", "입장료"), PoiInfoRules.rows(info, Lang.ko).map { it.label })
        assertEquals(
            listOf("Overview", "Hours", "Closed", "Parking", "Signature menu", "Admission"),
            PoiInfoRules.rows(info, Lang.en).map { it.label },
        )
    }

    @Test
    fun homepageOnlyAllowsWebAddressesAndCanStandAlone() {
        for (raw in listOf("https://example.com/info", "http://example.com")) {
            val info = PoiInfo(homepage = URI(raw))
            assertEquals(raw, PoiInfoRules.homepage(info))
            assertTrue(PoiInfoRules.isVisible(info, Lang.ko))
        }
        for (raw in listOf("", "javascript:alert(1)", "file:///tmp/page", "//example.com", "https://u:p@example.com")) {
            val info = PoiInfo(homepage = URI(raw))
            assertNull(PoiInfoRules.homepage(info), raw)
            assertFalse(PoiInfoRules.isVisible(info, Lang.ko), raw)
        }
        assertNull(PoiInfoRules.homepage(null))
    }

    @Test
    fun generatedDetailCarriesInfoAndPhotoFields() {
        val poi =
            detail(
                ",\"info\":{\"overview\":{\"text\":\"관광공사 안내\",\"lang\":\"ko\"},\"homepage\":\"https://example.com\"}" +
                    ",\"photos\":[{\"url\":\"https://example.com/full.jpg\",\"source\":\"official\"," +
                    "\"thumbnailUrl\":\"https://example.com/small.jpg\",\"license\":\"kogl_type3\",\"credit\":\"한국관광공사\"}]",
            )
        assertEquals("관광공사 안내", PoiInfoRules.rows(poi.info, Lang.ko).single().text)
        assertEquals("https://example.com", PoiInfoRules.homepage(poi.info))
        val photo = poi.photos!!.single()
        assertEquals(URI("https://example.com/small.jpg"), photo.thumbnailUrl)
        assertEquals(PhotoLicense.kogl_type3, photo.license)
        assertTrue(ReviewRules.preservesPhotoFrame(photo), "호스트나 credit 문자열에 기대지 않는다")
    }

    private fun official(
        credit: String? = null,
        license: PhotoLicense? = null,
        thumbnail: String? = null,
        url: String = "https://example.com/a.jpg",
    ) = Photo(URI(url), PhotoSource.official, credit = credit, thumbnailUrl = thumbnail?.let(::URI), license = license)

    @Test
    fun bothLicensesShowLocalizedSourceEvenWithoutCredit() {
        for (license in PhotoLicense.entries) {
            for (credit in listOf(null, "한국관광공사")) {
                val photo = official(credit, license)
                assertEquals(listOf("출처: 한국관광공사"), ReviewRules.credits(listOf(photo), Lang.ko))
                assertEquals(listOf("Source: Korea Tourism Organization"), ReviewRules.credits(listOf(photo), Lang.en))
            }
        }
    }

    @Test
    fun licensedPhotoKeepsAdditionalCreditAndDeduplicatesCaptions() {
        val photo = official("촬영: 홍길동 · 공공누리 제3유형", PhotoLicense.kogl_type3)
        assertEquals(listOf("출처: 한국관광공사\n촬영: 홍길동 · 공공누리 제3유형"), ReviewRules.credits(listOf(photo, photo), Lang.ko))
        // 라이선스가 없는 사진은 예전처럼 credit 그대로다.
        assertEquals("직접 촬영", ReviewRules.credit(official("직접 촬영"), Lang.ko))
        assertNull(ReviewRules.credit(official(), Lang.ko))
    }

    @Test
    fun smallPhotosUseThumbnailAndDetailKeepsOriginal() {
        val photo =
            official(license = PhotoLicense.kogl_type3, thumbnail = "https://example.com/small.jpg", url = "https://example.com/full.jpg")
        assertEquals("https://example.com/small.jpg", ReviewRules.imageUrl(photo, thumbnail = true))
        assertEquals("https://example.com/full.jpg", ReviewRules.imageUrl(photo, thumbnail = false))
    }

    @Test
    fun absentEmptyAndUnsafeThumbnailsFallBackToOriginal() {
        val extras = listOf("", ",\"thumbnailUrl\":null", ",\"thumbnailUrl\":\"\"", ",\"thumbnailUrl\":\"javascript:alert(1)\"")
        for (extra in extras) {
            val photo =
                Serializer.moshi.adapter(Photo::class.java).fromJson(
                    "{\"url\":\"https://example.com/full.jpg\",\"source\":\"official\"$extra}",
                )
            assertNotNull(photo)
            assertEquals("https://example.com/full.jpg", ReviewRules.imageUrl(photo!!, thumbnail = true), extra)
            assertEquals("https://example.com/full.jpg", ReviewRules.imageUrl(photo, thumbnail = false), extra)
        }
    }

    @Test
    fun typeThreePreservesTheFrameWithoutHostOrCreditHints() {
        for (credit in listOf(null, "", "한국관광공사")) {
            val photo = official(credit, PhotoLicense.kogl_type3)
            assertTrue(ReviewRules.preservesPhotoFrame(photo))
            assertTrue(ReviewRules.isProtected(photo))
        }
        assertFalse(ReviewRules.isProtected(official(license = PhotoLicense.kogl_type1)))
        assertFalse(ReviewRules.preservesPhotoFrame(official(license = PhotoLicense.kogl_type1)))
        // 방문자 사진은 라이선스가 있어도 공식 사진이 아니다.
        assertFalse(ReviewRules.preservesPhotoFrame(official(license = PhotoLicense.kogl_type3).copy(source = PhotoSource.review)))
    }
}
