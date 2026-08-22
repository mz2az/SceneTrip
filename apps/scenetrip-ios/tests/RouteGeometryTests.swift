import SceneApiClient
@testable import SceneTrip
import XCTest

/// 동선 최적화 (MZ2AZ-247).
///
/// **눈으로 「짧아 보인다」로는 검사가 안 된다.** 최적화는 숫자가 줄었는지가 전부이고,
/// 특히 최근접 이웃이 지는 경우를 잡아 두지 않으면 세 방법을 돌리는 이유가 사라진다.
///
/// API 를 부르지 않는 순수 계산이라 이 테스트도 네트워크가 필요 없다.
final class RouteGeometryTests: XCTestCase {
    /// 좌표만 있으면 되므로 이름·유형은 최소로 채운다.
    private func stop(_ latitude: Double, _ longitude: Double, _ name: String) -> RouteStop {
        RouteStop(place: PlaceSummary(
            id: Int64(abs(name.hashValue % 100_000)),
            name: name,
            latitude: latitude,
            longitude: longitude
        ))
    }

    // MARK: 고정된 것

    /// **첫 장소는 움직이지 않는다.** 사용자가 맨 위에 둔 곳은 대개 출발지(숙소·역)라
    /// 그것까지 바꾸면 최적화가 아니라 남의 일정이 된다.
    func testFirstStopStaysPut() {
        let stops = [
            stop(37.50, 127.10, "숙소"),
            stop(37.60, 126.98, "경복궁"),
            stop(37.51, 127.09, "코엑스"),
            stop(37.58, 126.99, "북촌"),
        ]
        let result = RouteGeometry.optimized(stops)
        XCTAssertEqual(result.first?.place.name, "숙소")
    }

    /// 장소를 잃거나 늘리지 않는다 — 순서만 바꾼다.
    func testKeepsEveryStopExactlyOnce() {
        let stops = [
            stop(37.50, 127.10, "숙소"),
            stop(37.60, 126.98, "경복궁"),
            stop(37.51, 127.09, "코엑스"),
            stop(37.58, 126.99, "북촌"),
            stop(37.55, 126.97, "남산"),
        ]
        let result = RouteGeometry.optimized(stops)
        XCTAssertEqual(result.count, stops.count)
        XCTAssertEqual(Set(result.map(\.id)), Set(stops.map(\.id)))
    }

    /// 두 곳 이하는 바꿀 순서가 없다.
    func testTooFewStopsAreLeftAlone() {
        let two = [stop(37.5, 127.0, "가"), stop(37.6, 127.1, "나")]
        XCTAssertEqual(RouteGeometry.optimized(two).map(\.id), two.map(\.id))
        XCTAssertEqual(RouteGeometry.optimized([]).count, 0)
    }

    // MARK: 실제로 짧아지는가

    /// 엉킨 순서를 넣으면 총 거리가 **줄어든다.**
    func testTangledOrderGetsShorter() {
        let stops = [
            stop(37.50, 127.00, "출발"),
            stop(37.58, 127.00, "멀리"),
            stop(37.51, 127.00, "가까이"),
            stop(37.57, 127.00, "멀리2"),
            stop(37.52, 127.00, "가까이2"),
        ]
        let before = RouteGeometry.totalKilometers(stops)
        let after = RouteGeometry.totalKilometers(RouteGeometry.optimized(stops))
        XCTAssertLessThan(after, before)
    }

    /// **최근접 이웃이 지는 경우를 잡는다.** 이 테스트가 세 방법을 돌리는 근거다.
    ///
    /// 배치를 고르는 데 한 번 실패했다. 출발지를 가운데 두고 좌우로 벌리면 **어느 순서로
    /// 돌아도 총 거리가 같아** 최근접 이웃이 지지 않는다. 지는 조건은 따로 있다 —
    /// **바로 옆의 미끼**다.
    ///
    /// 출발지 코앞(0.4km)에 「가까운 미끼」를 두고, 반대쪽에 중간 거리(2.6km),
    /// 그 너머에 먼 무리(8.8km)를 둔다. 최근접 이웃은 미끼를 먼저 집고 되돌아 나오느라
    /// 손해를 본다. 되돌아 나오는 그 한 번이 최적해와의 차이다.
    func testBeatsPlainNearestNeighbour() {
        let stops = [
            stop(37.50, 127.000, "출발"),
            stop(37.50, 127.005, "미끼"), // 출발지 코앞 — 최근접 이웃이 먼저 문다
            stop(37.50, 126.970, "반대쪽"), // 미끼와 반대 방향
            stop(37.50, 127.100, "먼무리1"),
            stop(37.50, 127.101, "먼무리2"),
            stop(37.50, 127.102, "먼무리3"),
        ]
        let optimized = RouteGeometry.totalKilometers(RouteGeometry.optimized(stops))

        // 같은 입력에 최근접 이웃만 적용한 값을 손으로 만든다.
        var current = stops[0]
        var remaining = Array(stops.dropFirst())
        var greedy = [current]
        while !remaining.isEmpty {
            let anchor = current
            let index = remaining.indices.min {
                RouteGeometry.kilometers(anchor.place, remaining[$0].place)
                    < RouteGeometry.kilometers(anchor.place, remaining[$1].place)
            }!
            current = remaining.remove(at: index)
            greedy.append(current)
        }
        let nearestOnly = RouteGeometry.totalKilometers(greedy)

        XCTAssertLessThan(optimized, nearestOnly, "세 방법을 돌린 결과가 최근접 이웃보다 짧아야 한다")
    }

    /// 이미 최적인 순서를 넣으면 **더 나빠지지 않는다.**
    func testAlreadyOptimalOrderIsNotWorsened() {
        let stops = [
            stop(37.50, 127.000, "1"),
            stop(37.50, 127.005, "2"),
            stop(37.50, 127.010, "3"),
            stop(37.50, 127.015, "4"),
        ]
        let before = RouteGeometry.totalKilometers(stops)
        let after = RouteGeometry.totalKilometers(RouteGeometry.optimized(stops))
        XCTAssertLessThanOrEqual(after, before + 1e-9)
    }

    // MARK: 멈추지 않는가

    /// 완전탐색을 건너뛰는 크기(중간 9개 이상)에서도 답이 나오고 멈추지 않는다.
    /// 9개면 순열이 362,880 가지라 그대로 돌리면 화면이 선다.
    func testLargeDayStillReturnsQuickly() {
        let stops = (0 ..< 12).map { index in
            stop(37.50 + Double(index % 4) * 0.01,
                 127.00 + Double(index % 5) * 0.01,
                 "장소\(index)")
        }
        let started = Date()
        let result = RouteGeometry.optimized(stops)
        XCTAssertEqual(result.count, stops.count)
        XCTAssertLessThan(Date().timeIntervalSince(started), 2.0)
    }
}
