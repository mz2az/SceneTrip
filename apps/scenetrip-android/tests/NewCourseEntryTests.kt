package com.mz2az.scenetrip

import com.mz2az.scenetrip.newcourse.NewCourseEntry
import com.mz2az.scenetrip.newcourse.NewCourseEntryRules
import com.mz2az.scenetrip.newcourse.NewCourseStart
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 새 코스 만들기의 입구와 방금 만든 코스 표시 (MZ2AZ-547 F2·Q2·Q5). iOS `NewCourseHookTests` 의 판단만 옮겼다.
 *
 * 탭 이동(`TabRouter`)은 Compose 상태라 JVM 에서 돌지 않으므로 순수 규칙 [NewCourseEntryRules] 만 본다.
 * 시트만 돌려주는 틀이나 늘 참인 틀로는 통과하지 못하게, 시험마다 서로 다른 답이 나와야 하는 경우를 같이 넣었다.
 */
class NewCourseEntryTests {
    /** 홈 도시 칩 — 시트 없이 그 도시의 도시 질문 흐름(「며칠?」부터)으로 바로 간다. */
    @Test
    fun cityChipOpensCityFlowDirectly() {
        assertEquals(NewCourseEntry.Flow(NewCourseStart.City("부산")), NewCourseEntryRules.entry("부산"))
        assertEquals(NewCourseEntry.Flow(NewCourseStart.City("서울")), NewCourseEntryRules.entry("서울"))
    }

    /** 「+ 새 코스」(도시 없음) — 세 갈래 시트. 도시 칩과는 다른 입구다. */
    @Test
    fun plusNewCourseOpensSheetUnlikeCityChip() {
        assertEquals(NewCourseEntry.Sheet, NewCourseEntryRules.entry(null))
        assertNotEquals(NewCourseEntryRules.entry(null), NewCourseEntryRules.entry("부산"), "도시 칩은 시트가 아니다")
    }

    /** 빈 도시 이름("", 공백)은 도시가 없는 것으로 보고 시트를 띄운다. 이름이 있으면 흐름으로 간다. */
    @Test
    fun blankCityCountsAsNoCity() {
        assertEquals(NewCourseEntry.Sheet, NewCourseEntryRules.entry(""))
        assertEquals(NewCourseEntry.Sheet, NewCourseEntryRules.entry("   "))
        assertEquals(NewCourseEntry.Flow(NewCourseStart.City("제주")), NewCourseEntryRules.entry("제주"))
    }

    /** 방금 만든 코스 — 표시된 id 와 같은 코스만 참, 다른 코스는 거짓. */
    @Test
    fun freshOnlyWhenSameId() {
        assertTrue(NewCourseEntryRules.isFresh(42, 42))
        assertFalse(NewCourseEntryRules.isFresh(42, 7), "다른 코스는 방금 만든 코스가 아니다")
    }

    /** 어느 한쪽이라도 없으면 거짓 — 둘 다 없어도(null == null) 방금 만든 코스가 아니다. */
    @Test
    fun notFreshWhenEitherMissing() {
        assertFalse(NewCourseEntryRules.isFresh(42, null), "코스 id 가 없으면 거짓")
        assertFalse(NewCourseEntryRules.isFresh(null, 42), "방금 만든 표시가 없으면 거짓")
        assertFalse(NewCourseEntryRules.isFresh(null, null), "둘 다 없어도 거짓")
    }
}
