import SceneApiClient
@testable import SceneTrip
import XCTest

/// AI 초안이 **지역으로 먼저 거르는가** (2026-08-24).
///
/// 이 검사가 없으면 회귀를 눈으로 못 잡는다. 화면에는 「1일차 5곳」으로 멀쩡히
/// 보이는데 그 5곳이 강원 정선과 서울 종로를 오가는 362 km 짜리일 수 있다 —
/// 실제로 그렇게 나왔었다. **숫자로 고정해 두어야 한다.**
final class RoutePlannerRegionTests: XCTestCase {
    private func place(_ id: Int64, _ latitude: Double, _ longitude: Double) -> PlaceSummary {
        PlaceSummary(id: id, name: "p\(id)", latitude: latitude, longitude: longitude)
    }

    /// 서울 도심에 촘촘히 놓인 곳들. 실제 데이터의 서울 덩어리를 흉내 낸다.
    private func seoul(_ count: Int, from start: Int64 = 1) -> [PlaceSummary] {
        (0 ..< count).map { index in
            place(start + Int64(index),
                  37.55 + Double(index % 5) * 0.01,
                  126.98 + Double(index / 5) * 0.01)
        }
    }

    // MARK: 거르기

    /// **먼 곳은 후보에서 빠진다.** 강원 정선(37.16, 128.93)은 서울에서 176 km 다.
    func testFarPlacesAreDropped() {
        let far = place(900, 37.16, 128.93)
        let pool = RoutePlanner.regionPool(seoul(20) + [far], need: 10)

        XCTAssertFalse(pool.contains { $0.id == 900 }, "176 km 떨어진 곳이 후보에 남았다")
        XCTAssertGreaterThanOrEqual(pool.count, 10, "필요한 만큼은 담겨야 한다")
    }

    /// **모자라면 가까운 덩어리를 붙인다.** 한 덩어리가 필요량보다 작을 때
    /// 빈손으로 두면 코스가 못 만들어진다.
    func testNearbyClusterIsAddedWhenShort() {
        // 서울 5곳 + 30 km 밖(같은 수도권) 5곳. 10곳이 필요하다.
        let near = (0 ..< 5).map { place(100 + Int64($0), 37.82, 127.05) }
        let pool = RoutePlanner.regionPool(seoul(5) + near, need: 10)

        XCTAssertEqual(pool.count, 10, "모자란 만큼 옆 덩어리를 붙여야 한다")
    }

    /// **아주 먼 덩어리보다 가까운 덩어리를 먼저 붙인다.**
    func testNearerClusterWinsOverFartherOne() {
        let near = (0 ..< 5).map { place(200 + Int64($0), 37.82, 127.05) } // 수도권
        let far = (0 ..< 5).map { place(300 + Int64($0), 33.49, 126.50) } // 제주
        let pool = RoutePlanner.regionPool(seoul(5) + far + near, need: 10)

        XCTAssertTrue(pool.contains { $0.id == 200 }, "가까운 덩어리가 안 붙었다")
        XCTAssertFalse(pool.contains { $0.id == 300 }, "제주가 먼저 붙었다")
    }

    /// 후보가 필요량보다 적으면 **거르지 않는다.** 거를 여유가 없다.
    func testSmallInputIsLeftAlone() {
        let few = seoul(3)
        XCTAssertEqual(RoutePlanner.regionPool(few, need: 10).count, 3)
    }

    /// **장소를 잃거나 늘리지 않는다.**
    func testNoPlaceIsInventedOrDuplicated() {
        let all = seoul(20) + [place(900, 37.16, 128.93), place(901, 33.49, 126.50)]
        let pool = RoutePlanner.regionPool(all, need: 10)

        XCTAssertEqual(Set(pool.map(\.id)).count, pool.count, "같은 곳이 두 번 담겼다")
        XCTAssertTrue(Set(pool.map(\.id)).isSubset(of: Set(all.map(\.id))), "없던 곳이 생겼다")
    }

    // MARK: 지금 있는 자리

    /// **부산에 있으면 부산 쪽 덩어리가 먼저다.** 자리를 안 보면 늘 가장 큰 덩어리
    /// (대개 서울)가 나온다.
    func testNearestClusterWinsWhenInKorea() {
        let busan = (0 ..< 4).map { place(400 + Int64($0), 35.16 + Double($0) * 0.01, 129.06) }
        let all = seoul(20) + busan

        let pool = RoutePlanner.regionPool(all, need: 4, near: (35.18, 129.07))
        XCTAssertTrue(pool.prefix(4).allSatisfy { $0.id >= 400 }, "부산에서 서울 덩어리가 먼저 왔다")
    }

    /// **한국 밖이면 자리를 무시한다.** 오기 전에 짜는 사람에게 「가장 가까운 곳」은
    /// 뜻이 없다 — 도쿄에서 재나 뉴욕에서 재나 한국 어딘가가 가장 가까울 뿐이다.
    func testLocationOutsideKoreaIsIgnored() {
        let busan = (0 ..< 4).map { place(400 + Int64($0), 35.16 + Double($0) * 0.01, 129.06) }
        let all = seoul(20) + busan

        // 시뮬레이터 기본 위치(미국 쿠퍼티노).
        let pool = RoutePlanner.regionPool(all, need: 4, near: (37.33, -122.03))
        XCTAssertTrue(pool.prefix(4).allSatisfy { $0.id < 400 }, "한국 밖인데 자리를 썼다")
    }

    /// 자리를 안 주면 **가장 큰 덩어리**다 — 예전 동작 그대로.
    func testBiggestClusterWhenNoLocation() {
        let busan = (0 ..< 4).map { place(400 + Int64($0), 35.16 + Double($0) * 0.01, 129.06) }
        let pool = RoutePlanner.regionPool(seoul(20) + busan, need: 4)
        XCTAssertTrue(pool.prefix(4).allSatisfy { $0.id < 400 })
    }

    // MARK: 효과

    /// **거른 뒤가 실제로 더 짧아야 한다.** 이 검사가 이 기능의 존재 이유다.
    func testFilteringShortensTheDay() {
        // 서울 12곳 + 전국에 흩어진 3곳. 인기순(앞에서부터)이라면 먼 곳이 섞인다.
        let scattered = [
            place(900, 37.16, 128.93), // 정선
            place(901, 35.18, 129.07), // 부산
            place(902, 33.49, 126.50), // 제주
        ]
        let all = scattered + seoul(12, from: 1) // 먼 곳이 앞에 오게 둔다

        let before = Array(all.prefix(5))
        let after = Array(RoutePlanner.regionPool(all, need: 5).prefix(5))

        let spanBefore = RouteGeometry.totalKilometers(before.map { RouteStop(place: $0) })
        let spanAfter = RouteGeometry.totalKilometers(after.map { RouteStop(place: $0) })

        XCTAssertLessThan(spanAfter, spanBefore / 5, "거른 효과가 없다")
        XCTAssertLessThan(spanAfter, 100, "거르고도 하루 100 km 를 넘는다")
    }
}
