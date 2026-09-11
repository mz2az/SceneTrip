import SceneApiClient
@testable import SceneTrip
import XCTest

/// 계약의 일정 초안(`GuidePlan`)과 화면 코스(`RouteCourse`) 사이 (MZ2AZ-321).
final class RouteGuidePlanTests: XCTestCase {
    private func stop(
        _ order: Int, _ name: String, placeId: Int64? = 100, arrive: Int = 540, dwell: Int = 45
    ) -> GuidePlanStop {
        GuidePlanStop(
            order: order, placeId: placeId, name: name, address: "서울 어딘가",
            latitude: 37.5 + Double(order) * 0.01, longitude: 127.0,
            arriveMinute: arrive, dwellMinutes: dwell
        )
    }

    private var samplePlan: GuidePlan {
        GuidePlan(
            titles: ["도깨비"], pace: .relaxed, travelBasis: .straightLine,
            notes: ["체류 시간은 추정이에요"],
            days: [
                GuidePlanDay(
                    day: 1, endMinute: 737, totalMeters: 11878,
                    stops: [stop(1, "서울중앙고"), stop(2, "개뿔", placeId: 101, arrive: 600)],
                    dropped: [GuidePlanDropped(placeId: 7, name: "잠수교", reason: "하루 정지점 상한 3 곳")]
                ),
                GuidePlanDay(day: 2, stops: [stop(1, "한미서점", placeId: 102, arrive: 555)]),
            ]
        )
    }

    /// 일차·순서·체류·도착 시각이 그대로 옮겨진다. AI 초안 표시가 켜진다.
    func testPlanBecomesCourse() {
        let course = RouteGuidePlan.course(from: samplePlan, title: "도깨비 1박 2일", startDate: nil, pace: .loose)
        XCTAssertEqual(course.days.count, 2)
        XCTAssertEqual(course.days[0].stops.map(\.place.name), ["서울중앙고", "개뿔"])
        XCTAssertEqual(course.days[0].stops[0].place.id, 100)
        XCTAssertEqual(course.days[0].stops[0].stayMinutes, 45)
        XCTAssertEqual(course.days[0].stops[1].arriveMinute, 600)
        XCTAssertTrue(course.madeByAI)
    }

    /// 「왜 빠졌는지」가 보인다. 요청한 작품의 촬영지가 말없이 사라지면 추천이 틀렸다고 느낀다.
    func testDroppedAndNotesBecomeDraftNotes() {
        let course = RouteGuidePlan.course(from: samplePlan, title: "t", startDate: nil, pace: .tight)
        XCTAssertTrue(course.draftNotes.contains { $0.contains("잠수교") && $0.contains("상한") })
        XCTAssertTrue(course.draftNotes.contains("체류 시간은 추정이에요"))
        XCTAssertTrue(course.draftNotes.contains { $0.contains("직선") })
    }

    /// `placeId == null` 은 **이름으로 대체하지 않는다.** 화면에는 남되 저장(`PUT`)에서 빠진다.
    func testMissingPlaceIdIsKeptOnScreenButNotSaved() {
        var plan = samplePlan
        plan.days[0].stops.append(stop(3, "이름만 아는 곳", placeId: nil, arrive: 660))
        let course = RouteGuidePlan.course(from: plan, title: "t", startDate: nil, pace: .tight)

        let shown = course.days[0].stops
        XCTAssertEqual(shown.count, 3)
        XCTAssertTrue(shown[2].placeMissing)
        XCTAssertLessThan(shown[2].place.id, -1_000_000_000) // 직접 찍은 핀의 음수 자리와도 갈린다

        let sent = RouteBridge.replace(from: course)
        XCTAssertEqual(sent.days[0].items.count, 2)
        XCTAssertEqual(sent.days[0].items.map(\.placeId), [100, 101])
    }

    /// 편집 사본을 되돌려 보내면 `placeId`·순서·체류가 보존된다. 되돌리기는 챗봇이 「2일차에서
    /// 빼 줘」를 알아듣는 전제다.
    func testRoundTripKeepsIdsOrderAndDwell() {
        let course = RouteGuidePlan.course(from: samplePlan, title: "t", startDate: nil, pace: .loose)
        let back = RouteGuidePlan.plan(from: course)
        XCTAssertEqual(back.pace, .relaxed)
        XCTAssertEqual(back.days.map(\.day), [1, 2])
        XCTAssertEqual(back.days[0].stops.map(\.placeId), [100, 101])
        XCTAssertEqual(back.days[0].stops.map(\.order), [1, 2])
        XCTAssertEqual(back.days[0].stops.map(\.dwellMinutes), [45, 45])
        XCTAssertEqual(back.days[0].stops[1].arriveMinute, 600)
    }

    /// 손으로 담은 곳(도착 시각 모름)과 직접 찍은 핀(촬영지 id 없음)도 계약 모양으로 나간다.
    func testHandPickedStopsStillSerialize() {
        let pinned = RouteStop(
            place: RouteMock.pinnedPlace(name: "우리 숙소", category: "숙소", lat: 37.5, lng: 127.0),
            isPinned: true
        )
        let course = RouteCourse(title: "t", days: [RouteDay(stops: [pinned])])
        let plan = RouteGuidePlan.plan(from: course)
        XCTAssertNil(plan.days[0].stops[0].placeId)
        XCTAssertEqual(plan.days[0].stops[0].arriveMinute, 0)
    }

    /// 에이전트 주의문의 마크다운은 벗기고, 직선 어림은 두 번 말하지 않는다.
    func testAgentNotesArePlainAndNotDuplicated() {
        var plan = samplePlan
        plan.notes = ["거리와 시간은 직선거리에 우회 계수를 곱한 **추정**이다."]
        let notes = RouteGuidePlan.notes(from: plan)
        XCTAssertTrue(notes.contains("거리와 시간은 직선거리에 우회 계수를 곱한 추정이다."))
        XCTAssertEqual(notes.filter { $0.contains("직선") }.count, 1)
        XCTAssertEqual(RouteGuidePlan.notesSummary(notes), "뺀 곳 1 · 주의 1")
    }

    func testClockLabelIsMadeByTheApp() {
        XCTAssertEqual(RouteGuidePlan.clock(540), "09:00")
        XCTAssertEqual(RouteGuidePlan.clock(737), "12:17")
        XCTAssertEqual(RouteGuidePlan.clock(0), "00:00")
        XCTAssertEqual(RouteGuidePlan.clock(1500), "01:00") // 자정을 넘긴 값도 시계로
    }
}
