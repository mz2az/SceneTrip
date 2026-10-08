import SceneApiClient
@testable import SceneTrip
import XCTest

/// 머무는 시간 — 일차 머리줄의 합과 고르는 값 (MZ2AZ-368).
///
/// 목록 줄의 칩을 빼고 합을 머리줄로 옮겼다. **칩이 사라져도 값은 남아야 한다** — 여기서
/// 잡아 두지 않으면 「안 보이니 기본값으로」 덮어쓰는 변경이 조용히 들어온다.
final class RouteStayTests: XCTestCase {
    private func stop(_ name: String, stay: Int, id: Int64 = 1) -> RouteStop {
        RouteStop(
            place: PlaceSummary(id: id, name: name, latitude: 37.5, longitude: 127.0),
            stayMinutes: stay
        )
    }

    // MARK: 합

    /// 합은 **머무는 시간만** 더한다. 이동 시간은 앱이 모르고, 지어내지 않는다.
    func testTotalAddsOnlyStayMinutes() {
        let stops = [stop("병원", stay: 30), stop("마트", stay: 45), stop("공원", stay: 120)]
        XCTAssertEqual(RouteStop.stayTotal(stops), 195)
    }

    /// 빈 일차는 0 이다 — 머리줄은 이때 합을 그리지 않는다.
    func testTotalOfEmptyDayIsZero() {
        XCTAssertEqual(RouteStop.stayTotal([]), 0)
    }

    /// 한 곳을 고치면 합이 그만큼 바뀐다 — 시트에서 고른 값이 머리줄에 바로 보이는 근거.
    func testTotalFollowsAnEdit() {
        var stops = [stop("병원", stay: 30), stop("마트", stay: 30)]
        XCTAssertEqual(RouteStop.stayTotal(stops), 60)
        stops[1].stayMinutes = 90
        XCTAssertEqual(RouteStop.stayTotal(stops), 120)
    }

    // MARK: 고르는 값

    /// 지금 값이 선택지에 있으면 선택지 그대로다.
    func testChoicesAreTheOptionsWhenCurrentIsOne() {
        XCTAssertEqual(RouteStop.stayChoices(current: 30), RouteStop.stayOptions)
    }

    /// **AI 초안의 40분도 목록에 보인다**, 제 자리에 끼어서. 빼 두면 지금 값에 표시가 안
    /// 붙고, 다른 값을 눌렀다가 되돌릴 길이 없다.
    func testChoicesKeepAnOffListCurrentValueInOrder() {
        XCTAssertEqual(RouteStop.stayChoices(current: 40), [15, 30, 40, 45, 60, 90, 120, 180])
        XCTAssertEqual(RouteStop.stayChoices(current: 240).last, 240)
    }

    // MARK: 저장

    /// **칩이 없어도 저장은 값을 그대로 싣는다.** 기본값(30분)으로 덮어쓰지 않는다 — 비우면
    /// 서버가 장소 유형별 기본값으로 바꿔 사용자가 정한 시간이 사라진다(`RouteBridge.item`).
    func testSaveCarriesEachStopsOwnStayMinutes() {
        let course = RouteCourse(
            serverId: 1,
            title: "코스",
            days: [RouteDay(stops: [stop("병원", stay: 40, id: 82), stop("마트", stay: 120, id: 49)])]
        )
        let body = RouteBridge.replace(from: course)
        XCTAssertEqual(body.days[0].items.map(\.dwellMinutes), [40, 120])
    }
}
