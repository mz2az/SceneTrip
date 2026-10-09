import SceneApiClient
@testable import SceneTrip
import XCTest

/// 뒷문이 지어내는 한도 응답 — 서버(와 모델)에 닿지 않고 한도 화면을 본다.
final class NetFaultShapeTests: XCTestCase {
    private let url = URL(string: "http://localhost:8081/v1/guide/chat")!

    private final class Body: @unchecked Sendable {
        var data: Data?
    }

    func testShapedStatusIsParsed() throws {
        let spec = "guide/chat:status:429;code=GUIDE_LIMIT_REACHED;retry=20;limit=15;left=0;reset=20:1"
        var shape = try XCTUnwrap(NetFault.Shape("429"))
        shape.code = "GUIDE_LIMIT_REACHED"
        shape.retryAfter = "20"
        shape.limit = "15"
        shape.left = "0"
        shape.reset = "20"
        XCTAssertEqual(NetFault.parse(spec), [.init(match: "guide/chat", injection: .shaped(shape), remaining: 1)])

        var fine = try XCTUnwrap(NetFault.Shape("200"))
        fine.limit = "15"
        fine.left = "3"
        fine.reset = "1200"
        fine.say = "Fake answer"
        XCTAssertEqual(
            NetFault.parse("POST@guide/chat:status:200;limit=15;left=3;reset=1200;say=Fake answer:all"),
            [.init(method: "POST", match: "guide/chat", injection: .shaped(fine), remaining: nil)]
        )
        // 덧붙인 것이 없으면 전과 같은 꼴이다.
        XCTAssertEqual(NetFault.parse("places:status:503:2"), [
            .init(match: "places", injection: .status(503), remaining: 2),
        ])
    }

    /// 오타는 조용히 넘기지 않는다 — 하나라도 못 읽으면 통째로 nil 이고, 앱은 요청을 전부 막는다.
    func testStrictParsingRejectsTheWholeSpec() {
        XCTAssertEqual(NetFault.parseStrictly(""), [])
        XCTAssertEqual(NetFault.parseStrictly("guide/chat:status:429;code=X:all")?.count, 1)
        XCTAssertNil(NetFault.parseStrictly("guide/chat:status:429;cod=X:all"))
        XCTAssertNil(NetFault.parseStrictly("guide/chat:status:429;code:all"))
        XCTAssertNil(NetFault.parseStrictly("guide/chat:status:503:all,:all"))
        XCTAssertNil(NetFault.parseStrictly("guide/chat:status:"))
    }

    func testShapedLimitResponseLooksLikeTheRealOne() throws {
        let rule = try XCTUnwrap(NetFault.parse("guide/chat:status:429;code=GUIDE_LIMIT_REACHED;retry=1800").first)
        guard case let .shaped(shape) = rule.injection else { return XCTFail("모양이 아니다") }
        let (data, response) = NetFault.response(shape, url: url)
        let failure = RouteGuideFailure(ErrorResponse.error(429, data, response, URLError(.badServerResponse)))
        XCTAssertEqual(failure, .limitReached(retryAfter: 1800))
        // `code` 만 적으면 `Retry-After` 는 없다 — 「언제 풀리는지 모름」 을 본다.
        let bare = try XCTUnwrap(NetFault.Shape("429;code=GUIDE_LIMIT_REACHED"))
        let (_, without) = NetFault.response(bare, url: url)
        XCTAssertNil((without as? HTTPURLResponse).flatMap(RateLimitLedger.retryAfter(in:)))
    }

    /// 지어낸 200 은 챗봇 답으로 읽히고, 헤더가 장부에 적히고, **안쪽 세션(서버)에는 한 건도 나가지 않는다.**
    func testShapedSuccessNeverReachesTheServer() throws {
        var rules = NetFault.parse("guide/chat:status:200;limit=15;left=3;reset=1200;say=Fake answer:all")
        let inner = RetryFakeSession([])
        let clock = RetryFakeClock()
        let ledger = RateLimitLedger()
        let session = RetryingSession(
            inner: inner, environment: clock.environment(fault: { NetFault.take(for: $0, from: &rules) }),
            ledger: ledger
        )
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        let results = RetryResults()
        let body = Body()
        for _ in 0 ..< 2 {
            session.dataTaskFromProtocol(with: request) { data, response, _ in
                body.data = data
                results.statuses.append((response as? HTTPURLResponse)?.statusCode)
            }.resume()
        }
        XCTAssertEqual(inner.requests.count, 0)
        XCTAssertEqual(results.statuses, [200, 200])
        let reply = try CodableHelper.decode(GuideChatReply.self, from: XCTUnwrap(body.data)).get()
        XCTAssertEqual(reply.reply, "Fake answer")
        XCTAssertEqual(
            ledger.entry(for: .guide),
            .init(limit: 15, remaining: 3, resetsAt: clock.now.addingTimeInterval(1200))
        )
    }
}
