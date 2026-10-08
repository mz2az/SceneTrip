import SceneApiClient
@testable import SceneTrip
import XCTest

/// 실패 넣기 뒷문(`-netFault`)의 글자 읽기와, 넣은 실패가 재시도 세션에서 어떻게 도는가 (MZ2AZ-366).
final class NetFaultTests: XCTestCase {
    private let clock = RetryFakeClock()
    private let results = RetryResults()

    private func request(_ method: String, _ path: String, body: String? = nil) -> URLRequest {
        var request = URLRequest(url: URL(string: "http://localhost:8081/v1\(path)")!)
        request.httpMethod = method
        request.httpBody = body.map { Data($0.utf8) }
        return request
    }

    private func run(
        _ inner: RetryFakeSession, _ request: URLRequest, fault: @escaping (URLRequest) -> NetFault.Injection?
    ) {
        inner.clock = clock
        let session = RetryingSession(
            inner: inner, environment: clock.environment(fault: fault), ledger: RateLimitLedger()
        )
        let results = results
        session.dataTaskFromProtocol(with: request) { _, response, error in
            results.statuses.append((response as? HTTPURLResponse)?.statusCode)
            results.errors.append((error as? URLError)?.code)
        }.resume()
    }

    func testFaultSpecIsParsed() {
        XCTAssertEqual(NetFault.parse("contents:status:503:2, places:lost,me:down:all,x:offline:3"), [
            .init(match: "contents", injection: .status(503), remaining: 2),
            .init(match: "places", injection: .lost, remaining: 1),
            .init(match: "me", injection: .down, remaining: nil),
            .init(match: "x", injection: .offline, remaining: 3),
        ])
        XCTAssertEqual(NetFault.parse(""), [])
        XCTAssertEqual(NetFault.parse("places:boom,status:503,places:status:abc"), [])
    }

    /// `메서드@경로` — 같은 경로의 조회가 쓰기 몫의 횟수를 먼저 쓰지 않게.
    func testFaultCanBeLimitedToOneMethod() {
        var rules = NetFault.parse("delete@reviews/me:lost")
        XCTAssertEqual(rules, [.init(method: "DELETE", match: "reviews/me", injection: .lost, remaining: 1)])
        XCTAssertNil(NetFault.take(for: request("GET", "/places/1/reviews/me"), from: &rules))
        XCTAssertEqual(NetFault.take(for: request("DELETE", "/places/1/reviews/me"), from: &rules), .lost)
        XCTAssertNil(NetFault.take(for: request("DELETE", "/places/1/reviews/me"), from: &rules))
    }

    func testFaultIsTakenOnlyAsManyTimesAsAsked() {
        var rules = NetFault.parse("places:status:503:2")
        let places = request("GET", "/places")
        XCTAssertNil(NetFault.take(for: request("GET", "/contents"), from: &rules))
        XCTAssertEqual(NetFault.take(for: places, from: &rules), .status(503))
        XCTAssertEqual(NetFault.take(for: places, from: &rules), .status(503))
        XCTAssertNil(NetFault.take(for: places, from: &rules))
    }

    /// 지어낸 503 은 서버에 닿지 않는다 — 두 번 지어내고 세 번째에 진짜로 나간다.
    func testInjectedStatusNeverReachesTheServer() {
        var rules = NetFault.parse("places:status:503:2")
        let inner = RetryFakeSession([.status(200)])
        run(inner, request("GET", "/places"), fault: { NetFault.take(for: $0, from: &rules) })
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(clock.delays, [1, 2])
        XCTAssertEqual(results.statuses, [200])
    }

    /// `lost` 는 보내고 응답을 버린다 — POST 는 그래도 한 번만 나간다.
    func testInjectedLostResponseDoesNotResendAPost() {
        var rules = NetFault.parse("courses:lost")
        let inner = RetryFakeSession([.status(201), .status(201)])
        run(inner, request("POST", "/courses", body: "{}"), fault: { NetFault.take(for: $0, from: &rules) })
        XCTAssertEqual(inner.requests.count, 1)
        XCTAssertEqual(results.errors, [.timedOut])
    }

    func testInjectedRateLimitLooksLikeTheRealOne() {
        let (data, response) = NetFault.response(status: 429, url: URL(string: "http://x/v1/places"))
        XCTAssertEqual(AuthRules.apiCode(from: data), "RATE_LIMITED")
        XCTAssertEqual((response as? HTTPURLResponse).flatMap(RateLimitLedger.retryAfter(in:)), 3)
    }
}
