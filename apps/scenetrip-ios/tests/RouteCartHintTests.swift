import Combine
import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class RouteCartHintTests: XCTestCase {
    func testOnlyNewManualEmptyCourseConsumesHint() {
        let store = RouteCartHintStore(storage: MemoryAIConsentStorage())
        let draft = RouteStore().emptyCourse(span: .sameDay, startDate: nil)
        var saved = draft
        saved.serverId = 42
        XCTAssertFalse(store.claim(course: saved))
        saved.detailLoaded = true
        XCTAssertFalse(store.claim(course: saved))
        var aiDraft = draft
        aiDraft.madeByAI = true
        XCTAssertFalse(store.claim(course: aiDraft))
        var filledDraft = draft
        filledDraft.days.append(RouteDay(stops: [RouteStop(place: RouteMock.pinnedPlace(
            name: "직접 찍은 곳", category: "기타", lat: 37.5, lng: 127
        ))]))
        XCTAssertFalse(store.claim(course: filledDraft))
        XCTAssertTrue(store.claim(course: draft))
        XCTAssertFalse(store.claim(course: draft))
    }

    func testManualSameDayWizardDraftIsEligibleWithoutSavedIDOrCartLoad() {
        let storage = MemoryAIConsentStorage()
        let draft = RouteStore().emptyCourse(span: .sameDay, startDate: nil)
        let editor = RouteEditorView(course: draft, isNew: true)
        XCTAssertTrue(editor.isNew)
        XCTAssertEqual(editor.course.id, draft.id)
        // 게스트 마법사의 「코스 만들기」는 저장·장바구니 조회 없이 이 초안을 연다.
        XCTAssertNil(draft.serverId)
        XCTAssertNil(draft.startDate)
        XCTAssertEqual(draft.days.count, 1)
        XCTAssertTrue(draft.stops.isEmpty)
        XCTAssertFalse(draft.madeByAI)
        XCTAssertEqual(storage.integer(forKey: "scenetrip.manualCourseCartHintSeen"), 0)
        XCTAssertTrue(RouteCartHintStore(storage: storage).claim(course: editor.course))
        XCTAssertEqual(RouteCartHintStore.messageKey(cartIsEmpty: true), "장소를 담으면 여기서 코스에 넣을 수 있어요")
        XCTAssertEqual(storage.integer(forKey: "scenetrip.manualCourseCartHintSeen"), 1)
        XCTAssertFalse(RouteCartHintStore(storage: storage).claim(course: draft))
    }

    func testUnsavedManualWizardDraftIsEligibleEvenWithExistingEditorPresentation() {
        let storage = MemoryAIConsentStorage()
        let draft = RouteStore().emptyCourse(span: .sameDay, startDate: nil)
        let editor = RouteEditorView(course: draft, isNew: false)
        // 표시 방식의 isNew와 달라도 서버에 없는 수동 빈 초안은 첫 안내 대상이다.
        XCTAssertTrue(RouteCartHintStore(storage: storage).claim(course: editor.course))
        XCTAssertEqual(storage.integer(forKey: "scenetrip.manualCourseCartHintSeen"), 1)
        XCTAssertFalse(RouteCartHintStore(storage: storage).claim(course: editor.course))
    }

    func testSeenFlagSurvivesStoreRecreationIncludingDismissal() {
        let storage = MemoryAIConsentStorage()
        let draft = RouteStore().emptyCourse(span: .sameDay, startDate: nil)
        XCTAssertTrue(RouteCartHintStore(storage: storage).claim(course: draft))
        // 표시 시점에 기록하므로 닫기·시간 만료·화면 이탈 후에도 다시 나타나지 않는다.
        XCTAssertFalse(RouteCartHintStore(storage: storage).claim(course: draft))
    }

    func testCartCopyDependsOnSavedPlaces() {
        XCTAssertEqual(RouteCartHintStore.messageKey(cartIsEmpty: true), "장소를 담으면 여기서 코스에 넣을 수 있어요")
        XCTAssertEqual(RouteCartHintStore.messageKey(cartIsEmpty: false), "담아둔 장소로 코스를 시작해 보세요")
    }

    func testSlowNonEmptyCartShowsSavedPlacesCopy() async {
        await assertDelayedCartHint(
            .success([CartItem(placeId: 42, name: "담아둔 장소", addedAt: Date(timeIntervalSince1970: 1))]),
            cartIsEmpty: false
        )
    }

    func testSlowEmptyCartShowsEmptyCopyOnlyAfterSuccess() async {
        await assertDelayedCartHint(.success([]), cartIsEmpty: true)
    }

    func testFailedCartLoadShowsGenericCopy() async {
        await assertDelayedCartHint(.failure(URLError(.notConnectedToInternet)), cartIsEmpty: false, loadState: .failed)
    }

    private func assertDelayedCartHint(
        _ result: Result<[CartItem], Error>, cartIsEmpty: Bool, loadState: CartStore.LoadState = .loaded
    ) async {
        let storage = MemoryAIConsentStorage()
        let hint = RouteCartHintStore(storage: storage)
        let client = DelayedHintCartClient(started: expectation(description: "장바구니 조회 시작"))
        let cart = CartStore(installId: UUID(), client: client)
        let highlighted = expectation(description: "조회 전 강조 시작")
        let observation = hint.$isHighlighted.filter { $0 }.prefix(1).sink { _ in highlighted.fulfill() }
        defer { observation.cancel() }
        let timerStarted = expectation(description: "문구 표시 후 노출 타이머 시작")
        var finishWindow: CheckedContinuation<Void, Error>?
        var announcements: [String] = []
        var durations: [Duration] = []
        let presentation = Task {
            await hint.present(course: RouteStore().emptyCourse(span: .sameDay, startDate: nil), cart: cart, sleep: {
                durations.append($0)
                try await withCheckedThrowingContinuation {
                    finishWindow = $0
                    timerStarted.fulfill()
                }
            }, announce: { announcements.append($0) })
        }
        await fulfillment(of: [highlighted], timeout: 1)
        XCTAssertEqual(cart.loadState, .notLoaded)
        XCTAssertNil(hint.message)
        let refresh = Task { await cart.refresh() }
        await fulfillment(of: [client.started], timeout: 1)
        XCTAssertEqual(cart.loadState, .loading)
        assertPendingHint(hint, storage: storage, announcements: announcements, durations: durations)
        client.reply?.resume(with: result.map { Cart(items: $0, totalCount: $0.count) })
        await refresh.value
        await fulfillment(of: [timerStarted], timeout: 1)
        let expected = tr(RouteCartHintStore.messageKey(cartIsEmpty: cartIsEmpty))
        XCTAssertEqual(cart.loadState, loadState)
        XCTAssertEqual(hint.message, expected)
        XCTAssertEqual(announcements, [expected])
        XCTAssertEqual(durations, [.milliseconds(2500)])
        XCTAssertEqual(storage.integer(forKey: "scenetrip.manualCourseCartHintSeen"), 1)
        finishWindow?.resume()
        await presentation.value
        XCTAssertNil(hint.message)
        XCTAssertFalse(hint.isHighlighted)
    }

    private func assertPendingHint(
        _ hint: RouteCartHintStore, storage: MemoryAIConsentStorage, announcements: [String], durations: [Duration]
    ) {
        XCTAssertTrue(hint.isHighlighted)
        XCTAssertNil(hint.message)
        XCTAssertTrue(announcements.isEmpty)
        XCTAssertTrue(durations.isEmpty, "조회 대기는 문구의 2.5초 노출 시간을 소모하지 않는다")
        XCTAssertEqual(storage.integer(forKey: "scenetrip.manualCourseCartHintSeen"), 0)
    }

    func testHintAndNearbyCopyIsBundledInKoreanAndEnglish() throws {
        let keys = [
            RouteCartHintStore.messageKey(cartIsEmpty: true),
            RouteCartHintStore.messageKey(cartIsEmpty: false),
            "지도 주변 촬영지 보기", "코스 추천받기 (AI)",
            "AI 일정은 편집할 초안으로 열려요. 확인한 뒤 직접 저장해 주세요.",
        ]
        for language in ["ko", "en"] {
            let url = try XCTUnwrap(Bundle(for: Self.self).url(
                forResource: "Localizable", withExtension: "strings", subdirectory: nil, localization: language
            ))
            let values = try XCTUnwrap(PropertyListSerialization.propertyList(
                from: Data(contentsOf: url), format: nil
            ) as? [String: String])
            for key in keys {
                let value = try XCTUnwrap(values[key])
                XCTAssertFalse(value.isEmpty)
                if language == "en" {
                    XCTAssertNotEqual(key, value)
                }
            }
        }
    }
}

@MainActor
private final class DelayedHintCartClient: CartClient {
    let started: XCTestExpectation
    var reply: CheckedContinuation<Cart, Error>?

    init(started: XCTestExpectation) {
        self.started = started
    }

    func fetch(installId _: UUID) async throws -> Cart {
        try await withCheckedThrowingContinuation {
            reply = $0
            started.fulfill()
        }
    }

    func add(installId _: UUID, placeId _: Int64, sourceContentId _: Int64?) async throws {}
    func remove(installId _: UUID, placeId _: Int64) async throws {}
}
