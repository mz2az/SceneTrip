import CoreLocation
import SceneApiClient
@testable import SceneTrip
import XCTest

/// 챗봇 턴이 **생성 클라이언트를 지나 실제로 어떤 `URLRequest` 가 되는가** (MZ2AZ-366 PR B).
@MainActor
final class GuideChatRequestBuildTests: XCTestCase {
    private let seoul = CLLocationCoordinate2D(latitude: 37.5663, longitude: 126.9779)

    /// 「전송」 과 「다시 시도」 가 **생성 클라이언트로 실제로 만드는 `URLRequest`** 를 본다 — 같은 턴이면 멱등 키와
    /// 언어 헤더가 같고 본문의 **값**이 같아야 한다(서버 지문은 본문을 읽어 들인 값 + `Accept-Language`).
    ///
    /// 이 시험이 잡으려는 것: 요청을 다시 부호화할 때 값이 달라지는 일. 지금은 본문이 전부 정해진 모양의
    /// 칸이라 열쇠 순서만 달라질 수 있고 서버 지문에는 들지 않는다. **계약에 자유 형식 칸(사전·`AnyCodable`)이
    /// 생기거나 부호화가 값에 손대기 시작하면(날짜·실수 자릿수) 「다시 시도」 가 조용히 422 가 된다** — 그때
    /// 여기서 걸린다.
    func testSendAndRetryBuildTheSameRequest() throws {
        let context = RouteGuide.Context(
            stops: [.init(number: 1, name: "덕수궁", kind: "궁궐", latitude: 37.5658, longitude: 126.9752)],
            picked: .init(number: 1, name: "덕수궁", kind: nil, latitude: 37.5658, longitude: 126.9752),
            trip: .init(
                phase: .guiding, course: "서울", day: 1, days: 2, targetNumber: 1, targetMeters: 412,
                walkedKilometers: 1.25
            )
        )
        let turn = PendingTurn(
            key: UUID(),
            request: RouteGuide.request(
                history: [.init(role: .user, text: "다음은 어디야?"), .init(role: .assistant, text: "덕수궁이에요")],
                here: seoul, sessionId: UUID(), context: context
            ),
            lang: .en, keyed: true
        )
        let failed = try XCTUnwrap(turn.failing(.unavailable))
        let again = try XCTUnwrap(failed.retried(lang: .en, newKey: UUID.init))

        let first = try urlRequest(for: turn)
        let second = try urlRequest(for: again)

        XCTAssertEqual(first.value(forHTTPHeaderField: "Idempotency-Key"), turn.key.uuidString)
        XCTAssertEqual(second.value(forHTTPHeaderField: "Idempotency-Key"), turn.key.uuidString)
        XCTAssertEqual(first.value(forHTTPHeaderField: "Accept-Language"), "en")
        XCTAssertEqual(second.value(forHTTPHeaderField: "Accept-Language"), "en")
        XCTAssertEqual(first.httpMethod, "POST")
        XCTAssertEqual(second.url, first.url)
        XCTAssertEqual(first.url?.path.hasSuffix("/guide/chat"), true)

        let sent = try json(first)
        XCTAssertEqual(try json(second), sent, "본문의 값이 달라지면 서버는 422 다")
        XCTAssertEqual(sent["latitude"] as? Double, seoul.latitude)
        XCTAssertEqual((sent["messages"] as? [Any])?.count, 2)
        XCTAssertNotNil(sent["context"])
    }

    /// 생성 클라이언트가 이 턴으로 만드는 요청. 빌더가 쓸 세션만 가짜로 바꿔, 나가려던 `URLRequest` 를 받는다.
    private func urlRequest(for turn: PendingTurn) throws -> URLRequest {
        let factory = SceneApiClientAPI.requestBuilderFactory
        let headers = SceneApiClientAPI.customHeaders
        defer {
            SceneApiClientAPI.requestBuilderFactory = factory
            SceneApiClientAPI.customHeaders = headers
        }
        let session = RetryFakeSession([.hang])
        CapturedSession.current = session
        SceneApiClientAPI.requestBuilderFactory = CapturingFactory()
        SceneApiClientAPI.customHeaders["Accept-Language"] = turn.lang.rawValue // 앱이 언어를 싣는 자리(`AppLocale`)

        let task = RouteGuide.chatBuilder(turn.request, key: turn.key).execute { _ in }
        task.cancel()
        return try XCTUnwrap(session.requests.first)
    }

    private func json(_ request: URLRequest) throws -> NSDictionary {
        let body = try XCTUnwrap(request.httpBody)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? NSDictionary)
    }
}

/// 생성 클라이언트의 빌더에 가짜 세션을 물린다 — 요청을 만들기만 하고 내보내지 않는다.
private enum CapturedSession {
    static var current: URLSessionProtocol = RetryFakeSession([])
}

private final class CapturingFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        URLSessionRequestBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        CapturingBuilder<T>.self
    }
}

private final class CapturingBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        CapturedSession.current
    }
}
