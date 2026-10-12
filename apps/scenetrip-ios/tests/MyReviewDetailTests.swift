import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class MyReviewDetailTests: XCTestCase {
    func testPoiAndPlaceWithSameIDUseDifferentRequests() async {
        var placesAsked = 0
        var poisAsked: [Int64] = []
        let store = MyReviewDetailStore(
            subject: .poi(7),
            loadPlace: { _ in
                placesAsked += 1
                throw URLError(.badServerResponse)
            },
            loadPoi: { id in
                poisAsked.append(id)
                return RouteGuide.Place(id: "poi-7", name: "카페", category: nil, latitude: 37.5, longitude: 127)
            }
        )
        await store.reload()
        XCTAssertEqual(placesAsked, 0)
        XCTAssertEqual(poisAsked, [7])
        guard case let .poi(place) = store.detail else { return XCTFail("편의시설 상세여야 한다") }
        XCTAssertEqual(place.poiId, 7)
    }

    func testFailureCanRetryWithoutShowingAnotherTarget() async {
        var attempts = 0
        let store = MyReviewDetailStore(subject: .place(7), loadPlace: { _ in
            attempts += 1
            if attempts == 1 {
                throw URLError(.notConnectedToInternet)
            }
            return PlaceDetail(id: 7, name: "경복궁", latitude: 37.5, longitude: 127)
        })
        await store.reload()
        XCTAssertTrue(store.failed)
        XCTAssertNil(store.detail)
        await store.reload()
        XCTAssertFalse(store.failed)
        guard case let .place(place) = store.detail else { return XCTFail("촬영지 상세여야 한다") }
        XCTAssertEqual(place.id, 7)
        XCTAssertEqual(attempts, 2)
    }

    func testMismatchedResponseDoesNotOpenAnotherPlace() async {
        let store = MyReviewDetailStore(subject: .place(7), loadPlace: { _ in
            PlaceDetail(id: 8, name: "다른 장소", latitude: 37.5, longitude: 127)
        })
        await store.reload()
        XCTAssertTrue(store.failed)
        XCTAssertNil(store.detail)
    }
}
