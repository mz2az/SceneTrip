import SceneApiClient
@testable import SceneTrip
import XCTest

final class UnavailableItemTests: XCTestCase {
    func testGeneratedClientDecodesOptionalStateWithoutInventingTrue() throws {
        for suffix in ["", ",\"delisted\":false", ",\"delisted\":true"] {
            let json = "{\"id\":2,\"category\":\"drama\",\"title\":\"작품\",\"placeCount\":1\(suffix)}"
            let work = try JSONDecoder().decode(ContentSummary.self, from: Data(json.utf8))
            XCTAssertEqual(UnavailableItem.notice(for: work), suffix.contains("true") ? .content : nil)
        }
    }

    func testSavedRowsDefaultToAvailableAndRecoverWhenFalse() {
        var cart = CartItem(placeId: 1, name: "장소", addedAt: Date())
        var work = ContentSummary(id: 2, category: .drama, title: "작품", placeCount: 1)
        XCTAssertNil(UnavailableItem.notice(for: cart))
        XCTAssertNil(UnavailableItem.notice(for: work))
        cart.delisted = true
        work.delisted = true
        XCTAssertEqual(UnavailableItem.notice(for: cart), .place)
        XCTAssertEqual(UnavailableItem.notice(for: work), .content)
        cart.delisted = false
        work.delisted = false
        XCTAssertNil(UnavailableItem.notice(for: cart))
        XCTAssertNil(UnavailableItem.notice(for: work))
    }

    func testCourseNoticeUsesOnlyTheFlagForItsSource() {
        var item = courseItem(source: .place)
        XCTAssertNil(UnavailableItem.notice(for: item))
        item.delisted = true
        item.closed = true
        XCTAssertEqual(UnavailableItem.notice(for: item), .place)
        item.source = .poi
        XCTAssertEqual(UnavailableItem.notice(for: item), .closedPoi)
        item.closed = false
        XCTAssertNil(UnavailableItem.notice(for: item))
        item.source = .custompin
        item.closed = true
        XCTAssertNil(UnavailableItem.notice(for: item))
    }

    func testDetailAllowsRemovalButBlocksNewAdds() {
        for flag: Bool? in [nil, false, true] {
            XCTAssertEqual(UnavailableItem.canToggle(delisted: flag, saved: false), flag != true)
            XCTAssertTrue(UnavailableItem.canToggle(delisted: flag, saved: true))
        }
        let summary = ContentSummary(id: 2, category: .drama, title: "작품", placeCount: 1, delisted: true)
        var detail = ContentDetail(id: 2, category: .drama, title: "작품", placeCount: 1, delisted: false)
        XCTAssertTrue(UnavailableItem.contentDelisted(summary: summary, detail: nil))
        XCTAssertFalse(UnavailableItem.contentDelisted(summary: summary, detail: detail), "다시 소개하면 최신 상세가 우선")
        detail.delisted = true
        XCTAssertTrue(UnavailableItem.contentDelisted(summary: summary, detail: detail))
        detail.delisted = nil
        XCTAssertFalse(UnavailableItem.contentDelisted(summary: summary, detail: detail))
    }

    func test409CodesAndUnknownConflictsAreDistinct() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .ko
        let messages = [
            "PLACE_DELISTED": "목록에서 빠진 장소라 담을 수 없어요.",
            "CONTENT_DELISTED": "목록에서 빠진 작품이라 찜할 수 없어요.",
            "POI_CLOSED": "폐업한 곳이라 코스에 넣을 수 없어요.",
        ]
        for (code, message) in messages {
            let error = ErrorResponse.error(
                409, Data("{\"code\":\"\(code)\",\"message\":\"server\"}".utf8), nil,
                URLError(.badServerResponse)
            )
            let failure = ApiFailure(error)
            XCTAssertEqual(failure.message, message)
            XCTAssertTrue(failure.courseSaveMessage.contains("편집 내용은 그대로예요"))
            XCTAssertFalse(failure.isRetryable)
        }
        for code in ["DUPLICATE_CART_ITEM", "UNKNOWN", "IDEMPOTENCY_IN_PROGRESS"] {
            XCTAssertNil(UnavailableItem.rejection(status: 409, code: code))
        }
        XCTAssertNil(UnavailableItem.rejection(status: 400, code: "PLACE_DELISTED"))
        XCTAssertNil(UnavailableItem.rejection(status: 409, code: nil))
    }

    func testExistingCourseRowsKeepIdsAndNewRowsRemainNew() {
        var place = courseItem(source: .place)
        place.delisted = true
        var poi = courseItem(source: .poi)
        poi.id = 22
        poi.closed = true
        let stops = [RouteBridge.stop(from: place), RouteBridge.stop(from: poi)]
        XCTAssertEqual(stops.map(\.unavailable), [.place, .closedPoi])
        let course = RouteCourse(serverId: 8, title: "편집", days: [RouteDay(stops: stops)])
        let sent = RouteBridge.replace(from: course).days[0].items
        XCTAssertEqual(sent.map(\.id), [11, 22], "숨김·폐업 줄도 저장에서 빼지 않는다")
        var readded = stops[0]
        readded.serverItemId = nil
        XCTAssertNil(RouteBridge.item(from: readded).id, "다시 추가한 줄에 옛 ID를 지어내지 않는다")
    }

    func testNextStopAndDistancesSkipUnavailableRowsWithoutRenumbering() {
        let first = stop(1, longitude: 127)
        let hidden = stop(2, longitude: 129, unavailable: .place)
        let closed = stop(3, longitude: 130, unavailable: .closedPoi)
        let last = stop(4, longitude: 127.1)
        let rows = [first, hidden, closed, last]
        XCTAssertEqual(RouteAvailability.nextUnvisited(in: rows)?.number, 1)
        var visited = first
        visited.visited = true
        XCTAssertEqual(RouteAvailability.nextUnvisited(in: [visited, hidden, closed, last])?.number, 4)
        let direct = RouteGeometry.kilometers(first.place, last.place)
        XCTAssertEqual(RouteGeometry.totalKilometers(rows), direct, accuracy: 0.00001)
        XCTAssertEqual(RouteAvailability.nextKilometers(after: 0, in: rows) ?? -1, direct, accuracy: 0.00001)
        for index in 1 ... 3 {
            XCTAssertNil(RouteAvailability.nextKilometers(after: index, in: rows))
        }
        XCTAssertNil(RouteAvailability.nextUnvisited(in: [hidden, closed]))
        XCTAssertEqual(RouteGeometry.totalKilometers([hidden, closed]), 0)
        XCTAssertEqual(rows.map(\.place.id), [1, 2, 3, 4])
    }

    func testUnavailableRowsInvalidateServerTravelEstimate() {
        let rows = [stop(1, longitude: 127), stop(2, longitude: 128, unavailable: .place)]
        let estimate = RouteDayEstimate(stops: rows, totalMinutes: 160)
        XCTAssertNil(estimate.total(for: rows))
        XCTAssertEqual(RouteAvailability.stayTotal(rows), RouteStop.defaultStayMinutes)
    }

    func testGuideMoveAndRevisePreserveUnavailableStateAndSkipRules() throws {
        var hidden = courseItem(source: .place)
        hidden.delisted = true
        var closed = courseItem(source: .poi)
        closed.id = 22
        closed.closed = true
        var visited = stop(4, longitude: 127.2)
        visited.visited = true
        let original = RouteCourse(serverId: 8, title: "편집", days: [RouteDay(stops: [
            stop(3, longitude: 127.1), RouteBridge.stop(from: hidden), RouteBridge.stop(from: closed), visited,
        ])])
        var plan = RouteGuidePlan.plan(from: original)
        plan.days[0].stops.reverse()
        for index in plan.days[0].stops.indices {
            plan.days[0].stops[index].order = index + 1
        }
        for op in ["plan.move", "plan.revise"] {
            let effect = GuideEffect(op: op, plan: plan)
            let decoded = try JSONDecoder().decode(GuideEffect.self, from: JSONEncoder().encode(effect))
            let revised = try RouteGuidePlan.revising(original, with: XCTUnwrap(decoded.plan))
            let before = original.days[0].stops
            let after = revised.days[0].stops
            XCTAssertEqual(after.map(\.unavailable), [nil, .closedPoi, .place, nil], op)
            XCTAssertEqual(after.map(\.serverItemId), [nil, 22, 11, nil], op)
            XCTAssertTrue(after[0].visited, op)
            for rows in [before, after] {
                XCTAssertEqual(RouteAvailability.nextUnvisited(in: rows)?.stop.place.id, 3, op)
                XCTAssertEqual(RouteGeometry.totalKilometers(rows),
                               RouteGeometry.kilometers(visited.place, before[0].place), accuracy: 0.00001, op)
                let optimized = RouteGeometry.optimized(rows, pinStart: false, pinEnd: false)
                XCTAssertEqual(optimized[1].id, rows[1].id, op)
                XCTAssertEqual(optimized[2].id, rows[2].id, op)
                XCTAssertNil(RouteAvailability.nextKilometers(after: 1, in: rows), op)
                XCTAssertNil(RouteAvailability.nextKilometers(after: 2, in: rows), op)
            }
        }
    }

    func testStartDoesNotActivateDayWithOnlyUnavailableRemaining() {
        var visited = stop(1, longitude: 127)
        visited.visited = true
        let hidden = stop(2, longitude: 128, unavailable: .place)
        let closed = stop(3, longitude: 129, unavailable: .closedPoi)
        for rows in [[hidden, closed], [visited, hidden, closed], []] {
            var course = RouteCourse(serverId: 8, title: "여행", days: [
                RouteDay(stops: [stop(4, longitude: 127.1)]), RouteDay(stops: rows),
            ])
            XCTAssertNil(RouteAvailability.start(&course, dayIndex: 1))
            XCTAssertFalse(course.isRunning, "다른 일차나 이미 방문한 곳으로 대신 시작하지 않는다")
            XCTAssertEqual(RouteAvailability.hasOnlyUnavailableRemaining(in: rows), !rows.isEmpty)
        }
        XCTAssertFalse(RouteAvailability.hasOnlyUnavailableRemaining(in: [visited]), "방문 완료는 별도 상태다")
    }

    func testStartActivatesSelectedDayAtFirstVisitableUnvisitedStop() {
        var visited = stop(4, longitude: 127.2)
        visited.visited = true
        let available = stop(3, longitude: 127.1)
        var course = RouteCourse(serverId: 8, title: "여행", days: [
            RouteDay(stops: [stop(1, longitude: 127)]),
            RouteDay(stops: [visited, stop(2, longitude: 128, unavailable: .place), available]),
        ])
        XCTAssertEqual(RouteAvailability.start(&course, dayIndex: 1)?.id, available.id)
        XCTAssertTrue(course.isRunning)
        XCTAssertFalse(RouteAvailability.hasOnlyUnavailableRemaining(in: course.days[1].stops))
    }

    func testOptimizationPreservesUnavailableRowsAndUsesOnlyAvailableStops() {
        let first = stop(1, longitude: 127.2)
        let hidden = stop(2, longitude: 127, unavailable: .place)
        let nearest = stop(3, longitude: 127.01)
        let closed = stop(4, longitude: 129, unavailable: .closedPoi)
        let last = stop(5, longitude: 127.1)
        let rows = [first, hidden, nearest, closed, last]
        let here = PlaceSummary(id: 0, name: "현재", latitude: 37, longitude: 127)
        let starting = RouteGeometry.startingNearest(rows, to: here)
        XCTAssertEqual(starting.map(\.place.id), [3, 2, 1, 4, 5])
        let optimized = RouteGeometry.optimized(rows, pinStart: false, pinEnd: false)
        XCTAssertEqual(optimized[1].id, hidden.id)
        XCTAssertEqual(optimized[3].id, closed.id)
        XCTAssertEqual(Set(optimized.map(\.id)), Set(rows.map(\.id)))
        let activeOnly = RouteGeometry.optimized([first, nearest, last], pinStart: false, pinEnd: false)
        XCTAssertEqual(
            RouteGeometry.totalKilometers(optimized),
            RouteGeometry.totalKilometers(activeOnly),
            accuracy: 0.00001
        )
    }

    @MainActor
    func testExplicitNavigationToUnavailableRowNeverStartsOrRequestsRoute() {
        var requests = 0
        let trip = TripSession { _ in
            requests += 1
            throw URLError(.badServerResponse)
        }
        for notice in [UnavailableItem.place, .closedPoi] {
            trip.start(to: stop(1, longitude: 127, unavailable: notice), number: 1, courseId: 8)
            XCTAssertNil(trip.target)
            XCTAssertFalse(trip.isActive)
        }
        XCTAssertEqual(requests, 0)
    }

    func testNoticeAndRejectionsHaveKoreanAndEnglishStrings() {
        let before = AppLanguage.current
        defer { AppLanguage.current = before }
        AppLanguage.current = .ko
        XCTAssertEqual(UnavailableItem.place.message, "더 이상 소개하지 않는 장소예요")
        XCTAssertEqual(UnavailableItem.content.message, "더 이상 소개하지 않는 작품이에요")
        XCTAssertEqual(UnavailableItem.closedPoi.message, "폐업했어요")
        AppLanguage.current = .en
        XCTAssertEqual(UnavailableItem.place.message, "We no longer feature this place")
        XCTAssertEqual(UnavailableItem.content.message, "We no longer feature this title")
        XCTAssertEqual(UnavailableItem.closedPoi.message, "Closed permanently")
        for code in ["PLACE_DELISTED", "CONTENT_DELISTED", "POI_CLOSED"] {
            let message = UnavailableItem.rejection(status: 409, code: code)
            XCTAssertNotNil(message)
            XCTAssertTrue(message?.contains("can’t") == true)
        }
    }

    private func courseItem(source: CourseItemSource) -> CourseItem {
        CourseItem(id: 11, source: source, placeId: 1, poiId: 3, name: "장소",
                   latitude: 37, longitude: 127, dwellMinutes: 30)
    }

    private func stop(_ id: Int64, longitude: Double, unavailable: UnavailableItem? = nil) -> RouteStop {
        RouteStop(place: PlaceSummary(id: id, name: "장소", latitude: 37, longitude: longitude), unavailable: unavailable)
    }
}
