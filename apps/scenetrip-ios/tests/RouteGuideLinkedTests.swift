@testable import SceneTrip
import XCTest

/// 챗봇 목록에서 **네이버에 연결된 곳을 표시한다** — 빼지 않는다 (2026-09-17 사용자 결정, MZ2AZ-327).
final class RouteGuideLinkedTests: XCTestCase {
    private func poi(_ id: Int64, _ name: String) -> RouteGuide.Place {
        RouteGuide.Place(id: "poi-\(id)", name: name, category: "카페", latitude: 37.56, longitude: 126.97)
    }

    /// **빼지 않는다.** 표시를 달고 연결된 것을 위로 올릴 뿐이다 — 그 안의 순서는 받은 그대로.
    func testMarksAndLiftsLinkedWithoutDropping() {
        let places = [poi(1, "가"), poi(2, "나"), poi(3, "다"), poi(4, "라")]
        let marked = RouteGuideLinked.marked(places, linked: [4, 2])
        XCTAssertEqual(marked.map(\.name), ["나", "라", "가", "다"])
        XCTAssertEqual(marked.map(\.linked), [true, true, false, false])
    }

    /// 촬영지는 네이버에 묻지 않는다 — 우리 상세가 있으니 늘 연결된 것으로 친다.
    func testShootingPlacesCountAsLinked() async {
        let place = RouteGuide.Place(id: "place-7", name: "덕수궁 돌담길", category: nil, latitude: 37.56, longitude: 126.97)
        let linked = await RouteGuideLinked.linkedIds(of: [place, poi(1, "가")], probe: { ids in
            XCTAssertEqual(ids, [1])
            return RouteGuideLinked.Probe()
        }, wait: { _ in })
        XCTAssertTrue(linked.isEmpty)
        XCTAssertEqual(RouteGuideLinked.marked([poi(1, "가"), place], linked: linked).map(\.name), ["덕수궁 돌담길", "가"])
    }

    /// `pending` 이었던 것**만** 다시 묻고, 그 사이에 채워지면 연결된 것이다.
    func testPendingIsAskedAgain() async {
        var asked: [[Int64]] = []
        let linked = await RouteGuideLinked.linkedIds(of: [poi(1, "가"), poi(2, "나")], probe: { ids in
            asked.append(ids)
            return asked.count == 1
                ? RouteGuideLinked.Probe(linked: [1], pending: [2])
                : RouteGuideLinked.Probe(linked: [2])
        }, wait: { _ in })
        XCTAssertEqual(asked, [[1, 2], [2]])
        XCTAssertEqual(linked, [1, 2])
    }

    /// 끝까지 `pending` 이면 연결 안 된 것이다. 처음 한 번 + 다시 세 번에서 멈춘다.
    func testGivesUpAfterRetries() async {
        var rounds = 0
        let linked = await RouteGuideLinked.linkedIds(of: [poi(1, "가")], probe: { _ in
            rounds += 1
            return RouteGuideLinked.Probe(pending: [1], retryAfter: 30)
        }, wait: { seconds in
            XCTAssertEqual(seconds, RouteGuideLinked.waitCapSeconds) // 서버 어림 30초를 그대로 기다리지 않는다
        })
        XCTAssertEqual(rounds, 4)
        XCTAssertTrue(linked.isEmpty)
    }
}
