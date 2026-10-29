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
}
