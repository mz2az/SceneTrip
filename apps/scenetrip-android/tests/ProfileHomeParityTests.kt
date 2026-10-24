package com.mz2az.scenetrip

import com.mz2az.scenetrip.auth.AuthRules
import com.mz2az.scenetrip.auth.PendingWrites
import com.mz2az.scenetrip.data.AccountDataReset
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.hometab.HomeTabModel
import com.mz2az.scenetrip.reviews.ReviewRules
import com.mz2az.scenetrip.routetab.CourseListPhase
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientError
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 마이페이지·홈·커뮤니티·로그인의 남은 차이 (MZ2AZ-480). */
class ProfileHomeParityTests {
    private fun failure(
        status: Int,
        code: String?,
    ) = ClientException(
        "시험",
        status,
        ClientError<Unit>(body = code?.let { """{"code":"$it"}""" }, statusCode = status, headers = emptyMap()),
    )

    // 계정이 떠날 때 사본 비우기

    @Test
    fun accountDataResetRaisesTheGenerationAndTellsEveryWatcherUntilItStopsWatching() {
        val before = AccountDataReset.generation
        var heard = 0
        val stop = AccountDataReset.watch { heard += 1 }
        AccountDataReset.clear()
        assertEquals(before + 1, AccountDataReset.generation)
        assertEquals(1, heard)
        stop()
        AccountDataReset.clear()
        assertEquals(before + 2, AccountDataReset.generation)
        assertEquals(1, heard, "구독을 끊으면 더 듣지 않는다")
    }

    // 홈의 코스 카드

    @Test
    fun tripCardNeverOffersToCreateACourseBeforeItKnowsThereIsNone() {
        val card = HomeTabModel.Companion::tripCard
        val unknown = RouteStore.CourseListState.UNKNOWN
        val loaded = RouteStore.CourseListState.LOADED
        val failed = RouteStore.CourseListState.FAILED
        assertEquals(HomeTabModel.TripCard.FILLED, card(true, unknown, false, false))
        assertEquals(HomeTabModel.TripCard.LOADING, card(false, unknown, false, false), "코스가 있는지 모르는 동안은 받는 중이다")
        assertEquals(HomeTabModel.TripCard.UNAVAILABLE, card(false, failed, false, false))
        assertEquals(HomeTabModel.TripCard.LOADING, card(false, loaded, true, true), "목록은 왔고 상세를 받는 중이다")
        assertEquals(HomeTabModel.TripCard.CREATE, card(false, loaded, false, false), "받아 봤는데 정말 없을 때만 코스 만들기")
        assertEquals(HomeTabModel.TripCard.CREATE, card(false, loaded, true, false))
        assertNotNull(englishOf("내 여행을 불러오지 못했어요"))
        assertNotNull(englishOf("내 여행을 불러오는 중…"))
    }

    // 커뮤니티

    @Test
    fun communityFailureTextHasTheBodyLayoutSentenceAndTheOtherCodes() {
        assertEquals("본문의 문단과 사진 배치를 확인해 주세요", CommunityRules.failureText(failure(400, "POST_BODY_INVALID")))
        assertEquals("이 후기는 더 이상 볼 수 없어요", CommunityRules.failureText(failure(404, "POST_NOT_FOUND")))
        assertEquals("로그인한 뒤에 쓸 수 있어요", CommunityRules.failureText(failure(401, null)))
        assertEquals("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요", CommunityRules.failureText(failure(500, null)))
        assertNotNull(englishOf("본문의 문단과 사진 배치를 확인해 주세요"))
        assertNotNull(englishOf("기존 사진과 코스를 보관하고 있어요. 이 기기에서만 보입니다"))
        assertNotNull(englishOf("이 기기에만 저장된 옛 글"))
    }

    // 로그인·닉네임

    @Test
    fun signInFailureIsPickedByCodeAndConnectionTroubleIsItsOwnSentence() {
        assertEquals("구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요", AuthRules.signInFailureText(503, "AUTH_PROVIDER_UNAVAILABLE"))
        assertEquals("로그인하지 못했어요. 다시 해 주세요", AuthRules.signInFailureText(401, "GOOGLE_TOKEN_INVALID"))
        assertEquals("로그인하지 못했어요. 다시 해 주세요", AuthRules.signInFailureText(500, null))
        assertEquals("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요", AuthRules.signInFailureText(null, null))
        assertEquals("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요", AuthRules.signInFailureText(-1, null))
        for (text in listOf("구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요", "로그인하지 못했어요. 다시 해 주세요", "연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요")) {
            assertNotNull(englishOf(text), text)
        }
        assertEquals("Continue with Google", englishOf("Google 계정으로 계속하기"))
    }

    @Test
    fun signInStopsWhenWritesAreStillRunningAfterTheWait() =
        runBlocking {
            PendingWrites.begin()
            try {
                assertThrows(PendingWrites.StillRunning::class.java) { runBlocking { PendingWrites.settle() } }
            } finally {
                PendingWrites.end()
            }
            // 끝났으면 바로 지나간다.
            PendingWrites.settle()
            assertNotNull(englishOf("저장 중인 내용이 있어요. 잠시 뒤 다시 로그인해 주세요"))
        }

    @Test
    fun nicknameFailureTextIsPickedByCode() {
        assertEquals("쓸 수 없는 닉네임이에요. 2~16자, 한글·영문·숫자·밑줄(_)만 쓸 수 있어요", ReviewRules.nicknameFailureText("NICKNAME_INVALID"))
        assertEquals("이미 쓰는 닉네임입니다", ReviewRules.nicknameFailureText("NICKNAME_TAKEN"))
        assertFalse(ReviewRules.nicknameFailureText("TEXT_NOT_ALLOWED").isEmpty())
        assertEquals("저장하지 못했어요. 잠시 뒤 다시 해 주세요", ReviewRules.nicknameFailureText(null))
        assertEquals("저장하지 못했어요. 잠시 뒤 다시 해 주세요", ReviewRules.nicknameFailureText("SOMETHING"))
        assertTrue(englishOf("쓸 수 없는 닉네임이에요. 2~16자, 한글·영문·숫자·밑줄(_)만 쓸 수 있어요") != null)
    }

    @Test
    fun profileCopyHasEnglish() {
        for (text in listOf("이 기기에만 저장돼요. 서버로 보내지 않아요.", "닉네임 바꾸기", "마이페이지", "찜한 작품을 불러오지 못했어요. 잠시 뒤 다시 해 주세요")) {
            assertNotNull(englishOf(text), text)
        }
    }

    @Test
    fun courseTabDrawsEmptyOnlyWhenTheServerSaidEmpty() {
        val unknown = RouteStore.CourseListState.UNKNOWN
        val loaded = RouteStore.CourseListState.LOADED
        val failed = RouteStore.CourseListState.FAILED
        assertEquals(CourseListPhase.LOADING, CourseListPhase.of(false, unknown, false))
        assertEquals(CourseListPhase.LOADING, CourseListPhase.of(false, unknown, true))
        assertEquals(CourseListPhase.FAILED, CourseListPhase.of(false, failed, false))
        assertEquals(CourseListPhase.LOADING, CourseListPhase.of(false, failed, true))
        assertEquals(CourseListPhase.EMPTY, CourseListPhase.of(false, loaded, false))
        assertEquals(CourseListPhase.LIST, CourseListPhase.of(true, unknown, true))
        assertEquals(CourseListPhase.LIST, CourseListPhase.of(true, loaded, false))
    }
}
