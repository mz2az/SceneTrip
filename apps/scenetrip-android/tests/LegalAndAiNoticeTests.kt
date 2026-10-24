package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.AccountDeletionCopy
import com.mz2az.scenetrip.data.LegalLinks
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.routetab.GuideAiNotice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.URI

/** MZ2AZ-452 탈퇴 확인 문구 · MZ2AZ-455 약관 링크 · MZ2AZ-439 AI 답변 안내. iOS 문구와 한 글자까지 같아야 한다. */
class LegalAndAiNoticeTests {
    @Test
    fun legalLinksPointAtThePublishedPrivacyAndTermsPages() {
        for ((raw, path) in listOf(LegalLinks.PRIVACY to "/privacy", LegalLinks.TERMS to "/terms")) {
            val uri = URI(raw)
            assertEquals("https", uri.scheme)
            assertEquals("scenetrip.io", uri.host)
            assertEquals(path, uri.path)
            assertEquals(null, uri.query)
            assertEquals(null, uri.fragment)
        }
    }

    @Test
    fun legalCopyMatchesIosAndHasEnglish() {
        assertEquals("약관 및 정책", LegalLinks.SECTION_TITLE_KO)
        assertEquals("개인정보 처리방침", LegalLinks.PRIVACY_TITLE_KO)
        assertEquals("이용약관", LegalLinks.TERMS_TITLE_KO)
        assertEquals("계속하면 이용약관과 개인정보 처리방침에 동의하는 것으로 봅니다.", LegalLinks.SIGN_IN_NOTICE_KO)
        assertEquals("Terms & Policies", englishOf(LegalLinks.SECTION_TITLE_KO))
        assertEquals("Privacy Policy", englishOf(LegalLinks.PRIVACY_TITLE_KO))
        assertEquals("Terms of Service", englishOf(LegalLinks.TERMS_TITLE_KO))
        assertEquals("By continuing, you agree to the Terms of Service and Privacy Policy.", englishOf(LegalLinks.SIGN_IN_NOTICE_KO))
        for (key in LegalLinks.copyKeys) assertNotNull(englishOf(key), key)
        // 안내 문장은 두 링크 이름을 그대로 담는다 — 문장과 링크가 따로 번역돼도 같은 낱말을 쓴다.
        assertTrue(LegalLinks.SIGN_IN_NOTICE_KO.contains(LegalLinks.TERMS_TITLE_KO))
        assertTrue(LegalLinks.SIGN_IN_NOTICE_KO.contains(LegalLinks.PRIVACY_TITLE_KO))
        assertTrue(englishOf(LegalLinks.SIGN_IN_NOTICE_KO)!!.contains(englishOf(LegalLinks.TERMS_TITLE_KO)!!))
        assertTrue(englishOf(LegalLinks.SIGN_IN_NOTICE_KO)!!.contains(englishOf(LegalLinks.PRIVACY_TITLE_KO)!!))
    }

    @Test
    fun withdrawalConfirmationMentionsWrittenReviewsStoriesAndPhotos() {
        assertEquals(
            "장바구니·코스·찜과 이 기기의 발자취, 작성한 리뷰·여행후기와 사진이 모두 지워지고 되돌릴 수 없어요.",
            AccountDeletionCopy.CONFIRM_KO,
        )
        assertEquals(
            "Your cart, courses, favorites, this device's trail, and the reviews and travel stories you wrote with their photos " +
                "will all be deleted. This can't be undone.",
            englishOf(AccountDeletionCopy.CONFIRM_KO),
        )
        // 옛 문구(리뷰가 「탈퇴한 사용자」로 남는다)는 더 쓰지 않는다.
        assertFalse(AccountDeletionCopy.CONFIRM_KO.contains("남아요"))
        assertEquals("Deleted user", englishOf("탈퇴한 사용자"))
    }

    @Test
    fun aiNoticeShowsFullSentenceUntilAccessibilitySizesThenOnlyTheFirst() {
        assertEquals("AI 답변은 틀릴 수 있어요. 영업시간·요금·교통은 직접 확인해 주세요.", GuideAiNotice.FULL_KO)
        assertEquals("AI 답변은 틀릴 수 있어요.", GuideAiNotice.SHORT_KO)
        assertTrue(GuideAiNotice.FULL_KO.startsWith(GuideAiNotice.SHORT_KO))
        for (scale in listOf(0.85f, 1.0f, 1.15f, 1.3f, 1.5f)) {
            assertEquals(GuideAiNotice.FULL_KO, GuideAiNotice.visibleKorean(scale), "scale $scale")
        }
        for (scale in listOf(1.6f, 1.8f, 2.0f)) {
            assertEquals(GuideAiNotice.SHORT_KO, GuideAiNotice.visibleKorean(scale), "scale $scale")
        }
    }

    @Test
    fun aiNoticeTextScaleIsCappedLikeIosAccessibility1() {
        assertEquals(1.3f, GuideAiNotice.cappedFontScale(1.3f))
        assertEquals(GuideAiNotice.MAX_FONT_SCALE, GuideAiNotice.cappedFontScale(2.0f))
        assertTrue(GuideAiNotice.MAX_FONT_SCALE >= GuideAiNotice.ACCESSIBILITY_FONT_SCALE)
    }

    @Test
    fun aiNoticeHasEnglishForBothSentences() {
        assertEquals(
            "AI answers can be wrong. Please check hours, prices and transit yourself.",
            englishOf(GuideAiNotice.FULL_KO),
        )
        assertEquals("AI answers can be wrong.", englishOf(GuideAiNotice.SHORT_KO))
        for (key in GuideAiNotice.copyKeys) assertNotNull(englishOf(key), key)
    }

    @Test
    fun androidOnlyEnglishSurvivedTheIosTranslationRegeneration() {
        // 주변 지도의 묶음 핀 문구는 iOS 403 을 따라 걷어냈다(MZ2AZ-482) — 남은 Android 전용 문구가 살아 있는지 본다.
        assertEquals("Everything still works when this is off.", englishOf("꺼도 앱 기능은 그대로예요."))
        assertEquals("Could not save visit · Try again", englishOf("방문 기록을 저장하지 못했어요 · 다시 저장"))
        assertNotNull(englishOf("기기에 보관된 옛 글 찾기"))
    }
}
