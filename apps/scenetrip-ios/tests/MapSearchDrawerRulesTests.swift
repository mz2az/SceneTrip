import CoreGraphics
@testable import SceneTrip
import XCTest

/// 지도 검색 결과 서랍 끌기 — 42% / 78% 에 붙기, 튕기기, 끝에서 조금만 늘어나기, 손잡이 누르기
/// (계획 docs/project/plans/motion-works-drawer-parity.md §3). 세로 거리·속도는 아래가 +.
final class MapSearchDrawerRulesTests: XCTestCase {
    /// 지도 높이 1000 → 42% 는 420, 78% 는 780, 가운데는 600.
    private let map: CGFloat = 1000

    // MARK: 숫자

    func testSharesFlickAndDuration() {
        XCTAssertEqual(MapSearchResults.shortShare, 0.42)
        XCTAssertEqual(MapSearchResults.tallShare, 0.78)
        XCTAssertEqual(MapSearchDrawerRules.flickSpeed, 600)
        XCTAssertEqual(MapSearchDrawerRules.snapDuration, 0.28, accuracy: 0.0001)
    }

    // MARK: 끄는 동안

    func testNoMovementStaysAtStart() {
        XCTAssertEqual(MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: 0, mapHeight: map), 420, accuracy: 0.001)
        XCTAssertEqual(MapSearchDrawerRules.dragHeight(startShare: 0.78, translation: 0, mapHeight: map), 780, accuracy: 0.001)
    }

    func testFollowsFingerBetweenLimits() {
        XCTAssertEqual(MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: -100, mapHeight: map), 520, accuracy: 0.001)
        XCTAssertEqual(MapSearchDrawerRules.dragHeight(startShare: 0.78, translation: 200, mapHeight: map), 580, accuracy: 0.001)
        XCTAssertEqual(MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: -360, mapHeight: map), 780, accuracy: 0.001)
    }

    func testScalesWithMapHeight() {
        // 지도 700 → 42% 는 294.
        XCTAssertEqual(MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: -50, mapHeight: 700), 344, accuracy: 0.001)
    }

    func testRubberBandAboveTall() {
        let over100 = MapSearchDrawerRules.dragHeight(startShare: 0.78, translation: -100, mapHeight: map)
        XCTAssertGreaterThan(over100, 780)
        XCTAssertLessThanOrEqual(over100, 780 + 50, "78% 위로는 손가락의 절반도 안 따라간다")
        let over200 = MapSearchDrawerRules.dragHeight(startShare: 0.78, translation: -200, mapHeight: map)
        XCTAssertGreaterThan(over200, over100, "더 끌면 조금 더 늘어난다")
        XCTAssertLessThan(over200, 980)
    }

    func testRubberBandOnlyForThePartPastTheLimit() {
        // 42% 에서 400 올리면 360 은 그대로 따라가고 넘친 40 만 덜 따라간다.
        let height = MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: -400, mapHeight: map)
        XCTAssertGreaterThan(height, 780)
        XCTAssertLessThan(height, 800)
    }

    func testRubberBandBelowShort() {
        let under100 = MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: 100, mapHeight: map)
        XCTAssertLessThan(under100, 420)
        XCTAssertGreaterThanOrEqual(under100, 420 - 50)
        let under200 = MapSearchDrawerRules.dragHeight(startShare: 0.42, translation: 200, mapHeight: map)
        XCTAssertLessThan(under200, under100)
        XCTAssertGreaterThan(under200, 220)
    }

    // MARK: 손을 뗐을 때 — 가까운 쪽

    func testSnapsToNearestWithoutVelocity() {
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 590, velocity: 0, mapHeight: map), 0.42)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 610, velocity: 0, mapHeight: map), 0.78)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 300, velocity: 0, mapHeight: map), 0.42)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 900, velocity: 0, mapHeight: map), 0.78)
    }

    func testSlowReleaseStillPicksNearest() {
        // 아래로 599pt/초는 튕긴 것이 아니다 — 700 은 78% 가 가깝다.
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 700, velocity: 599, mapHeight: map), 0.78)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 500, velocity: -599, mapHeight: map), 0.42)
    }

    // MARK: 손을 뗐을 때 — 튕기기

    func testFastFlickUpGoesTall() {
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 450, velocity: -700, mapHeight: map), 0.78)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 420, velocity: -600, mapHeight: map), 0.78)
    }

    func testFastFlickDownGoesShort() {
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 760, velocity: 700, mapHeight: map), 0.42)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 780, velocity: 600, mapHeight: map), 0.42)
    }

    func testFlickFromRubberBandArea() {
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 820, velocity: 650, mapHeight: map), 0.42)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 380, velocity: -650, mapHeight: map), 0.78)
        XCTAssertEqual(MapSearchDrawerRules.settledShare(height: 820, velocity: -50, mapHeight: map), 0.78)
    }

    // MARK: 손잡이 누르기

    func testTapTogglesShortAndTall() {
        XCTAssertEqual(MapSearchDrawerRules.tappedShare(0.42), 0.78)
        XCTAssertEqual(MapSearchDrawerRules.tappedShare(0.78), 0.42)
    }

    func testTapAgreesWithResultsState() {
        var results = MapSearchResults(query: "카페")
        XCTAssertEqual(MapSearchDrawerRules.tappedShare(results.drawerShare), 0.78)
        results.toggleDrawer()
        XCTAssertEqual(results.drawerShare, 0.78)
        XCTAssertEqual(MapSearchDrawerRules.tappedShare(results.drawerShare), 0.42)
    }
}
