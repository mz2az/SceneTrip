import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class CartStoreTests: XCTestCase {
    func testOnlyDuplicateConflictMarksPlaceAsSavedWhenReloadFails() async {
        for code in ["DUPLICATE_CART_ITEM", "PLACE_DELISTED", "UNKNOWN"] {
            let client = CartFakeClient()
            client.addError = .error(
                409, Data("{\"code\":\"\(code)\",\"message\":\"conflict\"}".utf8), nil,
                URLError(.badServerResponse)
            )
            client.fetchError = URLError(.notConnectedToInternet)
            let store = CartStore(installId: UUID(), client: client)

            await store.add(placeId: 99)

            XCTAssertEqual(store.contains(99), code == "DUPLICATE_CART_ITEM")
            if code == "PLACE_DELISTED" {
                XCTAssertEqual(store.toast, tr("목록에서 빠진 장소라 담을 수 없어요."))
            }
        }
    }

    func testFailedDeletePreservesRowsAndSavedIcons() async {
        let failures: [Error] = [serverError(503), serverError(404), URLError(.timedOut), URLError(.notConnectedToInternet)]
        for error in failures {
            let client = CartFakeClient()
            let store = CartStore(installId: UUID(), client: client)
            await store.refresh()
            let original = store.items
            client.deleteError = error
            client.fetchError = URLError(.notConnectedToInternet)

            await store.remove(placeId: 48)

            XCTAssertEqual(store.items, original)
            XCTAssertEqual(store.placeIds, [48, 49])
            XCTAssertEqual(store.toast, tr("빼지 못했습니다. 잠시 후 다시 시도해 주세요"))
            XCTAssertEqual(client.fetches, 1, "삭제 실패를 성공한 새 조회로 덮지 않는다")
        }
    }

    func testDelistedRowCanBeRemovedFromObservedCart() async {
        let client = CartFakeClient()
        client.items[0].delisted = true
        let store = CartStore(installId: UUID(), client: client)
        await store.refresh()
        XCTAssertEqual(UnavailableItem.notice(for: store.items[0]), .place)

        await store.remove(placeId: 48)

        XCTAssertEqual(store.items.map(\.placeId), [49])
        XCTAssertEqual(store.items.count, 1, "마이페이지 개수와 시트가 같은 저장소를 관찰한다")
        XCTAssertFalse(store.contains(48))
        XCTAssertEqual(client.deleted, [48])
    }

    func testSuccessfulDeleteUpdatesRowsAndIconsEvenWhenReloadFails() async {
        let client = CartFakeClient()
        let installId = UUID()
        let store = CartStore(installId: installId, client: client)
        await store.refresh()
        client.fetchError = URLError(.notConnectedToInternet)

        await store.remove(placeId: 48)

        XCTAssertEqual(store.items.map(\.placeId), [49])
        XCTAssertEqual(store.placeIds, [49])
        XCTAssertEqual(store.toast, tr("장바구니에서 뺐습니다"))
        XCTAssertEqual(client.deleted, [48])
        XCTAssertEqual(client.installIds, [installId, installId, installId])
        XCTAssertEqual(store.items.first?.sourceContentId, 7)
    }

    func testSuccessfulReloadKeepsRemainingServerOrderAndSource() async {
        let client = CartFakeClient()
        let store = CartStore(installId: UUID(), client: client)
        await store.refresh()

        await store.remove(placeId: 49)

        XCTAssertEqual(store.items.map(\.placeId), [48])
        XCTAssertEqual(store.placeIds, [48])
        XCTAssertEqual(store.items.first?.sourceContentTitle, "작품 A")
        XCTAssertEqual(client.fetches, 2)
    }

    private func serverError(_ status: Int) -> ErrorResponse {
        .error(status, nil, nil, URLError(.badServerResponse))
    }
}

@MainActor
private final class CartFakeClient: CartClient {
    var addError: ErrorResponse?
    var items = [
        CartItem(placeId: 48, name: "장소 A", sourceContentId: 6, sourceContentTitle: "작품 A",
                 addedAt: Date(timeIntervalSince1970: 1)),
        CartItem(placeId: 49, name: "장소 B", sourceContentId: 7, sourceContentTitle: "작품 B",
                 addedAt: Date(timeIntervalSince1970: 2)),
    ]
    var deleteError: Error?
    var fetchError: Error?
    var fetches = 0
    var deleted: [Int64] = []
    var installIds: [UUID] = []

    func fetch(installId: UUID) async throws -> Cart {
        installIds.append(installId)
        fetches += 1
        if let fetchError {
            throw fetchError
        }
        return Cart(items: items, totalCount: items.count)
    }

    func remove(installId: UUID, placeId: Int64) async throws {
        installIds.append(installId)
        deleted.append(placeId)
        if let deleteError {
            throw deleteError
        }
        items.removeAll { $0.placeId == placeId }
    }

    func add(installId _: UUID, placeId: Int64, sourceContentId: Int64?) async throws {
        if let addError {
            throw addError
        }
        items.append(CartItem(placeId: placeId, name: "새 장소", sourceContentId: sourceContentId, addedAt: Date()))
    }
}
