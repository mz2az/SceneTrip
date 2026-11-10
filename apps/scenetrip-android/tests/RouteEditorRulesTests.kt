package com.mz2az.scenetrip

import com.mz2az.scenetrip.routetab.CourseAddPicker
import com.mz2az.scenetrip.routetab.CourseAddRules
import com.mz2az.scenetrip.routetab.CourseOpeningRules
import com.mz2az.scenetrip.routetab.RouteCameraKey
import com.mz2az.scenetrip.routetab.RouteCameraRules
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteEditorMode
import com.mz2az.scenetrip.routetab.RouteEditorRules
import com.mz2az.scenetrip.routetab.RouteMapToggle
import com.mz2az.scenetrip.routetab.RouteNumberPinSpec
import com.mz2az.scenetrip.routetab.RouteOpeningState
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 계획(코스 편집) 화면 규칙 (MZ2AZ-547 P4, 계획서 `docs/project/plans/ios-web-parity.md` E1–E17).
 * iOS `RouteEditorRulesTests` 를 하나씩 옮기고, Android 에만 있는 [RouteNumberPinSpec](E11)·[CourseOpeningRules](E17) 를 더했다.
 */
class RouteEditorRulesTests {
    // ── 도움 ──

    /** id 마다 이름·좌표가 다르다 — `RouteDedupe` 는 이름+좌표가 같으면 id 가 달라도 같은 곳으로 본다. */
    private fun place(
        id: Long,
        name: String? = null,
    ) = PlaceSummary(
        id = id,
        name = name ?: "곳$id",
        latitude = 37.5 + id * 0.001,
        longitude = 127.0 + id * 0.001,
    )

    private fun stop(id: Long) = RouteStop(place = place(id))

    /** 1일차 [10, 11], 2일차 [20]. */
    private fun course() =
        RouteCourse(
            serverId = 1,
            title = "서울 이틀",
            days = listOf(RouteDay(stops = listOf(stop(10), stop(11))), RouteDay(stops = listOf(stop(20)))),
        )

    private fun ids(day: RouteDay): List<Long> = day.stops.map { it.place.id }

    // ── E1 처음 상태 ──

    /** 새 초안은 처음부터 편집. */
    @Test
    fun newDraftOpensEditing() {
        assertEquals(RouteEditorMode.EDITING, RouteEditorRules.initialMode(isNew = true, isFresh = false))
    }

    /** 방금 「도시로」 만든 코스도 처음부터 편집. */
    @Test
    fun freshCourseOpensEditing() {
        assertEquals(RouteEditorMode.EDITING, RouteEditorRules.initialMode(isNew = false, isFresh = true))
    }

    /** 저장된 코스는 보기로 연다. */
    @Test
    fun savedCourseOpensViewing() {
        assertEquals(RouteEditorMode.VIEWING, RouteEditorRules.initialMode(isNew = false, isFresh = false))
    }

    // ── E3·E16 「취소」 ──

    /** 방금 만든 코스는 바꾼 것이 없어도 「저장하지 않고 나갈까요?」 를 묻는다(저장해야 남는다). */
    @Test
    fun cancelFreshUnchangedAsksLeaveAndDelete() {
        assertEquals(
            RouteEditorRules.LeaveDecision.CONFIRM_LEAVE_AND_DELETE,
            RouteEditorRules.cancel(isNew = false, isFresh = true, changed = false),
        )
    }

    @Test
    fun cancelFreshChangedAsksLeaveAndDelete() {
        assertEquals(
            RouteEditorRules.LeaveDecision.CONFIRM_LEAVE_AND_DELETE,
            RouteEditorRules.cancel(isNew = false, isFresh = true, changed = true),
        )
    }

    /** 새 초안인데 바꾼 것이 있으면 「바꾼 내용을 버릴까요?」 → 닫기. */
    @Test
    fun cancelNewDraftChangedAsksDiscardThenClose() {
        assertEquals(
            RouteEditorRules.LeaveDecision.CONFIRM_DISCARD_THEN_CLOSE,
            RouteEditorRules.cancel(isNew = true, isFresh = false, changed = true),
        )
    }

    /** 새 초안인데 빈 틀이면 묻지 않고 닫는다. */
    @Test
    fun cancelNewDraftUnchangedCloses() {
        assertEquals(
            RouteEditorRules.LeaveDecision.CLOSE,
            RouteEditorRules.cancel(isNew = true, isFresh = false, changed = false),
        )
    }

    /** 저장된 코스를 고치다 바꾼 것이 있으면 「바꾼 내용을 버릴까요?」 → 되돌려 보기로. */
    @Test
    fun cancelSavedChangedAsksDiscardThenRevert() {
        assertEquals(
            RouteEditorRules.LeaveDecision.CONFIRM_DISCARD_THEN_REVERT,
            RouteEditorRules.cancel(isNew = false, isFresh = false, changed = true),
        )
    }

    /** 저장된 코스에서 바꾼 것이 없으면 묻지 않고 보기로. */
    @Test
    fun cancelSavedUnchangedRevertsToView() {
        assertEquals(
            RouteEditorRules.LeaveDecision.REVERT_TO_VIEW,
            RouteEditorRules.cancel(isNew = false, isFresh = false, changed = false),
        )
    }

    // ── E3 저장 뒤 ──

    @Test
    fun afterSaveNewDraftCloses() {
        assertEquals(RouteEditorRules.SaveOutcome.CLOSE, RouteEditorRules.afterSave(isNew = true))
    }

    @Test
    fun afterSaveSavedCourseStaysInView() {
        assertEquals(RouteEditorRules.SaveOutcome.VIEW, RouteEditorRules.afterSave(isNew = false))
    }

    // ── E2·E3·E14 보이는 것 ──

    @Test
    fun editToolsShownOnlyWhileEditing() {
        assertTrue(RouteEditorRules.showsEditTools(RouteEditorMode.EDITING))
        assertFalse(RouteEditorRules.showsEditTools(RouteEditorMode.VIEWING))
    }

    /** 저장된 코스를 고치는 동안만 「코스 시작」 줄을 숨긴다. */
    @Test
    fun bottomBarHiddenWhileEditingSavedCourse() {
        assertFalse(RouteEditorRules.showsBottomBar(RouteEditorMode.EDITING, isNew = false))
    }

    @Test
    fun bottomBarShownWhileViewing() {
        assertTrue(RouteEditorRules.showsBottomBar(RouteEditorMode.VIEWING, isNew = false))
    }

    /** 새 초안은 「코스 만들기」 가 있어 편집 중에도 보인다. */
    @Test
    fun bottomBarShownForNewDraftEditing() {
        assertTrue(RouteEditorRules.showsBottomBar(RouteEditorMode.EDITING, isNew = true))
    }

    /** 웹 `LocateButton` 의 top — 편집 중 62(칩 줄 아래), 보기 10. */
    @Test
    fun locateTopByMode() {
        assertEquals(62, RouteEditorRules.locateTop(RouteEditorMode.EDITING))
        assertEquals(10, RouteEditorRules.locateTop(RouteEditorMode.VIEWING))
    }

    // ── E12·E13 지도 카메라 ──

    @Test
    fun cameraRefitsOnDaySwitchAddAndFirstOpen() {
        assertTrue(RouteCameraRules.refits(RouteCameraRules.Change.DAY_SWITCH))
        assertTrue(RouteCameraRules.refits(RouteCameraRules.Change.ADD))
        assertTrue(RouteCameraRules.refits(RouteCameraRules.Change.MAP_FIRST_OPEN))
    }

    @Test
    fun cameraKeepsViewOnReorderRemoveOptimizeSheetAndReopen() {
        assertFalse(RouteCameraRules.refits(RouteCameraRules.Change.REORDER))
        assertFalse(RouteCameraRules.refits(RouteCameraRules.Change.REMOVE))
        assertFalse(RouteCameraRules.refits(RouteCameraRules.Change.OPTIMIZE), "E13: 최적화는 지도를 그대로 둔다")
        assertFalse(RouteCameraRules.refits(RouteCameraRules.Change.SHEET_HEIGHT))
        assertFalse(RouteCameraRules.refits(RouteCameraRules.Change.MAP_REOPEN))
    }

    /** 정보 카드 ✕ 로 닫기는 화면을 그대로 둔다 — 사람이 확대해 둔 것이 풀리지 않게. */
    @Test
    fun cameraKeepsViewOnCardClose() {
        assertFalse(RouteCameraRules.refits(RouteCameraRules.Change.CARD_CLOSE))
    }

    // ── 카메라 열쇠 — 고른 것을 놓기만 하면 옮기지 않는다 ──

    private fun key(
        focused: String? = null,
        picked: String? = null,
        context: String = "ctx",
    ) = RouteCameraKey(focused = focused, picked = picked, context = context)

    /** 다시 맞추라는 신호가 오면 늘 옮긴다. */
    @Test
    fun movesWhenFitChanged() {
        assertTrue(RouteCameraRules.moves(key(), key(), fitChanged = true))
        assertTrue(RouteCameraRules.moves(key(focused = "a"), key(), fitChanged = true))
    }

    /** 처음은 옮긴다. */
    @Test
    fun movesOnFirstKey() {
        assertTrue(RouteCameraRules.moves(null, key(), fitChanged = false))
    }

    /** 열쇠가 같으면 옮기지 않는다. */
    @Test
    fun staysWhenKeyUnchanged() {
        assertFalse(
            RouteCameraRules.moves(key(focused = "a", picked = "p"), key(focused = "a", picked = "p"), fitChanged = false),
        )
    }

    /** 코스 줄 고르기를 놓기만 했으면 그대로. */
    @Test
    fun staysWhenOnlyFocusedReleased() {
        assertFalse(RouteCameraRules.moves(key(focused = "a"), key(), fitChanged = false))
        assertFalse(RouteCameraRules.moves(key(focused = "a", picked = "p"), key(picked = "p"), fitChanged = false))
    }

    /** 편의시설·검색 카드 ✕ 로 놓기만 했으면 그대로. */
    @Test
    fun staysWhenOnlyPickedReleased() {
        assertFalse(RouteCameraRules.moves(key(picked = "p"), key(), fitChanged = false))
        assertFalse(RouteCameraRules.moves(key(focused = "a", picked = "p"), key(focused = "a"), fitChanged = false))
    }

    /** 둘을 함께 놓아도 그대로. */
    @Test
    fun staysWhenBothReleasedTogether() {
        assertFalse(RouteCameraRules.moves(key(focused = "a", picked = "p"), key(), fitChanged = false))
    }

    /** 새로 고르면 옮긴다. */
    @Test
    fun movesWhenNewSelection() {
        assertTrue(RouteCameraRules.moves(key(), key(focused = "a"), fitChanged = false))
        assertTrue(RouteCameraRules.moves(key(), key(picked = "p"), fitChanged = false))
    }

    /** 하나에서 다른 하나로 바꾸면 옮긴다. */
    @Test
    fun movesWhenSwitchingSelection() {
        assertTrue(RouteCameraRules.moves(key(focused = "a"), key(focused = "b"), fitChanged = false))
        assertTrue(RouteCameraRules.moves(key(picked = "p"), key(picked = "q"), fitChanged = false))
        assertTrue(RouteCameraRules.moves(key(focused = "a"), key(picked = "p"), fitChanged = false))
    }

    /** 놓으면서 하나를 새로 고르면(다른 쪽이 바뀌면) 옮긴다. */
    @Test
    fun movesWhenReleaseCombinedWithOtherChange() {
        assertTrue(RouteCameraRules.moves(key(focused = "a"), key(picked = "p"), fitChanged = false))
        assertTrue(RouteCameraRules.moves(key(focused = "a", picked = "p"), key(picked = "q"), fitChanged = false))
    }

    /** 그 밖의 것(내 위치·담을까 보는 곳 등)이 바뀌면 옮긴다. */
    @Test
    fun movesWhenContextChanges() {
        assertTrue(RouteCameraRules.moves(key(context = "x"), key(context = "y"), fitChanged = false))
    }

    /** 놓으면서 그 밖의 것도 바뀌었으면 옮긴다. */
    @Test
    fun movesWhenReleaseWithContextChange() {
        assertTrue(RouteCameraRules.moves(key(focused = "a", context = "x"), key(context = "y"), fitChanged = false))
        assertTrue(RouteCameraRules.moves(key(picked = "p", context = "x"), key(context = "y"), fitChanged = false))
    }

    // ── E4·E5 지도 알약 ──

    /** 기본은 지도 접힘(목록 전체), 아직 맞추지 않음. */
    @Test
    fun mapStartsClosedWhenNotRunning() {
        val toggle = RouteMapToggle.initial(running = false)
        assertFalse(toggle.isOpen)
        assertFalse(toggle.fitted)
        assertEquals(RouteMapToggle.Pill.MAP, toggle.pill)
    }

    /** 여행 중이면 펼친 채로, 맞춘 상태로 연다. */
    @Test
    fun mapStartsOpenWhenRunning() {
        val toggle = RouteMapToggle.initial(running = true)
        assertTrue(toggle.isOpen)
        assertTrue(toggle.fitted)
        assertEquals(RouteMapToggle.Pill.LIST_ONLY, toggle.pill)
    }

    /** 처음 펼칠 때만 맞춘다 — 접었다 다시 펼치면 false. */
    @Test
    fun openFitsOnlyFirstTime() {
        val first = RouteMapToggle.initial(running = false).open()
        assertTrue(first.fits)
        assertTrue(first.toggle.isOpen)
        assertTrue(first.toggle.fitted)
        val closed = first.toggle.close()
        assertFalse(closed.isOpen)
        val again = closed.open()
        assertFalse(again.fits)
        assertTrue(again.toggle.isOpen)
    }

    /** 여행 중으로 연 지도는 이미 맞췄으니 접었다 펼쳐도 false. */
    @Test
    fun openAfterRunningStartReturnsFalse() {
        val closed = RouteMapToggle.initial(running = true).close()
        assertFalse(closed.open().fits)
    }

    /** 알약은 펼침/접힘을 뒤집는다. 처음 펼칠 때만 true. */
    @Test
    fun toggleFlipsAndFitsOnlyFirstOpen() {
        val s1 = RouteMapToggle.initial(running = false).toggle()
        assertTrue(s1.fits)
        assertTrue(s1.toggle.isOpen)
        assertEquals(RouteMapToggle.Pill.LIST_ONLY, s1.toggle.pill)
        val s2 = s1.toggle.toggle()
        assertFalse(s2.fits)
        assertFalse(s2.toggle.isOpen)
        assertEquals(RouteMapToggle.Pill.MAP, s2.toggle.pill)
        val s3 = s2.toggle.toggle()
        assertFalse(s3.fits)
        assertTrue(s3.toggle.isOpen)
    }

    /** 줄 누르기·핀 찍기 — 접혀 있으면 펼친다(처음이면 true). */
    @Test
    fun revealOpensClosedMap() {
        val step = RouteMapToggle.initial(running = false).reveal()
        assertTrue(step.fits)
        assertTrue(step.toggle.isOpen)
        assertTrue(step.toggle.fitted)
    }

    /** 펼쳐져 있으면 그대로(false), 상태도 그대로. */
    @Test
    fun revealOnOpenMapIsNoOp() {
        val before = RouteMapToggle.initial(running = false).open().toggle
        val step = before.reveal()
        assertFalse(step.fits)
        assertEquals(before, step.toggle)
        // 스텁이 「펼침」 을 못 만들면 위 비교가 그냥 통과하므로, 펼친 상태였는지도 확인한다.
        assertTrue(before.isOpen)
    }

    /** 한 번 맞춘 뒤 접었다가 줄을 누르면 펼치되 다시 맞추지 않는다. */
    @Test
    fun revealAfterCloseOpensWithoutRefit() {
        val closed =
            RouteMapToggle
                .initial(running = false)
                .toggle()
                .toggle
                .toggle()
                .toggle
        val step = closed.reveal()
        assertFalse(step.fits)
        assertTrue(step.toggle.isOpen)
    }

    // ── E10 보관함에서 고르기 ──

    @Test
    fun pickerTogglesInOrderAndShiftsDownOnUnpick() {
        val picker = CourseAddPicker().toggle(3).toggle(1).toggle(2)
        assertEquals(listOf(3L, 1L, 2L), picker.picked)
        assertEquals(1, picker.order(3))
        assertEquals(2, picker.order(1))
        assertEquals(3, picker.order(2))

        val after = picker.toggle(3)
        assertEquals(listOf(1L, 2L), after.picked)
        assertNull(after.order(3))
        assertEquals(1, after.order(1), "푼 뒤 번호가 당겨진다")
        assertEquals(2, after.order(2))
    }

    @Test
    fun pickerOrderNullWhenNotPicked() {
        assertNull(CourseAddPicker().order(5))
    }

    @Test
    fun allPickedFalseWhenFreeEmpty() {
        assertFalse(CourseAddPicker().allPicked(emptyList()))
    }

    @Test
    fun allPicked() {
        val one = CourseAddPicker().toggle(2)
        assertFalse(one.allPicked(listOf(1, 2)))
        assertTrue(one.toggle(1).allPicked(listOf(1, 2)))
    }

    /** 다 안 골라져 있으면 `free` 순서대로 전부 고른다. */
    @Test
    fun toggleAllSelectsAllInFreeOrder() {
        val picker = CourseAddPicker().toggleAll(listOf(5, 6, 7))
        assertEquals(listOf(5L, 6L, 7L), picker.picked)
        assertTrue(picker.allPicked(listOf(5, 6, 7)))
    }

    /** 일부만 골랐을 때도 전부 고름(`free` 순서). */
    @Test
    fun toggleAllFromPartialSelectsAll() {
        val picker = CourseAddPicker().toggle(7).toggleAll(listOf(5, 6, 7))
        assertEquals(setOf(5L, 6L, 7L), picker.picked.toSet())
        assertTrue(picker.allPicked(listOf(5, 6, 7)))
    }

    /** 다 골라져 있으면 전부 푼다. */
    @Test
    fun toggleAllClearsWhenAllPicked() {
        val all = CourseAddPicker().toggleAll(listOf(5, 6))
        // 스텁이 처음부터 고르지 못하면 「푼다」 를 시험할 수 없으므로, 먼저 다 골라졌는지 확인한다.
        assertEquals(listOf(5L, 6L), all.picked)
        val cleared = all.toggleAll(listOf(5, 6))
        assertEquals(emptyList<Long>(), cleared.picked)
        assertEquals(CourseAddPicker.Done.PickFirst, cleared.done)
    }

    @Test
    fun orderedFollowsPickOrderAndSkipsUnknown() {
        val picker = CourseAddPicker().toggle(3).toggle(99).toggle(1)
        val places = listOf(place(1, "가"), place(2, "나"), place(3, "다"))
        assertEquals(listOf(3L, 1L), picker.ordered(places).map { it.id })
    }

    @Test
    fun doneLabel() {
        val picker = CourseAddPicker()
        assertEquals(CourseAddPicker.Done.PickFirst, picker.done)
        assertEquals(CourseAddPicker.Done.Picked(count = 2), picker.toggle(1).toggle(2).done)
    }

    // ── E9·E10 넣기 공통 ──

    @Test
    fun splitKeepsOriginalOrder() {
        val places = listOf(place(1), place(2), place(3), place(4))
        val split = CourseAddRules.split(places, taken = setOf(2, 4))
        assertEquals(listOf(1L, 3L), split.free.map { it.id })
        assertEquals(listOf(2L, 4L), split.inCourse.map { it.id })
    }

    @Test
    fun buttonAdd() {
        // 스텁이 Add 를 돌려주어 이것만으로는 통과할 수 있다 — 명세상 맞는 답이다.
        assertEquals(CourseAddRules.Button.Add, CourseAddRules.button(1, taken = emptySet(), addedTo = emptyMap()))
    }

    @Test
    fun buttonAlready() {
        assertEquals(CourseAddRules.Button.Already, CourseAddRules.button(1, taken = setOf(1), addedTo = emptyMap()))
    }

    /** 방금 넣은 것이 「이미 있음」 보다 먼저. */
    @Test
    fun buttonAddedWinsOverTaken() {
        assertEquals(CourseAddRules.Button.Added(day = 2), CourseAddRules.button(1, taken = setOf(1), addedTo = mapOf(1L to 2)))
        assertEquals(CourseAddRules.Button.Added(day = 0), CourseAddRules.button(1, taken = emptySet(), addedTo = mapOf(1L to 0)))
    }

    @Test
    fun dayCaption() {
        assertEquals(CourseAddRules.DayCaption.Empty, CourseAddRules.dayCaption(0))
        assertEquals(CourseAddRules.DayCaption.Places(3), CourseAddRules.dayCaption(3))
    }

    /** 고른 일차의 끝에 고른 순서대로. */
    @Test
    fun addingAppendsToDayEndInOrder() {
        val result = CourseAddRules.adding(listOf(place(31), place(30)), day = 1, course = course())
        assertEquals(2, result.added)
        assertEquals(listOf(20L, 31L, 30L), ids(result.course.days[1]))
        assertEquals(listOf(10L, 11L), ids(result.course.days[0]), "다른 일차는 그대로")
    }

    /** 코스의 어느 일차에든 이미 있는 곳과 묶음 안의 중복은 거른다. */
    @Test
    fun addingSkipsPlacesAlreadyInCourseAndBatchDuplicates() {
        val batch = listOf(place(10), place(40), place(20), place(40), place(41))
        val result = CourseAddRules.adding(batch, day = 0, course = course())
        assertEquals(2, result.added)
        assertEquals(listOf(10L, 11L, 40L, 41L), ids(result.course.days[0]))
        assertEquals(listOf(20L), ids(result.course.days[1]))
    }

    /** 일차가 범위 밖이면 그대로 두고 0 — 스텁도 그대로 돌려주므로 통과할 수 있다(명세상 맞는 답). */
    @Test
    fun addingOutOfRangeDayLeavesCourse() {
        val original = course()
        val high = CourseAddRules.adding(listOf(place(50)), day = 2, course = original)
        assertEquals(0, high.added)
        assertEquals(original, high.course)
        val low = CourseAddRules.adding(listOf(place(50)), day = -1, course = original)
        assertEquals(0, low.added)
        assertEquals(original, low.course)
    }

    // ── E11 코스 번호 핀 (Android 에만) ──

    /** 그림은 30×38, 지도 좌표는 꼬리 끝 (15, 37) 에 닿는다. */
    @Test
    fun numberPinAnchorsAtTailTip() {
        assertEquals(30f, RouteNumberPinSpec.WIDTH)
        assertEquals(38f, RouteNumberPinSpec.HEIGHT)
        assertEquals(15f / 30f, RouteNumberPinSpec.anchorX, 1e-6f)
        assertEquals(37f / 38f, RouteNumberPinSpec.anchorY, 1e-6f)
    }

    /** 번호 글자 크기 — 한 자리 13, 두 자리 11.5, 세 자리 이상 9. */
    @Test
    fun numberPinFontSizeByDigits() {
        assertEquals(13f, RouteNumberPinSpec.fontSize(1))
        assertEquals(13f, RouteNumberPinSpec.fontSize(9))
        assertEquals(11.5f, RouteNumberPinSpec.fontSize(10))
        assertEquals(11.5f, RouteNumberPinSpec.fontSize(99))
        assertEquals(9f, RouteNumberPinSpec.fontSize(100))
        assertEquals(9f, RouteNumberPinSpec.fontSize(123))
    }

    /** 고른 핀은 1.2배 — 스텁에 상수로 이미 있어 통과한다(값 고정용). */
    @Test
    fun numberPinSelectedScale() {
        assertEquals(1.2f, RouteNumberPinSpec.SELECTED_SCALE)
    }

    // ── E17 코스 여는 중 덮개·실패 띠 (Android 에만) ──

    @Test
    fun overlayShownExactlyWhileLoading() {
        assertTrue(CourseOpeningRules.showsOverlay(RouteOpeningState(isLoading = true)))
        assertTrue(CourseOpeningRules.showsOverlay(RouteOpeningState(failedCourse = course(), isLoading = true)))
        assertFalse(CourseOpeningRules.showsOverlay(RouteOpeningState()))
        assertFalse(CourseOpeningRules.showsOverlay(RouteOpeningState(failedCourse = course())))
    }

    /** 실패 띠는 실패했고 다시 여는 중이 아닐 때만. */
    @Test
    fun failureBannerOnlyWhenFailedAndNotLoading() {
        assertTrue(CourseOpeningRules.showsFailure(RouteOpeningState(failedCourse = course())))
        assertFalse(CourseOpeningRules.showsFailure(RouteOpeningState(failedCourse = course(), isLoading = true)))
        assertFalse(CourseOpeningRules.showsFailure(RouteOpeningState()))
        assertFalse(CourseOpeningRules.showsFailure(RouteOpeningState(isLoading = true)))
    }
}
