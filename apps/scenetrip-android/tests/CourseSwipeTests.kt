package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.CourseSwipe
import com.mz2az.scenetrip.routetab.SwipeSettle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CourseSwipeTests {
    private val reveal = 80f
    private val full = 370f

    @Test
    fun shortDragReturnsTheRowWithoutRevealingTheButton() {
        assertEquals(SwipeSettle.CLOSE, CourseSwipe.settle(0f, reveal, full))
        assertEquals(SwipeSettle.CLOSE, CourseSwipe.settle(39f, reveal, full))
    }

    @Test
    fun dragPastHalfTheButtonAreaLeavesTheRowOpenSoTheButtonMustBePressed() {
        assertEquals(SwipeSettle.OPEN, CourseSwipe.settle(41f, reveal, full))
        assertEquals(SwipeSettle.OPEN, CourseSwipe.settle(184f, reveal, full))
    }

    @Test
    fun dragPastHalfTheRowActsImmediatelyLikeIosFullSwipe() {
        assertEquals(SwipeSettle.FULL, CourseSwipe.settle(186f, reveal, full))
        assertEquals(SwipeSettle.FULL, CourseSwipe.settle(370f, reveal, full))
    }

    @Test
    fun unmeasuredRowNeverTriggersFullSwipe() {
        assertEquals(SwipeSettle.OPEN, CourseSwipe.settle(100f, reveal, 0f))
    }

    @Test
    fun onlyOneRowIsOpenAtATimeSoSwipingAnotherRowClosesTheFirst() {
        var open: Long? = null
        open = CourseSwipe.claim(open, 1)
        assertEquals(1L, open)
        open = CourseSwipe.claim(open, 2)
        assertEquals(2L, open)
    }

    @Test
    fun releasingARowThatIsNoLongerTheOpenOneLeavesTheOpenRowAlone() {
        assertEquals(null, CourseSwipe.release(1, 1))
        assertEquals(2L, CourseSwipe.release(2, 1))
        assertEquals(null, CourseSwipe.release(null, 1))
    }

    @Test
    fun dividerIsHiddenUnderTheOpenRowAndAfterTheLastRow() {
        assertEquals(true, CourseSwipe.showsDivider(openId = null, id = 1, isLast = false))
        assertEquals(true, CourseSwipe.showsDivider(openId = 2, id = 1, isLast = false))
        assertEquals(false, CourseSwipe.showsDivider(openId = 1, id = 1, isLast = false))
        assertEquals(false, CourseSwipe.showsDivider(openId = null, id = 1, isLast = true))
    }
}
