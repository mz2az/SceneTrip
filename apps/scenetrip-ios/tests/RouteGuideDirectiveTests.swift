import SceneApiClient
@testable import SceneTrip
import XCTest

/// 에이전트의 화면 명령(`GuideUiDirective`)을 읽는 규칙 — **아는 `op` 여섯은 갈래로, 모르는 `op` 는
/// `nil`** 로. 응답 전체를 버리지 않아야 에이전트가 명령을 늘려도 옛 앱이 깨지지 않는다.
final class RouteGuideDirectiveTests: XCTestCase {
    func testKnownOpsAreParsed() {
        XCTAssertEqual(
            RouteGuide.Directive(contract: GuideUiDirective(op: "map.focus", placeIds: [1, 2])),
            .mapFocus([1, 2])
        )
        XCTAssertEqual(
            RouteGuide.Directive(contract: GuideUiDirective(op: "route.draw", placeIds: [3])),
            .routeDraw([3])
        )
        XCTAssertEqual(
            RouteGuide.Directive(contract: GuideUiDirective(op: "place.card", placeId: 9)),
            .placeCard(9)
        )
        XCTAssertEqual(
            RouteGuide.Directive(contract: GuideUiDirective(op: "course.open", day: 2)),
            .courseOpen(2)
        )
        XCTAssertEqual(
            RouteGuide.Directive(contract: GuideUiDirective(op: "course.focus", day: 1, changed: ["개뿔"])),
            .courseFocus(day: 1, changed: ["개뿔"])
        )
        XCTAssertEqual(
            RouteGuide.Directive(contract: GuideUiDirective(op: "sheet.collapse")),
            .sheetCollapse
        )
    }

    /// 값이 빠진 명령은 할 수 있는 것이 없다 — 조용히 무시한다.
    func testOpsMissingTheirValueAreIgnored() {
        XCTAssertNil(RouteGuide.Directive(contract: GuideUiDirective(op: "place.card")))
        XCTAssertNil(RouteGuide.Directive(contract: GuideUiDirective(op: "course.open")))
        XCTAssertNil(RouteGuide.Directive(contract: GuideUiDirective(op: "map.focus")))
    }

    func testUnknownOpIsIgnoredNotFatal() {
        XCTAssertNil(RouteGuide.Directive(contract: GuideUiDirective(op: "hologram.show", day: 3)))
    }

    /// 찾아 준 장소는 출처가 id 앞에 붙는다 — 촬영지와 편의시설은 다른 표라 숫자만으로는 못 가른다.
    func testGuidePlaceKeepsItsSource() {
        let poi = RouteGuide.Place(guide: GuidePlace(
            id: 42, name: "정동커피", category: "카페", categoryGroup: .food,
            latitude: 37.5, longitude: 127.0, source: .poi
        ))
        XCTAssertEqual(poi.id, "poi-42")
        XCTAssertEqual(poi.poiId, 42)
        XCTAssertNil(poi.placeId)
        XCTAssertEqual(poi.serverId, 42)

        let spot = RouteGuide.Place(guide: GuidePlace(
            id: 42, name: "북촌한옥마을", category: "촬영지", categoryGroup: .sight,
            latitude: 37.5, longitude: 127.0, source: .place
        ))
        XCTAssertEqual(spot.id, "place-42")
        XCTAssertEqual(spot.placeId, 42)
        XCTAssertNil(spot.poiId)
    }
}
