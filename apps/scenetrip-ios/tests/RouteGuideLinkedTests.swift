@testable import SceneTrip
import XCTest

/// 챗봇 목록에서 **네이버에 연결 안 된 곳을 뺀다** (2026-09-17 사용자 결정, MZ2AZ-327 이 풀릴 때까지).
final class RouteGuideLinkedTests: XCTestCase {
    private func poi(_ id: Int64, _ name: String) -> RouteGuide.Place {
        RouteGuide.Place(id: "poi-\(id)", name: name, category: "카페", latitude: 37.56, longitude: 126.97)
    }

    /// 연결된 것만, **받은 순서 그대로** 남는다. 순서는 에이전트가 정한 것(거리·리뷰순)이다.
    func testKeepsOnlyLinkedInOrder() async {
        let places = [poi(1, "가"), poi(2, "나"), poi(3, "다")]
        let kept = await RouteGuideLinked.only(places, probe: { _ in
            RouteGuideLinked.Probe(linked: [3, 1])
        }, wait: { _ in })
        XCTAssertEqual(kept.map(\.name), ["가", "다"])
    }

    /// 촬영지는 네이버에 묻지 않는다 — 우리 상세가 있다.
    func testShootingPlacesPassThrough() async {
        let place = RouteGuide.Place(id: "place-7", name: "덕수궁 돌담길", category: nil, latitude: 37.56, longitude: 126.97)
        let kept = await RouteGuideLinked.only([place, poi(1, "가")], probe: { ids in
            XCTAssertEqual(ids, [1])
            return RouteGuideLinked.Probe()
        }, wait: { _ in })
        XCTAssertEqual(kept.map(\.name), ["덕수궁 돌담길"])
    }

    /// `pending` 이었던 것**만** 다시 묻고, 그 사이에 채워지면 남긴다.
    func testPendingIsAskedAgain() async {
        var asked: [[Int64]] = []
        let kept = await RouteGuideLinked.only([poi(1, "가"), poi(2, "나")], probe: { ids in
            asked.append(ids)
            return asked.count == 1
                ? RouteGuideLinked.Probe(linked: [1], pending: [2])
                : RouteGuideLinked.Probe(linked: [2])
        }, wait: { _ in })
        XCTAssertEqual(asked, [[1, 2], [2]])
        XCTAssertEqual(kept.map(\.name), ["가", "나"])
    }

    /// 끝까지 `pending` 이면 뺀다. 처음 한 번 + 다시 세 번에서 멈춘다.
    func testGivesUpAfterRetries() async {
        var rounds = 0
        let kept = await RouteGuideLinked.only([poi(1, "가")], probe: { _ in
            rounds += 1
            return RouteGuideLinked.Probe(pending: [1], retryAfter: 30)
        }, wait: { seconds in
            XCTAssertEqual(seconds, RouteGuideLinked.waitCapSeconds) // 서버 어림 30초를 그대로 기다리지 않는다
        })
        XCTAssertEqual(rounds, 4)
        XCTAssertTrue(kept.isEmpty)
    }
}
