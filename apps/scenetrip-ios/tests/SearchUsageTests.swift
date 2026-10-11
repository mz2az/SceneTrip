import SceneApiClient
@testable import SceneTrip
import XCTest

@MainActor
final class SearchUsageTests: XCTestCase {
    private var oldAnalytics: AnalyticsSink!
    private var analytics: SearchAnalyticsRecorder!

    override func setUp() async throws {
        oldAnalytics = AppAnalytics.sink
        analytics = SearchAnalyticsRecorder()
        AppAnalytics.sink = analytics
    }

    override func tearDown() async throws {
        AppAnalytics.sink = oldAnalytics
    }

    func testCommitRecordsNewQueryTotalAfterFirstSuccessInsteadOfOldCatalog() async throws {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        data.search("")
        await replies.wait(for: nil)
        replies.succeed(nil, contents: 128, places: 521)
        await waitUntil { data.phase == .loaded }
        data.commitSearch("  제주  ", kind: "content", target: .content)
        await replies.wait(for: "제주")
        XCTAssertEqual(recorded.count, 0, "새 검색 전의 128편을 새 검색어에 붙이지 않는다")
        XCTAssertEqual(analytics.events, [.search(termLength: 2, kind: "content")])
        replies.succeed("제주", contents: 7, places: 19)
        await waitUntil { recorded.count == 1 }
        let event = try XCTUnwrap(recorded.first)
        XCTAssertEqual(event.type, .search)
        XCTAssertEqual(event.query, "제주")
        XCTAssertEqual(event.resultCount, 7)
        XCTAssertNil(event.entityType)
        XCTAssertEqual(data.contentPaging.total, 7)
    }

    func testPlaceCommitAndZeroResultsUseFreshServerTotal() async {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        data.commitSearch("수원", kind: "place", target: .place)
        await replies.wait(for: "수원")
        replies.succeed("수원", contents: 3, places: 250)
        await waitUntil { recorded.count == 1 }
        XCTAssertEqual(recorded.first?.resultCount, 250, "첫 페이지의 행 수가 아니라 탭의 총수")
        data.commitSearch("없는말", kind: nil, target: .place)
        await replies.wait(for: "없는말")
        replies.succeed("없는말", contents: 0, places: 0)
        await waitUntil { recorded.count == 2 }
        XCTAssertEqual(recorded.last?.query, "없는말")
        XCTAssertEqual(recorded.last?.resultCount, 0)
        XCTAssertEqual(analytics.events.count, 2)
    }

    func testFailedCommitDiscardsIntentAndSuccessfulRetryDoesNotRecordAgain() async {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        data.commitSearch("제주", kind: nil, target: .content)
        await replies.wait(for: "제주")
        replies.fail("제주")
        await waitUntil {
            if case .failed = data.phase {
                true
            } else {
                false
            }
        }
        XCTAssertEqual(recorded.count, 0, "실패는 검색 결과 0건이 아니다")
        data.retry()
        await replies.wait(for: "제주")
        replies.succeed("제주", contents: 7, places: 3)
        await waitUntil { data.phase == .loaded }
        XCTAssertEqual(recorded.count, 0)
        XCTAssertEqual(analytics.events, [.search(termLength: 2, kind: "typed")])
    }

    func testFastNextCommitDiscardsCancelledStaleResult() async {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        data.commitSearch("옛검색", kind: nil, target: .content)
        await replies.wait(for: "옛검색")
        data.commitSearch("새검색", kind: nil, target: .place)
        await replies.wait(for: "새검색")
        replies.succeed("새검색", contents: 8, places: 2)
        await waitUntil { recorded.count == 1 }
        replies.succeed("옛검색", contents: 128, places: 521)
        for _ in 0 ..< 20 {
            await Task.yield()
        }
        XCTAssertEqual(recorded.map(\.query), ["새검색"])
        XCTAssertEqual(recorded.map(\.resultCount), [2])
        XCTAssertEqual(data.placePaging.total, 2)
        XCTAssertEqual(analytics.events.count, 2, "실제 확정 두 번, 뒤늦은 응답에는 새 이벤트 없음")
    }

    func testGeneralRefreshCancelsCommitAndDoesNotRecordTypingOrReload() async {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        data.commitSearch("옛검색", kind: nil, target: .content)
        await replies.wait(for: "옛검색")
        data.search("") // 첫 목록·지도 범위 전환과 같은 일반 조회는 intent를 만들지 않는다.
        await replies.wait(for: nil)
        replies.succeed(nil, contents: 128, places: 521)
        await waitUntil { data.phase == .loaded }
        replies.succeed("옛검색", contents: 7, places: 2)
        for _ in 0 ..< 20 {
            await Task.yield()
        }
        XCTAssertEqual(recorded.count, 0)
        XCTAssertEqual(analytics.events.count, 1)
        XCTAssertEqual(data.contentPaging.total, 128)
    }

    func testViewportCancelsPendingCommitAndDoesNotSendSearch() async {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(
            fetchSearch: replies.fetch,
            fetchViewport: { _ in PlaceList(items: [], total: 17, limit: 200, offset: 0) },
            recordUsage: { recorded.append($0) }
        )
        data.commitSearch("옛검색", kind: nil, target: .content)
        await replies.wait(for: "옛검색")
        data.searchInViewport(bbox: "126.9,37.5,127.0,37.6")
        await waitUntil { data.phase == .loaded }
        replies.succeed("옛검색", contents: 128, places: 521)
        for _ in 0 ..< 20 {
            await Task.yield()
        }
        XCTAssertEqual(recorded.count, 0)
        XCTAssertEqual(analytics.events.count, 1)
        XCTAssertEqual(data.placePaging.total, 17)
    }

    func testPaginationFailureDoesNotEmitAnotherCommittedSearch() async {
        let oldFactory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = oldFactory }
        SceneApiClientAPI.requestBuilderFactory = UsageRejectingFactory()
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        data.commitSearch("서울", kind: nil, target: .place)
        await replies.wait(for: "서울")
        replies.succeed("서울", contents: 128, places: 521)
        await waitUntil { data.phase == .loaded }
        data.loadMoreContents()
        await waitUntil { data.moreContentsFailed }
        let received = await data.loadNextPlaces()
        XCTAssertFalse(received)
        XCTAssertTrue(data.morePlacesFailed)
        XCTAssertEqual(recorded.count, 1)
        XCTAssertEqual(analytics.events.count, 1)
    }

    func testEmptyOrOutOfContractQueryDoesNotSendServerUsage() async {
        let replies = SearchReplyQueue()
        var recorded: [UsageEvent] = []
        let data = SceneData(fetchSearch: replies.fetch, recordUsage: { recorded.append($0) })
        for term in [" \n ", String(repeating: "가", count: 101)] {
            let keyword = term.trimmingCharacters(in: .whitespacesAndNewlines)
            let key: String? = keyword.isEmpty ? nil : keyword
            data.commitSearch(term, kind: nil, target: .content)
            await replies.wait(for: key)
            replies.succeed(key, contents: 0, places: 0)
            await waitUntil { data.phase == .loaded }
        }
        XCTAssertEqual(recorded.count, 0)
        XCTAssertEqual(analytics.events, [.search(termLength: 101, kind: "typed")])
    }

    private func waitUntil(_ condition: () -> Bool) async {
        for _ in 0 ..< 200 {
            if condition() {
                return
            }
            await Task.yield()
        }
        XCTFail("가짜 검색 응답이 적용되지 않았다")
    }
}

private final class SearchAnalyticsRecorder: AnalyticsSink {
    var events: [AppEvent] = []
    func log(_ event: AppEvent) {
        events.append(event)
    }

    func setUserProperty(_: String?, for _: String) {}
}

@MainActor
private final class SearchReplyQueue {
    typealias Reply = (ContentList, PlaceList)
    private var pending: [String: CheckedContinuation<Reply, Error>] = [:]

    func fetch(_ keyword: String?) async throws -> Reply {
        try await withCheckedThrowingContinuation { pending[keyword ?? ""] = $0 }
    }

    func wait(for keyword: String?) async {
        for _ in 0 ..< 200 {
            if pending[keyword ?? ""] != nil {
                return
            }
            await Task.yield()
        }
        XCTFail("가짜 검색 요청이 도착하지 않았다")
    }

    func succeed(_ keyword: String?, contents: Int, places: Int) {
        pending.removeValue(forKey: keyword ?? "")?.resume(returning: (
            ContentList(
                items: contents > 0 ? [ContentSummary(id: 1, category: .movie, title: "시험", placeCount: 1)] : [],
                total: contents, limit: 50, offset: 0
            ),
            PlaceList(
                items: places > 0 ? [PlaceSummary(id: 1, name: "시험", latitude: 37.5, longitude: 127)] : [],
                total: places, limit: 200, offset: 0
            )
        ))
    }

    func fail(_ keyword: String?) {
        pending.removeValue(forKey: keyword ?? "")?.resume(throwing: URLError(.timedOut))
    }
}
