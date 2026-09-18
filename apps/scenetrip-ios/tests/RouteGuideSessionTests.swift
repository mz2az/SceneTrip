@testable import SceneTrip
import XCTest

/// 가이드 대화는 **코스의 것이다** (2026-09-17 사용자 지적 — 새 코스에 앞 코스의 AI 장소가 찍혔다).
@MainActor
final class RouteGuideSessionTests: XCTestCase {
    func testBindingAnotherCourseStartsFresh() {
        let session = RouteGuideSession()
        session.bind(to: "course-16")
        session.picked = RouteGuide.Place(id: "poi-1", name: "가", category: nil, latitude: 37.5, longitude: 127)
        session.bind(to: "course-16") // 같은 코스를 다시 열었다 — 그대로
        XCTAssertNotNil(session.picked)
        session.bind(to: "draft-ABC") // 새 코스
        XCTAssertNil(session.picked)
        XCTAssertTrue(session.places.isEmpty)
        XCTAssertTrue(session.isEmpty)
    }

    /// 저장해서 서버 id 를 얻은 것은 같은 코스다 — 다시 열어도 대화가 남는다.
    func testRekeyKeepsConversation() {
        let session = RouteGuideSession()
        session.bind(to: "draft-ABC")
        session.picked = RouteGuide.Place(id: "poi-1", name: "가", category: nil, latitude: 37.5, longitude: 127)
        session.rekey(to: "course-21")
        session.bind(to: "course-21")
        XCTAssertNotNil(session.picked)
    }
}
