@testable import SceneTrip
import XCTest

/// 응답 헤더의 남은 양을 적어 두는 장부 (MZ2AZ-366, 계약 「요청 한도」).
final class RateLimitLedgerTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_000_000)

    private func response(_ headers: [String: String], status: Int = 200) -> HTTPURLResponse {
        HTTPURLResponse(
            url: URL(string: "http://localhost:8081/v1/x")!, statusCode: status,
            httpVersion: "HTTP/1.1", headerFields: headers
        )!
    }

    func testReadsTheThreeHeaders() {
        let entry = RateLimitLedger.entry(
            from: response(["RateLimit-Limit": "15", "RateLimit-Remaining": "3", "RateLimit-Reset": "120"]),
            now: now
        )
        XCTAssertEqual(entry, .init(limit: 15, remaining: 3, resetsAt: now.addingTimeInterval(120)))
    }

    /// HTTP/2 는 헤더 이름을 소문자로 준다.
    func testHeaderNamesAreCaseInsensitive() {
        let entry = RateLimitLedger.entry(
            from: response(["ratelimit-limit": "60", "ratelimit-remaining": "59", "ratelimit-reset": "3"]),
            now: now
        )
        XCTAssertEqual(entry?.remaining, 59)
    }

    func testMissingOrBrokenHeadersAreUnknown() {
        XCTAssertNil(RateLimitLedger.entry(from: response([:]), now: now))
        XCTAssertNil(RateLimitLedger.entry(
            from: response(["RateLimit-Limit": "15", "RateLimit-Remaining": "many", "RateLimit-Reset": "1"]),
            now: now
        ))
    }

    /// `Retry-After` 는 초다. 날짜 꼴이나 음수는 모르는 것으로 본다.
    func testRetryAfterIsSeconds() {
        XCTAssertEqual(RateLimitLedger.retryAfter(in: response(["Retry-After": "42"], status: 429)), 42)
        XCTAssertEqual(RateLimitLedger.retryAfter(in: response(["retry-after": " 7 "], status: 429)), 7)
        XCTAssertNil(RateLimitLedger.retryAfter(in: response(["Retry-After": "Wed, 21 Oct 2026 07:28:00 GMT"])))
        XCTAssertNil(RateLimitLedger.retryAfter(in: response(["Retry-After": "-3"])))
        XCTAssertNil(RateLimitLedger.retryAfter(in: response([:])))
    }

    func testBucketsAreKeptApart() {
        XCTAssertEqual(RateLimitLedger.bucket(for: "/v1/guide/chat"), .guide)
        XCTAssertEqual(RateLimitLedger.bucket(for: "/v1/navigation/next-leg"), .navigation)
        XCTAssertEqual(RateLimitLedger.bucket(for: "/v1/guide/plan"), .general)
        XCTAssertEqual(RateLimitLedger.bucket(for: "/v1/places/3"), .general)

        let ledger = RateLimitLedger()
        XCTAssertFalse(ledger.serverKnowsLimits)
        ledger.record(
            path: "/v1/places",
            response: response(["RateLimit-Limit": "120", "RateLimit-Remaining": "118", "RateLimit-Reset": "30"]),
            now: now
        )
        ledger.record(
            path: "/v1/guide/chat",
            response: response(["RateLimit-Limit": "15", "RateLimit-Remaining": "2", "RateLimit-Reset": "900"]),
            now: now
        )
        XCTAssertTrue(ledger.serverKnowsLimits)
        XCTAssertEqual(ledger.entry(for: .general)?.remaining, 118)
        XCTAssertEqual(ledger.entry(for: .guide)?.remaining, 2)
        XCTAssertNil(ledger.entry(for: .navigation))
    }

    /// 멱등 키로 되돌려 준 답에는 헤더가 없다 — 그렇다고 아는 값을 지우지 않는다.
    func testResponseWithoutHeadersKeepsWhatWeKnew() {
        let ledger = RateLimitLedger()
        ledger.record(
            path: "/v1/guide/chat",
            response: response(["RateLimit-Limit": "15", "RateLimit-Remaining": "2", "RateLimit-Reset": "900"]),
            now: now
        )
        ledger.record(path: "/v1/guide/chat", response: response([:]), now: now)
        XCTAssertEqual(ledger.entry(for: .guide)?.remaining, 2)
    }
}
