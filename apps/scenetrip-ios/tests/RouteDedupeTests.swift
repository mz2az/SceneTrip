import SceneApiClient
@testable import SceneTrip
import XCTest

/// 같은 곳을 두 번 담지 못한다 (2026-08-27).
///
/// **id 로만 거르면 안 된다.** 직접 찍은 핀과 가이드가 준 곳은 id 가 시각으로
/// 매겨져 같은 가게라도 값이 다르다 — 실제로 「달큰커피」가 한 일차에 3번·4번으로
/// 나란히 들어갔다.
final class RouteDedupeTests: XCTestCase {
    /// id 를 직접 준다. `RouteMock.pinnedPlace` 는 시각으로 매기는데, 테스트가
    /// 빨라 같은 밀리초에 걸리면 값이 겹쳐 전제가 무너진다(실제로 그랬다).
    private func place(_ id: Int64, _ name: String, _ lat: Double, _ lng: Double) -> PlaceSummary {
        PlaceSummary(id: id, name: name, latitude: lat, longitude: lng)
    }

    // MARK: 핀·가이드가 준 곳 (id 가 매번 다르다)

    /// **같은 이름·같은 자리는 id 가 달라도 한 번만.** 이 검사가 이 기능의 이유다.
    func testSameSpotWithDifferentIdsIsAddedOnce() {
        let first = place(-111, "달큰커피", 37.5665, 126.9780)
        let second = place(-222, "달큰커피", 37.5665, 126.9780)

        let fresh = RouteDedupe.fresh([first, second], takenIds: [], takenKeys: [])
        XCTAssertEqual(fresh.count, 1, "같은 가게가 두 번 담겼다")
    }

    /// 이미 담긴 것과 겹쳐도 안 담긴다.
    func testAlreadyTakenSpotIsDropped() {
        let taken = place(-111, "달큰커피", 37.5665, 126.9780)
        let again = place(-999, "달큰커피", 37.5665, 126.9780)

        let fresh = RouteDedupe.fresh(
            [again], takenIds: [], takenKeys: [RouteDedupe.key(taken)]
        )
        XCTAssertTrue(fresh.isEmpty, "이미 있는 곳이 또 담겼다")
    }

    /// 좌표 끝자리가 미세하게 달라도 **1 m 안이면 같은 곳**이다.
    func testTinyCoordinateDriftIsSameSpot() {
        let one = place(-1, "달큰커피", 37.566_500_1, 126.978_000_2)
        let two = place(-2, "달큰커피", 37.566_500_4, 126.978_000_9)

        XCTAssertEqual(RouteDedupe.fresh([one, two], takenIds: [], takenKeys: []).count, 1)
    }

    // MARK: 갈라야 하는 것

    /// 이름이 같아도 **자리가 다르면 다른 곳**이다 — 프랜차이즈 지점이 그렇다.
    func testSameNameDifferentPlaceStaysTwo() {
        let gwanghwamun = place(-1, "스타벅스", 37.5720, 126.9769)
        let cityhall = place(-2, "스타벅스", 37.5663, 126.9779)

        XCTAssertEqual(
            RouteDedupe.fresh([gwanghwamun, cityhall], takenIds: [], takenKeys: []).count, 2,
            "다른 지점이 하나로 합쳐졌다"
        )
    }

    // MARK: 촬영지 (서버 id 가 있다)

    /// 촬영지는 **id 로** 거른다.
    func testFilmingSpotIsFilteredById() {
        let bukchon = place(42, "북촌한옥마을", 37.5826, 126.9830)
        XCTAssertTrue(
            RouteDedupe.fresh([bukchon], takenIds: [42], takenKeys: []).isEmpty,
            "이미 담긴 촬영지가 또 담겼다"
        )
    }

    /// 안 담긴 것은 그대로 통과한다.
    func testFreshPlacesPassThrough() {
        let one = place(1, "경복궁", 37.5796, 126.9770)
        let two = place(-5, "달큰커피", 37.5665, 126.9780)

        XCTAssertEqual(
            RouteDedupe.fresh([one, two], takenIds: [99], takenKeys: []).count, 2
        )
    }
}
