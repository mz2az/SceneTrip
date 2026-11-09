package com.mz2az.scenetrip.hometab

import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary

/**
 * 홈 오른쪽 아래 떠 있는 「여행 코스 짜기」 단추의 규칙 (MZ2AZ-547 H4, 웹 `proto/ProtoScreens.tsx` `TripFloatBar`, iOS `HomeTripFloatRules.swift`).
 *
 * Android 의 코스 목록([RouteStore.courses])은 서버에서 받은 것뿐이라 iOS 의 「서버에 저장된 것만」 거르기가 필요 없다.
 * 여행 중 = `status == active`.
 */
object HomeTripFloatRules {
    /** 줄에 올리는 내 코스의 최대 수. */
    const val MAX_COURSES = 5

    /** 펼친 줄의 알약이 하나씩 들어오는 간격(밀리초). */
    const val STAGGER_MILLIS = 45

    /** 줄에 올릴 코스 — 여행 중인 것 먼저(그 안에서는 받은 순서), 최대 [MAX_COURSES]. */
    fun courses(all: List<CourseSummary>): List<CourseSummary> =
        (all.filter { it.isRunning } + all.filter { !it.isRunning }).take(MAX_COURSES)

    private val CourseSummary.isRunning: Boolean get() = status == CourseStatus.active

    /** 접힌 단추의 모습. */
    sealed class Collapsed {
        /** 「여행 코스 짜기」. */
        data object Plan : Collapsed()

        /** 여행 중인 그 코스 — 누르면 바로 열린다. */
        data class Running(
            val course: CourseSummary,
        ) : Collapsed()
    }

    /** 여행 중인 첫 코스가 있으면 [Collapsed.Running], 없으면 [Collapsed.Plan]. */
    fun collapsed(courses: List<CourseSummary>): Collapsed =
        courses.firstOrNull { it.isRunning }?.let { Collapsed.Running(it) } ?: Collapsed.Plan

    /** 「여행 코스 짜기」 를 눌렀을 때. */
    enum class PlanTap {
        /** 새 코스 만들기로 바로 간다. */
        NEW_COURSE,

        /** 줄을 펼친다. */
        EXPAND,
    }

    /** 코스가 **정말 없을 때**(목록을 받았고 0개)만 바로 새 코스. 아직 모르거나 못 받았으면 줄을 펼친다. */
    fun planTap(
        courseList: RouteStore.CourseListState,
        courseCount: Int,
    ): PlanTap = if (courseList == RouteStore.CourseListState.LOADED && courseCount == 0) PlanTap.NEW_COURSE else PlanTap.EXPAND

    /** 펼친 줄의 알약 — **오른쪽 끝(단추 자리)부터** 이 순서로 놓인다. */
    sealed class Item {
        /** ✕ 접기. */
        data object Close : Item()

        /** ≡ 전체 코스. */
        data object All : Item()

        /** 「+ 새 코스」. */
        data object NewCourse : Item()

        /** 내 코스 하나. */
        data class Course(
            val course: CourseSummary,
        ) : Item()
    }

    /** ✕ · ≡ · 「+ 새 코스」 · 내 코스들(받은 순서). */
    fun items(courses: List<CourseSummary>): List<Item> = listOf(Item.Close, Item.All, Item.NewCourse) + courses.map { Item.Course(it) }

    /** i 번째 알약이 들어오기까지 기다리는 시간(밀리초). 접을 때는 모두 0. */
    fun delay(
        index: Int,
        open: Boolean,
    ): Int = if (open) maxOf(0, index) * STAGGER_MILLIS else 0
}

/** 확인용 뒷문 — `adb shell am start … --ez homeFloatOpen true` 이면 떠 있는 줄이 펼친 채로 뜬다(iOS `-homeFloatOpen 1`). */
object HomeDebug {
    @Volatile
    var floatOpen: Boolean = false
}
