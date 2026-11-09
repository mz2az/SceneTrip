import SceneApiClient
@testable import SceneTrip
import XCTest

/// 계획(코스 편집) 화면 규칙 (MZ2AZ-547 P4, 계획서 `docs/project/plans/ios-web-parity.md` E1–E16).
///
/// 웹 `route/editor/RouteEditor.tsx`·`proto/ProtoAddMenu.tsx`·`proto/protoCourseAdd.tsx`·
/// `route/map/RouteMap.tsx` 의 동작을 명세로 삼는다. 화면은 이 규칙의 답을 그대로 따른다.
final class RouteEditorRulesTests: XCTestCase {
    // MARK: 도움

    /// id 마다 이름·좌표가 다르다 — `RouteDedupe` 는 이름+좌표가 같으면 id 가 달라도 같은 곳으로 본다.
    private func place(_ id: Int64, _ name: String? = nil) -> PlaceSummary {
        PlaceSummary(
            id: id,
            name: name ?? "곳\(id)",
            latitude: 37.5 + Double(id) * 0.001,
            longitude: 127.0 + Double(id) * 0.001
        )
    }

    private func stop(_ id: Int64) -> RouteStop {
        RouteStop(place: place(id))
    }

    /// 1일차 [10, 11], 2일차 [20].
    private func course() -> RouteCourse {
        RouteCourse(
            serverId: 1,
            title: "서울 이틀",
            days: [RouteDay(stops: [stop(10), stop(11)]), RouteDay(stops: [stop(20)])]
        )
    }

    private func ids(_ day: RouteDay) -> [Int64] {
        day.stops.map(\.place.id)
    }

    // MARK: E1 처음 상태

    /// 새 초안은 처음부터 편집.
    func testNewDraftOpensEditing() {
        XCTAssertEqual(RouteEditorRules.initialMode(isNew: true, isFresh: false), .editing)
    }

    /// 방금 「도시로」 만든 코스도 처음부터 편집.
    func testFreshCourseOpensEditing() {
        XCTAssertEqual(RouteEditorRules.initialMode(isNew: false, isFresh: true), .editing)
    }

    /// 저장된 코스는 보기로 연다.
    func testSavedCourseOpensViewing() {
        XCTAssertEqual(RouteEditorRules.initialMode(isNew: false, isFresh: false), .viewing)
    }

    // MARK: E3·E16 「취소」

    /// 방금 만든 코스는 바꾼 것이 없어도 「저장하지 않고 나갈까요?」 를 묻는다(저장해야 남는다).
    func testCancelFreshUnchangedAsksLeaveAndDelete() {
        XCTAssertEqual(
            RouteEditorRules.cancel(isNew: false, isFresh: true, changed: false), .confirmLeaveAndDelete
        )
    }

    func testCancelFreshChangedAsksLeaveAndDelete() {
        XCTAssertEqual(
            RouteEditorRules.cancel(isNew: false, isFresh: true, changed: true), .confirmLeaveAndDelete
        )
    }

    /// 새 초안인데 바꾼 것이 있으면 「바꾼 내용을 버릴까요?」 → 닫기.
    func testCancelNewDraftChangedAsksDiscardThenClose() {
        XCTAssertEqual(
            RouteEditorRules.cancel(isNew: true, isFresh: false, changed: true), .confirmDiscardThenClose
        )
    }

    /// 새 초안인데 빈 틀이면 묻지 않고 닫는다.
    func testCancelNewDraftUnchangedCloses() {
        XCTAssertEqual(RouteEditorRules.cancel(isNew: true, isFresh: false, changed: false), .close)
    }

    /// 저장된 코스를 고치다 바꾼 것이 있으면 「바꾼 내용을 버릴까요?」 → 되돌려 보기로.
    func testCancelSavedChangedAsksDiscardThenRevert() {
        XCTAssertEqual(
            RouteEditorRules.cancel(isNew: false, isFresh: false, changed: true), .confirmDiscardThenRevert
        )
    }

    /// 저장된 코스에서 바꾼 것이 없으면 묻지 않고 보기로.
    func testCancelSavedUnchangedRevertsToView() {
        XCTAssertEqual(RouteEditorRules.cancel(isNew: false, isFresh: false, changed: false), .revertToView)
    }

    // MARK: E3 저장 뒤

    func testAfterSaveNewDraftCloses() {
        XCTAssertEqual(RouteEditorRules.afterSave(isNew: true), .close)
    }

    func testAfterSaveSavedCourseStaysInView() {
        XCTAssertEqual(RouteEditorRules.afterSave(isNew: false), .view)
    }

    // MARK: E2·E3·E14 보이는 것

    func testEditToolsShownOnlyWhileEditing() {
        XCTAssertTrue(RouteEditorRules.showsEditTools(.editing))
        XCTAssertFalse(RouteEditorRules.showsEditTools(.viewing))
    }

    /// 저장된 코스를 고치는 동안만 「코스 시작」 줄을 숨긴다.
    func testBottomBarHiddenWhileEditingSavedCourse() {
        XCTAssertFalse(RouteEditorRules.showsBottomBar(.editing, isNew: false))
    }

    func testBottomBarShownWhileViewing() {
        XCTAssertTrue(RouteEditorRules.showsBottomBar(.viewing, isNew: false))
    }

    /// 새 초안은 「코스 만들기」 가 있어 편집 중에도 보인다.
    func testBottomBarShownForNewDraftEditing() {
        XCTAssertTrue(RouteEditorRules.showsBottomBar(.editing, isNew: true))
    }

    /// 웹 `LocateButton` 의 top — 편집 중 62(칩 줄 아래), 보기 10.
    func testLocateTopByMode() {
        XCTAssertEqual(RouteEditorRules.locateTop(.editing), 62)
        XCTAssertEqual(RouteEditorRules.locateTop(.viewing), 10)
    }

    // MARK: E12·E13 지도 카메라

    func testCameraRefitsOnDaySwitchAddAndFirstOpen() {
        XCTAssertTrue(RouteCameraRules.refits(.daySwitch))
        XCTAssertTrue(RouteCameraRules.refits(.add))
        XCTAssertTrue(RouteCameraRules.refits(.mapFirstOpen))
    }

    func testCameraKeepsViewOnReorderRemoveOptimizeSheetAndReopen() {
        XCTAssertFalse(RouteCameraRules.refits(.reorder))
        XCTAssertFalse(RouteCameraRules.refits(.remove))
        XCTAssertFalse(RouteCameraRules.refits(.optimize), "E13: 최적화는 지도를 그대로 둔다")
        XCTAssertFalse(RouteCameraRules.refits(.sheetHeight))
        XCTAssertFalse(RouteCameraRules.refits(.mapReopen))
    }

    // MARK: E4·E5 지도 알약

    /// 기본은 지도 접힘(목록 전체), 아직 맞추지 않음.
    func testMapStartsClosedWhenNotRunning() {
        let toggle = RouteMapToggle(running: false)
        XCTAssertFalse(toggle.isOpen)
        XCTAssertFalse(toggle.fitted)
        XCTAssertEqual(toggle.pill, .map)
    }

    /// 여행 중이면 펼친 채로, 맞춘 상태로 연다.
    func testMapStartsOpenWhenRunning() {
        let toggle = RouteMapToggle(running: true)
        XCTAssertTrue(toggle.isOpen)
        XCTAssertTrue(toggle.fitted)
        XCTAssertEqual(toggle.pill, .listOnly)
    }

    /// 처음 펼칠 때만 맞춘다 — 접었다 다시 펼치면 false.
    func testOpenFitsOnlyFirstTime() {
        var toggle = RouteMapToggle(running: false)
        XCTAssertTrue(toggle.open())
        XCTAssertTrue(toggle.isOpen)
        XCTAssertTrue(toggle.fitted)
        toggle.close()
        XCTAssertFalse(toggle.isOpen)
        XCTAssertFalse(toggle.open())
        XCTAssertTrue(toggle.isOpen)
    }

    /// 여행 중으로 연 지도는 이미 맞췄으니 접었다 펼쳐도 false.
    func testOpenAfterRunningStartReturnsFalse() {
        var toggle = RouteMapToggle(running: true)
        toggle.close()
        XCTAssertFalse(toggle.open())
    }

    /// 알약은 펼침/접힘을 뒤집는다. 처음 펼칠 때만 true.
    func testToggleFlipsAndFitsOnlyFirstOpen() {
        var toggle = RouteMapToggle(running: false)
        XCTAssertTrue(toggle.toggle())
        XCTAssertTrue(toggle.isOpen)
        XCTAssertEqual(toggle.pill, .listOnly)
        XCTAssertFalse(toggle.toggle())
        XCTAssertFalse(toggle.isOpen)
        XCTAssertEqual(toggle.pill, .map)
        XCTAssertFalse(toggle.toggle())
        XCTAssertTrue(toggle.isOpen)
    }

    /// 줄 누르기·핀 찍기 — 접혀 있으면 펼친다(처음이면 true).
    func testRevealOpensClosedMap() {
        var toggle = RouteMapToggle(running: false)
        XCTAssertTrue(toggle.reveal())
        XCTAssertTrue(toggle.isOpen)
        XCTAssertTrue(toggle.fitted)
    }

    /// 펼쳐져 있으면 그대로(false), 상태도 그대로.
    func testRevealOnOpenMapIsNoOp() {
        var toggle = RouteMapToggle(running: false)
        _ = toggle.open()
        let before = toggle
        XCTAssertFalse(toggle.reveal())
        XCTAssertEqual(toggle, before)
    }

    /// 한 번 맞춘 뒤 접었다가 줄을 누르면 펼치되 다시 맞추지 않는다.
    func testRevealAfterCloseOpensWithoutRefit() {
        var toggle = RouteMapToggle(running: false)
        _ = toggle.toggle()
        _ = toggle.toggle()
        XCTAssertFalse(toggle.reveal())
        XCTAssertTrue(toggle.isOpen)
    }

    // MARK: E10 보관함에서 고르기

    func testPickerTogglesInOrderAndShiftsDownOnUnpick() {
        var picker = CourseAddPicker()
        picker.toggle(3)
        picker.toggle(1)
        picker.toggle(2)
        XCTAssertEqual(picker.picked, [3, 1, 2])
        XCTAssertEqual(picker.order(of: 3), 1)
        XCTAssertEqual(picker.order(of: 1), 2)
        XCTAssertEqual(picker.order(of: 2), 3)

        picker.toggle(3)
        XCTAssertEqual(picker.picked, [1, 2])
        XCTAssertNil(picker.order(of: 3))
        XCTAssertEqual(picker.order(of: 1), 1, "푼 뒤 번호가 당겨진다")
        XCTAssertEqual(picker.order(of: 2), 2)
    }

    func testPickerOrderNilWhenNotPicked() {
        XCTAssertNil(CourseAddPicker().order(of: 5))
    }

    func testAllPickedFalseWhenFreeEmpty() {
        XCTAssertFalse(CourseAddPicker().allPicked([]))
    }

    func testAllPicked() {
        var picker = CourseAddPicker()
        picker.toggle(2)
        XCTAssertFalse(picker.allPicked([1, 2]))
        picker.toggle(1)
        XCTAssertTrue(picker.allPicked([1, 2]))
    }

    /// 다 안 골라져 있으면 `free` 순서대로 전부 고른다.
    func testToggleAllSelectsAllInFreeOrder() {
        var picker = CourseAddPicker()
        picker.toggleAll([5, 6, 7])
        XCTAssertEqual(picker.picked, [5, 6, 7])
        XCTAssertTrue(picker.allPicked([5, 6, 7]))
    }

    /// 일부만 골랐을 때도 전부 고름(`free` 순서).
    func testToggleAllFromPartialSelectsAll() {
        var picker = CourseAddPicker()
        picker.toggle(7)
        picker.toggleAll([5, 6, 7])
        XCTAssertEqual(Set(picker.picked), [5, 6, 7])
        XCTAssertTrue(picker.allPicked([5, 6, 7]))
    }

    /// 다 골라져 있으면 전부 푼다.
    func testToggleAllClearsWhenAllPicked() {
        var picker = CourseAddPicker()
        picker.toggleAll([5, 6])
        picker.toggleAll([5, 6])
        XCTAssertEqual(picker.picked, [])
        XCTAssertEqual(picker.done, .pickFirst)
    }

    func testOrderedFollowsPickOrderAndSkipsUnknown() {
        var picker = CourseAddPicker()
        picker.toggle(3)
        picker.toggle(99)
        picker.toggle(1)
        let places = [place(1, "가"), place(2, "나"), place(3, "다")]
        XCTAssertEqual(picker.ordered(places).map(\.id), [3, 1])
    }

    func testDoneLabel() {
        var picker = CourseAddPicker()
        XCTAssertEqual(picker.done, .pickFirst)
        picker.toggle(1)
        picker.toggle(2)
        XCTAssertEqual(picker.done, .done(count: 2))
    }

    // MARK: E9·E10 넣기 공통

    func testSplitKeepsOriginalOrder() {
        let places = [place(1), place(2), place(3), place(4)]
        let (free, inCourse) = CourseAddRules.split(places, taken: [2, 4])
        XCTAssertEqual(free.map(\.id), [1, 3])
        XCTAssertEqual(inCourse.map(\.id), [2, 4])
    }

    func testButtonAdd() {
        XCTAssertEqual(CourseAddRules.button(for: 1, taken: [], addedTo: [:]), .add)
    }

    func testButtonAlready() {
        XCTAssertEqual(CourseAddRules.button(for: 1, taken: [1], addedTo: [:]), .already)
    }

    /// 방금 넣은 것이 「이미 있음」 보다 먼저.
    func testButtonAddedWinsOverTaken() {
        XCTAssertEqual(CourseAddRules.button(for: 1, taken: [1], addedTo: [1: 2]), .added(day: 2))
        XCTAssertEqual(CourseAddRules.button(for: 1, taken: [], addedTo: [1: 0]), .added(day: 0))
    }

    func testDayCaption() {
        XCTAssertEqual(CourseAddRules.dayCaption(0), .empty)
        XCTAssertEqual(CourseAddRules.dayCaption(3), .places(3))
    }

    /// 고른 일차의 끝에 고른 순서대로.
    func testAddingAppendsToDayEndInOrder() {
        let (result, added) = CourseAddRules.adding([place(31), place(30)], toDay: 1, in: course())
        XCTAssertEqual(added, 2)
        XCTAssertEqual(ids(result.days[1]), [20, 31, 30])
        XCTAssertEqual(ids(result.days[0]), [10, 11], "다른 일차는 그대로")
    }

    /// 코스의 어느 일차에든 이미 있는 곳과 묶음 안의 중복은 거른다.
    func testAddingSkipsPlacesAlreadyInCourseAndBatchDuplicates() {
        let batch = [place(10), place(40), place(20), place(40), place(41)]
        let (result, added) = CourseAddRules.adding(batch, toDay: 0, in: course())
        XCTAssertEqual(added, 2)
        XCTAssertEqual(ids(result.days[0]), [10, 11, 40, 41])
        XCTAssertEqual(ids(result.days[1]), [20])
    }

    func testAddingOutOfRangeDayLeavesCourse() {
        let original = course()
        let (high, addedHigh) = CourseAddRules.adding([place(50)], toDay: 2, in: original)
        XCTAssertEqual(addedHigh, 0)
        XCTAssertEqual(high, original)
        let (low, addedLow) = CourseAddRules.adding([place(50)], toDay: -1, in: original)
        XCTAssertEqual(addedLow, 0)
        XCTAssertEqual(low, original)
    }
}
