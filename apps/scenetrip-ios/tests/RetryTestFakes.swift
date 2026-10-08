import SceneApiClient
@testable import SceneTrip
import XCTest

// 재시도 세션 시험의 가짜들 (MZ2AZ-366) — 안쪽 세션, 시계, 결과 받는 통. `RetryingSessionTests` 와
// `NetFaultTests` 가 같이 쓴다.

/// 안쪽 세션이 줄 답 하나.
enum FakeReply {
    case status(Int, code: String? = nil, headers: [String: String] = [:])
    case error(URLError.Code)
    /// 답하지 않는다 — 취소될 때까지 떠 있다.
    case hang
}

/// 안쪽 세션 — 차례로 정해 둔 답을 준다.
final class RetryFakeSession: URLSessionProtocol, @unchecked Sendable {
    var replies: [FakeReply]
    var requests: [URLRequest] = []
    /// 답 하나를 줄 때마다 시계를 이만큼 돌린다(요청이 걸린 시간).
    var seconds: TimeInterval = 0
    var clock: RetryFakeClock?

    init(_ replies: [FakeReply]) {
        self.replies = replies
    }

    func dataTaskFromProtocol(
        with request: URLRequest,
        completionHandler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void
    ) -> URLSessionDataTaskProtocol {
        requests.append(request)
        let reply = replies.isEmpty ? FakeReply.status(200) : replies.removeFirst()
        return RetryFakeTask(reply: reply, request: request, session: self, handler: completionHandler)
    }
}

final class RetryFakeTask: URLSessionDataTaskProtocol, @unchecked Sendable {
    let taskIdentifier = 1
    let progress = Progress()
    private let reply: FakeReply
    private let request: URLRequest
    private let session: RetryFakeSession
    private let handler: @Sendable (Data?, URLResponse?, Error?) -> Void
    private var done = false

    init(
        reply: FakeReply, request: URLRequest, session: RetryFakeSession,
        handler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void
    ) {
        self.reply = reply
        self.request = request
        self.session = session
        self.handler = handler
    }

    func resume() {
        switch reply {
        case let .status(status, code, headers):
            session.clock?.now.addTimeInterval(session.seconds)
            done = true
            let body = code.map { Data(#"{"code":"\#($0)","message":"x"}"#.utf8) } ?? Data("{}".utf8)
            let response = HTTPURLResponse(
                url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers
            )
            handler(body, response, nil)
        case let .error(code):
            session.clock?.now.addTimeInterval(session.seconds)
            done = true
            handler(nil, nil, URLError(code))
        case .hang:
            break
        }
    }

    func cancel() {
        guard !done else { return }
        done = true
        handler(nil, nil, URLError(.cancelled))
    }
}

/// 시계와 쉬기. `manual` 이면 쉬는 일을 쌓아 두고 시험이 깨운다.
final class RetryFakeClock: @unchecked Sendable {
    var now = Date(timeIntervalSince1970: 0)
    var delays: [TimeInterval] = []
    var manual = false
    var sleeping: [() -> Void] = []
    var cancelledSleeps = 0

    func environment(fault: @escaping (URLRequest) -> NetFault.Injection? = { _ in nil })
        -> RetryingSession.Environment
    {
        RetryingSession.Environment(
            now: { self.now },
            jitter: { 0 },
            schedule: { seconds, work in
                self.delays.append(seconds)
                var live = true
                let run = {
                    guard live else { return }
                    self.now.addTimeInterval(seconds)
                    work()
                }
                if self.manual {
                    self.sleeping.append(run)
                } else {
                    run()
                }
                return {
                    live = false
                    self.cancelledSleeps += 1
                }
            },
            fault: fault
        )
    }
}

final class RetryResults: @unchecked Sendable {
    var statuses: [Int?] = []
    var errors: [URLError.Code?] = []
    var count: Int {
        statuses.count
    }
}
