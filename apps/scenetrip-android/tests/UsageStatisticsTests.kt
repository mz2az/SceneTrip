package com.mz2az.scenetrip

import com.mz2az.scenetrip.analytics.AnalyticsSink
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.analytics.UsageStatistics
import com.mz2az.scenetrip.analytics.UsageStatisticsStorage
import com.mz2az.scenetrip.data.englishOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** MZ2AZ-475 이용 통계 수집 설정. iOS `UsageStatisticsTests`와 같은 규칙을 Firebase 없이 가짜 저장소·가짜 sink로 검증한다. */
class UsageStatisticsTests {
    private class MemoryStorage(
        var stored: Boolean? = null,
    ) : UsageStatisticsStorage {
        override fun getEnabled(): Boolean? = stored

        override fun setEnabled(enabled: Boolean) {
            stored = enabled
        }
    }

    private class RecordingSink : AnalyticsSink {
        val events = mutableListOf<AppEvent>()
        val properties = mutableListOf<Pair<String, String?>>()
        val collection = mutableListOf<Boolean>()

        override fun log(event: AppEvent) {
            events += event
        }

        override fun setUserProperty(
            name: String,
            value: String?,
        ) {
            properties += name to value
        }

        override fun setCollectionEnabled(enabled: Boolean) {
            collection += enabled
        }
    }

    private fun <T> withSink(
        sink: AnalyticsSink,
        block: () -> T,
    ): T {
        val saved = AppAnalytics.sink
        AppAnalytics.sink = sink
        try {
            return block()
        } finally {
            AppAnalytics.setCollectionEnabled(true)
            AppAnalytics.sink = saved
        }
    }

    @Test
    fun keyMatchesIosAndDefaultsOn() {
        assertEquals("scenetrip.analytics.collectionEnabled", UsageStatistics.KEY)
        assertTrue(UsageStatistics(MemoryStorage()) {}.isEnabled)
    }

    @Test
    fun storesBeforeTouchingSdkAndRestoresAcrossInstances() {
        val storage = MemoryStorage()
        val seenByApply = mutableListOf<Boolean?>()
        val settings = UsageStatistics(storage) { seenByApply += storage.stored }
        settings.setEnabled(false)
        val restored = UsageStatistics(storage) {}
        assertFalse(restored.isEnabled)
        assertEquals(listOf<Boolean?>(false), seenByApply, "SDK를 부르는 시점에 OFF가 이미 저장돼 있어야 한다")
        settings.setEnabled(true)
        assertTrue(restored.isEnabled)
        assertEquals(listOf<Boolean?>(false, true), seenByApply)
    }

    @Test
    fun switchPassesTheChoiceToTheSdkCollectionSwitch() {
        val sink = RecordingSink()
        withSink(sink) {
            val settings = UsageStatistics(MemoryStorage()) { AppAnalytics.setCollectionEnabled(it) }
            settings.setEnabled(false)
            settings.setEnabled(true)
        }
        assertEquals(listOf(false, true, true), sink.collection, "마지막 true는 withSink 정리")
    }

    @Test
    fun disabledStatisticsDropsEventsAndPropertiesAtEntry() {
        val sink = RecordingSink()
        withSink(sink) {
            AppAnalytics.setCollectionEnabled(false)
            AppAnalytics.log(AppEvent.ScreenView("home"))
            AppAnalytics.setLanguage("en")
            AppAnalytics.setMember(true)
            assertTrue(sink.events.isEmpty())
            assertTrue(sink.properties.isEmpty())

            AppAnalytics.setCollectionEnabled(true)
            AppAnalytics.log(AppEvent.ScreenView("profile"))
            assertEquals(listOf<AppEvent>(AppEvent.ScreenView("profile")), sink.events)
        }
    }

    @Test
    fun turningOnAgainReplaysThePropertiesChangedWhileOff() {
        val sink = RecordingSink()
        withSink(sink) {
            AppAnalytics.setCollectionEnabled(false)
            AppAnalytics.setLanguage("en")
            AppAnalytics.setMember(true)
            AppAnalytics.setCollectionEnabled(true)
            assertEquals(listOf("app_language" to "en", "account" to "member"), sink.properties)
            AppAnalytics.setLanguage("ko")
            assertEquals("app_language" to "ko", sink.properties.last())
        }
    }

    @Test
    fun copyMatchesIosAndHasEnglish() {
        assertEquals("이용 통계 수집", UsageStatistics.TITLE_KO)
        assertEquals("Usage statistics", englishOf(UsageStatistics.TITLE_KO))
        assertNotNull(englishOf(UsageStatistics.FOOTNOTE_KO))
        assertEquals("Everything still works when this is off.", englishOf(UsageStatistics.FOOTNOTE_KO))
    }
}
