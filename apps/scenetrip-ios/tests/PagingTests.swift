import SceneApiClient
@testable import SceneTrip
import XCTest

/// 목록을 이어 받는 규칙 (MZ2AZ-372). 틀어지면 목록이 중간에서 끊기거나(작품 100편), 같은 줄이 두 번 나오거나,
/// 다 받고도 끝없이 묻는다.
final class PagingTests: XCTestCase {
    /// 첫 쪽을 받기 전에는 받을 것이 있다고 본다.
    func testStartsWithMore() {
        XCTAssertTrue(Paging().hasMore)
        XCTAssertEqual(Paging().offset, 0)
    }

    /// 작품 128편을 50편씩 — 세 번에 끝난다.
    func testWalksToTheEnd() {
        var paging = Paging().advanced(received: 50, total: 128)
        XCTAssertTrue(paging.hasMore)
        XCTAssertEqual(paging.offset, 50)
        paging = paging.advanced(received: 50, total: 128)
        XCTAssertTrue(paging.hasMore)
        paging = paging.advanced(received: 28, total: 128)
        XCTAssertFalse(paging.hasMore)
        XCTAssertEqual(paging.offset, 128)
    }

    /// 한 쪽에 다 들어오면(작은 데이터 — 11편) 더 묻지 않는다.
    func testSmallCatalogEndsOnTheFirstPage() {
        XCTAssertFalse(Paging().advanced(received: 11, total: 11).hasMore)
        XCTAssertFalse(Paging().advanced(received: 0, total: 0).hasMore)
    }

    /// 서버의 총수가 실제보다 커도 빈 쪽을 받으면 멈춘다 — 안 그러면 목록 끝에서 끝없이 묻는다.
    func testEmptyPageStopsEvenIfTotalSaysMore() {
        let paging = Paging().advanced(received: 50, total: 128).advanced(received: 0, total: 128)
        XCTAssertFalse(paging.hasMore)
    }

    /// 화면의 수는 서버의 총수다 — 받아 둔 줄 수가 아니다.
    func testShownTotalIsTheServerTotal() {
        let paging = Paging().advanced(received: 50, total: 128)
        XCTAssertEqual(paging.shownTotal(loaded: 50), 128)
        // 받는 사이 늘어 쌓인 것이 더 많으면 쌓인 수.
        XCTAssertEqual(paging.shownTotal(loaded: 130), 130)
        XCTAssertEqual(Paging().shownTotal(loaded: 0), 0)
    }

    /// 받는 사이 순위가 밀려 같은 것이 또 오면 한 번만 쌓는다. 순서는 받은 그대로.
    func testMergeSkipsWhatIsAlreadyThere() {
        XCTAssertEqual(Paging.merged([1, 2, 3], with: [3, 4, 5], by: { $0 }), [1, 2, 3, 4, 5])
        XCTAssertEqual(Paging.merged([Int](), with: [7, 7, 8], by: { $0 }), [7, 8])
        XCTAssertEqual(Paging.merged([1, 2], with: [], by: { $0 }), [1, 2])
    }

    /// 다음 요청의 `offset` 은 **서버가 돌려준 줄 수**다 — 겹쳐서 버린 줄도 센다.
    func testOffsetCountsReceivedRowsNotKeptRows() {
        let kept = Paging.merged([1, 2, 3], with: [3, 4], by: { $0 })
        let paging = Paging().advanced(received: 3, total: 10).advanced(received: 2, total: 10)
        XCTAssertEqual(kept.count, 4)
        XCTAssertEqual(paging.offset, 5)
    }

    // MARK: 취소는 실패가 아니다

    /// 칩을 끄면 받던 요청이 끊긴다 — 그 끊김을 「못 받았다」 로 적으면 목록 끝에 「다시 시도」 가 남는다.
    func testCancellationIsRecognised() {
        XCTAssertTrue(PageFetchRules.isCancellation(CancellationError()))
        XCTAssertTrue(PageFetchRules.isCancellation(URLError(.cancelled)))
        // 생성 클라이언트는 `URLError` 를 제 오류로 한 겹 싼다.
        XCTAssertTrue(PageFetchRules.isCancellation(ErrorResponse.error(-1, nil, nil, URLError(.cancelled))))
    }

    func testRealFailuresAreNotCancellation() {
        XCTAssertFalse(PageFetchRules.isCancellation(URLError(.timedOut)))
        XCTAssertFalse(PageFetchRules.isCancellation(URLError(.notConnectedToInternet)))
        XCTAssertFalse(PageFetchRules.isCancellation(ErrorResponse.error(503, nil, nil, URLError(.badServerResponse))))
    }

    func testAttemptSortsTheThreeEndings() async {
        let got = await PageFetch.attempt { 7 }
        guard case .page(7) = got else { return XCTFail("받았는데 받았다고 하지 않는다") }

        let cut: PageFetch<Int> = await PageFetch.attempt { throw URLError(.cancelled) }
        guard case .cancelled = cut else { return XCTFail("취소를 실패로 적었다") }

        let broken: PageFetch<Int> = await PageFetch.attempt { throw URLError(.timedOut) }
        guard case .failed = broken else { return XCTFail("실패를 실패로 적지 않았다") }
    }

    /// 요청은 끝까지 갔어도 그 사이 그만두라고 했으면 붙이지 않는다.
    func testAttemptInsideACancelledTaskIsCancelled() async {
        let task = Task { () -> PageFetch<Int> in
            withUnsafeCurrentTask { $0?.cancel() }
            return await PageFetch.attempt { 7 }
        }
        guard case .cancelled = await task.value else { return XCTFail("취소된 일의 응답을 붙였다") }
    }
}
