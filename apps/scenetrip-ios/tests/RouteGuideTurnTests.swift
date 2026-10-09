import CoreLocation
import SceneApiClient
@testable import SceneTrip
import XCTest

/// 가이드에게 보내는 **턴 하나**의 상태 (MZ2AZ-366 PR B, 계획 `app-retry.md` §4·§10).
///
/// 지키는 것은 하나다 — **같은 질문은 같은 멱등 키와 같은 요청으로만 다시 나간다.** 키가 바뀌면 서버가 새
/// 턴으로 보고 모델을 한 번 더 부르고, 요청이 바뀌면 422 다.
@MainActor
final class RouteGuideTurnTests: XCTestCase {
    /// 가짜 창구 — 나간 것을 적고, 정해 둔 답을 준다.
    final class Wire {
        struct Sent {
            let request: GuideChatRequest
            let key: UUID
            let keyed: Bool
        }

        var sent: [Sent] = []
        var replies: [Result<RouteGuide.Answer, Error>] = []
        /// 켜면 답하지 않는다 — 취소될 때까지 떠 있다.
        var hangs = false

        func send(_ request: GuideChatRequest, key: UUID, keyed: Bool) async throws -> RouteGuide.Answer {
            sent.append(Sent(request: request, key: key, keyed: keyed))
            if hangs {
                try await Task.sleep(nanoseconds: 60_000_000_000)
            }
            return try replies.isEmpty ? Self.answer("기본 답") : replies.removeFirst().get()
        }

        static func answer(_ text: String) -> RouteGuide.Answer {
            RouteGuide.Answer(reply: text, tools: [], places: [], seconds: 1, effects: [], ui: [], route: nil)
        }
    }

    private let wire = Wire()
    private var serverKeepsKeys = true
    private var lang = Lang.en
    private var asked = 0
    private let panel = UUID()
    private let seoul = CLLocationCoordinate2D(latitude: 37.5663, longitude: 126.9779)
    private let busan = CLLocationCoordinate2D(latitude: 35.1796, longitude: 129.0756)

    private func makeSession() -> RouteGuideSession {
        let session = RouteGuideSession(environment: .init(
            send: { [wire] in try await wire.send($0, key: $1, keyed: $2) },
            serverKeepsKeys: { [unowned self] in serverKeepsKeys },
            lang: { [unowned self] in lang },
            newKey: UUID.init,
            asked: { [unowned self] in asked += 1 }
        ))
        session.setAttended(true, by: panel)
        return session
    }

    private func failure(_ status: Int, code: String? = nil) -> Result<RouteGuide.Answer, Error> {
        let data = code.map { Data(#"{"code":"\#($0)","message":"x"}"#.utf8) }
        return .failure(ErrorResponse.error(status, data, nil, URLError(.badServerResponse)))
    }

    private func lost(_ code: URLError.Code) -> Result<RouteGuide.Answer, Error> {
        .failure(ErrorResponse.error(-1, nil, nil, URLError(code)))
    }

    /// 서버가 보는 내용 — 본문을 읽어 들인 **값**. 서버의 지문은 받은 바이트가 아니라 읽어 들인 요청을 다시
    /// 적은 것의 해시다(`GuideController.requestHash`). `JSONEncoder` 는 열쇠 순서를 지키지 않아 같은 값도
    /// 부호화할 때마다 바이트가 다를 수 있다 — 그래서 값으로 견준다.
    private func content(_ request: GuideChatRequest) -> NSDictionary? {
        guard let data = try? CodableHelper.encode(request).get() else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? NSDictionary
    }

    /// 창구에 `count` 번째 요청이 나갈 때까지 기다린다.
    private func waitForSend(_ count: Int) async {
        for _ in 0 ..< 1000 where wire.sent.count < count {
            await Task.yield()
        }
        XCTAssertEqual(wire.sent.count, count)
    }

    // MARK: 전송

    func testAnAnswerClearsTheTurn() async {
        let session = makeSession()
        wire.replies = [.success(Wire.answer("덕수궁 옆에 카페가 있어요"))]
        await session.ask("근처 카페", here: seoul, context: nil)

        XCTAssertNil(session.pending)
        XCTAssertNil(session.failure)
        XCTAssertFalse(session.asking)
        XCTAssertFalse(session.canRetry)
        XCTAssertEqual(session.turns.map(\.text), ["근처 카페", "덕수궁 옆에 카페가 있어요"])
        XCTAssertEqual(wire.sent.count, 1)
        XCTAssertTrue(wire.sent[0].keyed)
        XCTAssertEqual(asked, 1)
    }

    func testEachMessageGetsANewKey() async {
        let session = makeSession()
        await session.ask("하나", here: seoul, context: nil)
        await session.ask("둘", here: seoul, context: nil)
        XCTAssertEqual(wire.sent.count, 2)
        XCTAssertNotEqual(wire.sent[0].key, wire.sent[1].key)
    }

    // MARK: 다시 시도

    /// 단추는 **같은 키, 처음 만든 요청 그대로**다 — 질문을 대화에 또 적지 않고, 분석에도 한 번만 센다.
    func testRetryResendsTheSameKeyAndTheSameRequest() async throws {
        let session = makeSession()
        wire.replies = [failure(503, code: "GUIDE_UNAVAILABLE"), .success(Wire.answer("이제 답"))]
        await session.ask("근처 카페", here: seoul, context: nil)

        XCTAssertEqual(session.failure, .unavailable)
        XCTAssertTrue(session.canRetry)
        XCTAssertEqual(session.pending?.stage, .failed(.unavailable))

        await session.retry()
        XCTAssertEqual(wire.sent.count, 2)
        XCTAssertEqual(wire.sent[1].key, wire.sent[0].key)
        XCTAssertEqual(wire.sent[1].request, wire.sent[0].request)
        let first = try XCTUnwrap(content(wire.sent[0].request))
        XCTAssertEqual(content(wire.sent[1].request), first, "내용이 달라지면 서버는 422 다")
        XCTAssertEqual(session.turns.map(\.text), ["근처 카페", "이제 답"])
        XCTAssertNil(session.pending)
        XCTAssertNil(session.failure)
        XCTAssertEqual(asked, 1)
    }

    func testRetryableFailuresKeepTheTurn() async {
        let cases: [(Result<RouteGuide.Answer, Error>, RouteGuideFailure)] = [
            (lost(.timedOut), .timedOut), (lost(.networkConnectionLost), .unreachable),
            (lost(.notConnectedToInternet), .offline), (failure(502), .unavailable),
            (failure(500), .other(status: 500)), (failure(429, code: "RATE_LIMITED"), .rateLimited),
            (failure(409, code: "IDEMPOTENCY_IN_PROGRESS"), .timedOut),
            (.failure(RouteGuideTimedOut()), .timedOut),
        ]
        for (reply, expected) in cases {
            let session = makeSession()
            wire.replies = [reply]
            await session.ask("질문", here: seoul, context: nil)
            XCTAssertEqual(session.failure, expected)
            XCTAssertTrue(session.canRetry, "\(expected)")
            XCTAssertFalse(session.asking)
        }
    }

    /// 다시 해도 같은 실패에는 단추가 없다. (챗봇 한도는 따로다 — `RouteGuideLimitSessionTests`.)
    func testFinalFailuresDropTheTurn() async {
        let cases: [(Result<RouteGuide.Answer, Error>, RouteGuideFailure)] = [
            (failure(400, code: "INVALID_PARAMETER"), .badRequest),
            (failure(401, code: "SIGN_IN_REQUIRED"), .signInRequired),
            (failure(401, code: "ACCESS_TOKEN_INVALID"), .sessionExpired),
            (failure(429, code: "NAVIGATION_LIMIT_REACHED"), .other(status: 429)),
            (failure(404), .other(status: 404)),
        ]
        for (reply, expected) in cases {
            let session = makeSession()
            wire.replies = [reply]
            await session.ask("질문", here: seoul, context: nil)
            XCTAssertEqual(session.failure, expected)
            XCTAssertNil(session.pending, "\(expected)")
            XCTAssertFalse(session.canRetry)
            await session.retry() // 눌릴 일은 없지만, 눌려도 나가지 않는다
        }
        XCTAssertEqual(wire.sent.count, cases.count)
    }

    /// 422 는 앱 버그다 — 그 키로는 영영 422 라, 「다시 시도」 는 **새 키**로 같은 요청을 보낸다.
    func testKeyReuseRetriesWithAFreshKey() async {
        let session = makeSession()
        wire.replies = [failure(422, code: "IDEMPOTENCY_KEY_REUSED")]
        await session.ask("질문", here: seoul, context: nil)
        XCTAssertEqual(session.failure, .keyReused)
        XCTAssertTrue(session.canRetry)

        await session.retry()
        XCTAssertEqual(wire.sent.count, 2)
        XCTAssertNotEqual(wire.sent[1].key, wire.sent[0].key)
        XCTAssertEqual(wire.sent[1].request, wire.sent[0].request)
    }

    /// 앱 언어가 바뀌면 `Accept-Language` 가 달라져 서버 지문이 어긋난다 — 그 턴은 새 키로.
    func testRetryAfterALanguageChangeUsesAFreshKey() async {
        let session = makeSession()
        wire.replies = [failure(503)]
        await session.ask("질문", here: seoul, context: nil)
        lang = .ko
        await session.retry()
        XCTAssertNotEqual(wire.sent[1].key, wire.sent[0].key)
    }

    /// 실패한 뒤 새 질문을 치면 그것은 새 턴이다 — 남아 있던 턴은 버린다.
    func testANewMessageAfterAFailureIsANewTurn() async {
        let session = makeSession()
        wire.replies = [failure(503)]
        await session.ask("첫 질문", here: seoul, context: nil)
        await session.ask("다른 질문", here: busan, context: nil)

        XCTAssertNotEqual(wire.sent[1].key, wire.sent[0].key)
        XCTAssertEqual(wire.sent[1].request.latitude, busan.latitude)
        XCTAssertEqual(wire.sent[1].request.messages.map(\.content), ["첫 질문", "다른 질문"])
        XCTAssertNil(session.pending)
        XCTAssertEqual(asked, 2)
    }

    // MARK: 스위치

    /// 서버가 키를 아는지 확인되지 않았다 — 실패해도 단추가 없다(전과 같다). 키는 실려 나간다.
    func testWithoutProofTheServerKeepsKeysThereIsNoRetryButton() async {
        serverKeepsKeys = false
        let session = makeSession()
        wire.replies = [failure(503), lost(.timedOut)]
        await session.ask("질문", here: seoul, context: nil)
        XCTAssertEqual(session.failure, .unavailable)
        XCTAssertNil(session.pending)
        XCTAssertFalse(session.canRetry)
        XCTAssertFalse(wire.sent[0].keyed)

        await session.retry()
        XCTAssertEqual(wire.sent.count, 1, "다시 나가지 않는다")
    }

    // MARK: 두 번 누름·취소

    func testSendingIsLockedWhileWaiting() async {
        let session = makeSession()
        wire.hangs = true
        let first = Task { await session.ask("첫 질문", here: seoul, context: nil) }
        await waitForSend(1)
        XCTAssertTrue(session.asking)
        XCTAssertEqual(session.pending?.stage, .sending)

        await session.ask("두 번째", here: seoul, context: nil) // 잠겨 있다
        await session.retry()
        XCTAssertEqual(wire.sent.count, 1)
        XCTAssertEqual(session.turns.map(\.text), ["첫 질문"])

        session.clear()
        await first.value
    }

    /// 공통 계층이 다시 보내는 동안의 말 — 「다시 연결하는 중」, 서버가 처리 중이면 그대로 「찾는 중」.
    func testResendNoticesMoveTheStage() async {
        let session = makeSession()
        wire.hangs = true
        let asking = Task { await session.ask("질문", here: seoul, context: nil) }
        await waitForSend(1)
        let key = wire.sent[0].key.uuidString

        session.noteResend(key: UUID().uuidString, reason: .failed) // 남의 요청
        XCTAssertEqual(session.pending?.stage, .sending)
        session.noteResend(key: key, reason: .failed)
        XCTAssertEqual(session.pending?.stage, .reconnecting)
        session.noteResend(key: key, reason: .inProgress)
        XCTAssertEqual(session.pending?.stage, .awaitingServer)
        session.noteResend(key: key, reason: .rateLimited)
        XCTAssertEqual(session.pending?.stage, .waitingForLimit, "한도 대기는 「다시 연결」 이 아니다")
        XCTAssertTrue(session.asking)

        session.clear()
        await asking.value
    }

    /// 가이드 창을 닫으면 **기다리던 다시 보내기·되묻기를 끊는다.** 턴은 남는다 — 다시 열어 같은 키로 잇는다.
    func testClosingThePanelCutsAWaitingResend() async {
        let session = makeSession()
        wire.hangs = true
        let asking = Task { await session.ask("질문", here: seoul, context: nil) }
        await waitForSend(1)
        let key = wire.sent[0].key
        session.noteResend(key: key.uuidString, reason: .inProgress)

        session.setAttended(false, by: panel)
        await asking.value
        XCTAssertEqual(session.failure, .interrupted)
        XCTAssertTrue(session.canRetry)

        wire.hangs = false
        wire.replies = [.success(Wire.answer("저장돼 있던 답"))]
        session.setAttended(true, by: panel)
        await session.retry()
        XCTAssertEqual(wire.sent[1].key, key)
        XCTAssertEqual(session.turns.last?.text, "저장돼 있던 답")
    }

    /// 편집 화면과 길찾기 화면이 같은 대화를 쓴다 — **한쪽 창이 사라져도 다른 쪽이 떠 있으면 열린 것이다.**
    func testOnePanelLeavingDoesNotCloseTheOther() async {
        let session = makeSession() // 편집 화면의 창(`panel`)이 떠 있다
        let navPanel = UUID()
        session.setAttended(true, by: navPanel)
        wire.hangs = true
        let asking = Task { await session.ask("질문", here: seoul, context: nil) }
        await waitForSend(1)
        session.noteResend(key: wire.sent[0].key.uuidString, reason: .inProgress)

        session.setAttended(false, by: panel) // 편집 화면의 창이 사라졌다 — 길찾기의 창은 떠 있다
        session.setAttended(false, by: panel) // 두 번 알려도 같다
        for _ in 0 ..< 20 {
            await Task.yield()
        }
        XCTAssertTrue(session.asking, "끊기지 않는다")

        session.setAttended(false, by: navPanel) // 마지막 창도 사라졌다
        await asking.value
        XCTAssertEqual(session.failure, .interrupted)
        XCTAssertTrue(session.canRetry)
    }

    /// 처음 보낸 요청은 창을 닫아도 끊지 않는다 — 묻고 지도를 보려고 창을 내린다.
    func testClosingThePanelLeavesTheFirstRequestAlone() async {
        let session = makeSession()
        wire.hangs = true
        let asking = Task { await session.ask("질문", here: seoul, context: nil) }
        await waitForSend(1)
        session.setAttended(false, by: panel)
        for _ in 0 ..< 20 {
            await Task.yield()
        }
        XCTAssertTrue(session.asking)
        XCTAssertNil(session.failure)

        // 창이 닫힌 채로 다시 보내기가 시작되면 그때 끊는다.
        session.noteResend(key: wire.sent[0].key.uuidString, reason: .failed)
        await asking.value
        XCTAssertEqual(session.failure, .interrupted)
        XCTAssertTrue(session.canRetry)
    }

    /// 코스가 바뀌면 보내던 턴도 버린다 — 늦게 온 답을 남의 대화에 적지 않는다.
    func testBindingAnotherCourseDropsTheTurn() async {
        let session = makeSession()
        session.bind(to: "course-1")
        wire.hangs = true
        let asking = Task { await session.ask("질문", here: seoul, context: nil) }
        await waitForSend(1)

        session.bind(to: "course-2")
        await asking.value
        XCTAssertNil(session.pending)
        XCTAssertNil(session.failure)
        XCTAssertTrue(session.isEmpty)
        XCTAssertFalse(session.asking)
    }

    // MARK: 전이 — 순수 함수

    func testTurnTransitions() {
        let request = RouteGuide.request(history: [.init(role: .user, text: "질문")], here: seoul, sessionId: UUID())
        let key = UUID()
        let fresh = UUID()
        let turn = PendingTurn(key: key, request: request, lang: .en, keyed: true)
        XCTAssertEqual(turn.stage, .sending)
        XCTAssertTrue(turn.stage.isBusy)
        XCTAssertFalse(turn.stage.isResending)

        XCTAssertEqual(turn.resending(.failed).stage, .reconnecting)
        XCTAssertEqual(turn.resending(.inProgress).stage, .awaitingServer)
        XCTAssertEqual(turn.resending(.failed).resending(.inProgress).stage, .awaitingServer)
        XCTAssertTrue(turn.resending(.inProgress).stage.isResending)
        XCTAssertEqual(turn.resending(.rateLimited).stage, .waitingForLimit)
        XCTAssertTrue(turn.resending(.rateLimited).stage.isResending)
        XCTAssertTrue(turn.resending(.rateLimited).stage.isBusy)

        // 실패 — 남는 것과 버려지는 것.
        XCTAssertEqual(turn.failing(.unavailable)?.stage, .failed(.unavailable))
        XCTAssertEqual(turn.failing(.unavailable)?.key, key)
        XCTAssertNil(turn.failing(.badRequest))
        XCTAssertNil(turn.failing(.other(status: 429)))
        let unkeyed = PendingTurn(key: key, request: request, lang: .en, keyed: false)
        XCTAssertNil(unkeyed.failing(.unavailable), "서버가 키를 모르면 남기지 않는다")

        // 다시 시도 — 같은 키, 같은 요청.
        let failed = turn.failing(.timedOut)
        XCTAssertFalse(failed?.stage.isBusy ?? true)
        XCTAssertEqual(failed?.resending(.failed).stage, .failed(.timedOut), "끝난 턴은 알림에 움직이지 않는다")
        let again = failed?.retried(lang: .en, newKey: { fresh })
        XCTAssertEqual(again?.key, key)
        XCTAssertEqual(again?.request, request)
        XCTAssertEqual(again?.stage, .sending)
        // 새 키가 필요한 둘.
        XCTAssertEqual(turn.failing(.keyReused)?.retried(lang: .en, newKey: { fresh })?.key, fresh)
        XCTAssertEqual(failed?.retried(lang: .ko, newKey: { fresh })?.key, fresh)
        XCTAssertEqual(failed?.retried(lang: .ko, newKey: { fresh })?.lang, .ko)
        // 보내는 중인 턴은 다시 시도할 수 없다.
        XCTAssertNil(turn.retried(lang: .en, newKey: { fresh }))
    }

    /// 요청은 **만들 때의 것**이다 — 그 뒤에 자리를 옮기고 화면 상태가 바뀌어도, 다시 보내는 내용은 그대로다.
    func testTheStoredRequestIsFrozenAtSendTime() async throws {
        let session = makeSession()
        wire.replies = [lost(.timedOut)]
        let context = RouteGuide.Context(
            stops: [.init(number: 1, name: "덕수궁", kind: "궁궐", latitude: 37.5658, longitude: 126.9752)],
            picked: nil,
            trip: .init(
                phase: .guiding, course: "서울", day: 1, days: 2, targetNumber: 1, targetMeters: 412,
                walkedKilometers: 1.25
            )
        )
        await session.ask("다음은 어디야?", here: seoul, context: context)
        let first = try XCTUnwrap(content(wire.sent[0].request))

        await session.retry() // 그 사이 걸어서 남은 거리가 달라졌어도 — 다시 읽지 않는다
        XCTAssertEqual(content(wire.sent[1].request), first)
        XCTAssertEqual(wire.sent[1].request.context?.trip?.targetMeters, 412)
        XCTAssertEqual(wire.sent[1].request.latitude, seoul.latitude)
    }
}
