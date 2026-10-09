import CoreLocation
import SceneApiClient
@testable import SceneTrip
import XCTest

/// 챗봇 한도 안내와 남은 양의 **규칙** (MZ2AZ-366 C, 계획 `app-retry.md` §5-2·§5-4·§11).
///
/// 서버는 1 시간 창과 하루 창을 같은 `code` 로 알린다 — 앱은 「언제 풀리나」 만으로 말을 고른다. 한도 숫자는
/// 앱에 없다(헤더로만 온다). 시험은 한국어로 돈다.
final class RouteGuideLimitRulesTests: XCTestCase {
    /// 연속 시계의 지금(초) — 벽시계가 아니다.
    private let now: TimeInterval = 5000

    private func entry(limit: Int, left: Int, reset: TimeInterval) -> GuideLimit.Quota {
        .init(limit: limit, remaining: left, refillsAt: now + reset)
    }

    // MARK: 429 를 어느 안내로 가르나

    func testWindowIsToldByHowSoonItLifts() {
        XCTAssertEqual(GuideLimit.window(seconds: 1), .short)
        XCTAssertEqual(GuideLimit.window(seconds: 60), .short)
        XCTAssertEqual(GuideLimit.window(seconds: 3600), .short, "1 시간 창은 길어야 3,600초다")
        XCTAssertEqual(GuideLimit.window(seconds: 3601), .day)
        XCTAssertEqual(GuideLimit.window(seconds: 86400), .day)
        XCTAssertEqual(GuideLimit.window(seconds: nil), .unknown, "서버가 말하지 않았으면 「내일」 이라 지어내지 않는다")
    }

    func testMinutesRoundUpAndNeverSayZero() {
        XCTAssertEqual(GuideLimit.minutes(left: 1), 1)
        XCTAssertEqual(GuideLimit.minutes(left: 60), 1)
        XCTAssertEqual(GuideLimit.minutes(left: 61), 2)
        XCTAssertEqual(GuideLimit.minutes(left: 3600), 60)
        XCTAssertEqual(GuideLimit.minutes(left: -5), 1)
    }

    // MARK: 풀리는 시각

    func testShortBlockLocksSendingUntilItLifts() {
        let block = GuideLimit.Block(retryAfter: 1200, now: now)
        XCTAssertEqual(block.window, .short)
        XCTAssertEqual(block.until, now + 1200)
        XCTAssertTrue(block.stopsSending(at: now))
        XCTAssertTrue(block.stopsSending(at: now + 1199))
        XCTAssertFalse(block.stopsSending(at: now + 1200))
        XCTAssertTrue(block.isOver(at: now + 1200))
    }

    /// `Retry-After` 가 없으면 언제 풀리는지 모른다 — 잠그지 않고(풀 길이 없다), 풀렸다고도 하지 않는다.
    func testUnknownRetryAfterDoesNotLock() {
        let block = GuideLimit.Block(retryAfter: nil, now: now)
        XCTAssertEqual(block.window, .unknown)
        XCTAssertNil(block.until)
        XCTAssertFalse(block.stopsSending(at: now))
        XCTAssertFalse(block.isOver(at: now + 100_000))
    }

    func testNoticeCountsDownAndTheDayOneSaysTomorrow() {
        let short = GuideLimit.Block(retryAfter: 1200, now: now)
        XCTAssertEqual(short.message(at: now), "가이드에게 잠시 많이 물어봤어요. 20분 뒤에 다시 물어볼 수 있어요")
        XCTAssertEqual(
            short.message(at: now + 1150), "가이드에게 잠시 많이 물어봤어요. 1분 뒤에 다시 물어볼 수 있어요"
        )
        let day = "오늘은 가이드에게 물어볼 수 있는 횟수를 다 썼어요. 내일 다시 물어봐 주세요"
        XCTAssertEqual(GuideLimit.Block(retryAfter: 30000, now: now).message(at: now), day)
        // 얼마나 기다릴지 모르면 「오늘」 도 「내일」 도 말하지 않는다.
        let unknown = GuideLimit.Block(retryAfter: nil, now: now).message(at: now)
        XCTAssertEqual(unknown, "지금은 가이드에게 물어볼 수 없어요. 잠시 뒤 다시 시도해 주세요")
        XCTAssertFalse(unknown.contains("내일") || unknown.contains("오늘"))
        // 하루 창이 자정 30 분 전에 걸렸다 — 「30분 뒤」 는 참이다.
        XCTAssertTrue(GuideLimit.Block(retryAfter: 1800, now: now).message(at: now).contains("30분 뒤"))
    }

    /// 안내 문구가 무료를 약속하지 않는다 — 마법사도 지금은 모델을 부른다(계획 §7-6).
    func testNoticeAndFailureMessageAgree() {
        XCTAssertEqual(
            RouteGuideFailure.limitReached(retryAfter: 600).message,
            "가이드에게 잠시 많이 물어봤어요. 10분 뒤에 다시 물어볼 수 있어요"
        )
        XCTAssertTrue(RouteGuideFailure.limitReached(retryAfter: 30000).message.contains("내일"))
        XCTAssertFalse(RouteGuideFailure.limitReached(retryAfter: nil).message.contains("내일"))
    }

    // MARK: 남은 양

    func testRemainingIsHiddenWithoutHeadersOrWhenPlenty() {
        XCTAssertEqual(GuideLimit.remaining(nil, now: now), .hidden, "옛 서버 — 헤더가 없다")
        XCTAssertEqual(GuideLimit.remaining(entry(limit: 15, left: 14, reset: 1200), now: now), .hidden)
        XCTAssertEqual(GuideLimit.remaining(entry(limit: 15, left: 4, reset: 1200), now: now), .hidden)
        XCTAssertEqual(GuideLimit.remaining(entry(limit: 0, left: 0, reset: 1200), now: now), .hidden)
    }

    /// 한도 크기의 20% 이하부터 — 15 면 3, 100 이면 20. 둘 다 서버가 준 수다.
    func testRemainingShowsWhenLow() {
        XCTAssertEqual(
            GuideLimit.remaining(entry(limit: 15, left: 3, reset: 1200), now: now),
            .few(count: 3, refillMinutes: 20)
        )
        XCTAssertEqual(
            GuideLimit.remaining(entry(limit: 100, left: 20, reset: 40000), now: now),
            .few(count: 20, refillMinutes: nil)
        )
        XCTAssertEqual(GuideLimit.remaining(entry(limit: 100, left: 21, reset: 40000), now: now), .hidden)
        XCTAssertEqual(
            GuideLimit.remaining(entry(limit: 15, left: 1, reset: 59), now: now), .few(count: 1, refillMinutes: 1)
        )
    }

    func testZeroSaysSoUntilTheWindowRefills() {
        XCTAssertEqual(
            GuideLimit.remaining(entry(limit: 15, left: 0, reset: 600), now: now), .none(refillMinutes: 10)
        )
        XCTAssertEqual(
            GuideLimit.remaining(entry(limit: 100, left: 0, reset: 50000), now: now), .none(refillMinutes: nil)
        )
    }

    /// 다시 찬 창의 수는 낡았다 — 「0번」 이나 「2번 남음」 을 붙들고 있지 않는다.
    func testStaleEntryIsHidden() {
        let low = entry(limit: 15, left: 2, reset: 600)
        XCTAssertNotEqual(GuideLimit.remaining(low, now: now + 599), .hidden)
        XCTAssertEqual(GuideLimit.remaining(low, now: now + 600), .hidden)
        XCTAssertEqual(GuideLimit.remaining(entry(limit: 15, left: 0, reset: 600), now: now + 601), .hidden)
    }

    /// 장부의 「다시 차는 때」 는 벽시계다 — 읽는 순간에 연속 시계로 옮기고, 그 뒤로는 벽시계를 보지 않는다.
    func testQuotaIsMovedOntoTheContinuousClock() {
        let wall = Date(timeIntervalSince1970: 1_000_000)
        let ledger = RateLimitLedger.Entry(limit: 15, remaining: 3, resetsAt: wall.addingTimeInterval(1200))
        let quota = GuideLimit.Quota(ledger, wall: wall, now: now)
        XCTAssertEqual(quota, .init(limit: 15, remaining: 3, refillsAt: now + 1200))
    }

    func testRemainingWording() {
        XCTAssertNil(GuideLimit.Remaining.hidden.text)
        XCTAssertEqual(
            GuideLimit.Remaining.few(count: 3, refillMinutes: 20).text, "3번 더 물어볼 수 있어요 · 20분 뒤 다시 채워져요"
        )
        XCTAssertEqual(GuideLimit.Remaining.few(count: 1, refillMinutes: nil).text, "오늘 1번 더 물어볼 수 있어요")
        XCTAssertEqual(GuideLimit.Remaining.none(refillMinutes: 10).text, "지금은 더 물어볼 수 없어요 · 10분 뒤 다시 채워져요")
        XCTAssertEqual(GuideLimit.Remaining.none(refillMinutes: nil).text, "오늘은 더 물어볼 수 없어요")
    }

    // MARK: 응답 → 실패

    func testLimitResponseCarriesRetryAfter() {
        func error(_ headers: [String: String], code: String = "GUIDE_LIMIT_REACHED") -> Error {
            let url = URL(string: "http://localhost:8081/v1/guide/chat")!
            let response = HTTPURLResponse(
                url: url, statusCode: 429, httpVersion: "HTTP/1.1", headerFields: headers
            )
            let data = Data(#"{"code":"\#(code)","message":"x"}"#.utf8)
            return ErrorResponse.error(429, data, response, URLError(.badServerResponse))
        }
        XCTAssertEqual(RouteGuideFailure(error(["Retry-After": "1800"])), .limitReached(retryAfter: 1800))
        XCTAssertEqual(RouteGuideFailure(error(["retry-after": "40000"])), .limitReached(retryAfter: 40000))
        XCTAssertEqual(RouteGuideFailure(error([:])), .limitReached(retryAfter: nil))
        XCTAssertEqual(RouteGuideFailure(error(["Retry-After": "soon"])), .limitReached(retryAfter: nil))
        // 분당 한도와 길찾기 한도는 이 안내가 아니다.
        XCTAssertEqual(RouteGuideFailure(error(["Retry-After": "3"], code: "RATE_LIMITED")), .rateLimited)
        XCTAssertEqual(
            RouteGuideFailure(error(["Retry-After": "3"], code: "NAVIGATION_LIMIT_REACHED")), .other(status: 429)
        )
        XCTAssertTrue(RouteGuideFailure.limitReached(retryAfter: 5).isLimit)
        XCTAssertFalse(RouteGuideFailure.rateLimited.isLimit)
        XCTAssertEqual(RouteGuideFailure.limitReached(retryAfter: 5).retry, .sameKey)
    }

    /// 계정이 바뀌면 챗봇의 남은 양은 잊는다 — 「서버가 한도를 안다」 는 그대로다(멱등 키 스위치).
    func testLedgerForgetsOneBucketButStillKnowsTheServer() throws {
        let ledger = RateLimitLedger()
        let response = try XCTUnwrap(try HTTPURLResponse(
            url: XCTUnwrap(URL(string: "http://localhost:8081/v1/guide/chat")), statusCode: 200, httpVersion: "HTTP/1.1",
            headerFields: ["RateLimit-Limit": "15", "RateLimit-Remaining": "3", "RateLimit-Reset": "120"]
        ))
        ledger.record(path: "/v1/guide/chat", response: response, now: Date(timeIntervalSince1970: 1_000_000))
        XCTAssertNotNil(ledger.entry(for: .guide))
        ledger.forget(.guide)
        XCTAssertNil(ledger.entry(for: .guide))
        XCTAssertTrue(ledger.serverKnowsLimits)
    }
}

/// 한도에 걸린 **대화**가 어떻게 도는가 — 잠금, 풀림, 같은 키로 다시 보내기, 남은 양.
@MainActor
final class RouteGuideLimitSessionTests: XCTestCase {
    private let wire = RouteGuideTurnTests.Wire()
    /// 연속 시계(초)와 벽시계. 따로 돈다 — 벽시계를 돌려도 잠금은 그대로여야 한다.
    private var now: TimeInterval = 9000
    private var wall = Date(timeIntervalSince1970: 2_000_000)
    private var quota: RateLimitLedger.Entry?
    private var serverKeepsKeys = true
    /// 쉬어 달라고 한 초들. `wake` 를 켜면 쉬던 것이 깬다.
    private var sleeps: [TimeInterval] = []
    private var wake = false
    private let seoul = CLLocationCoordinate2D(latitude: 37.5663, longitude: 126.9779)

    private func makeSession() -> RouteGuideSession {
        let session = RouteGuideSession(environment: .init(
            send: { [wire] in try await wire.send($0, key: $1, keyed: $2) },
            serverKeepsKeys: { [unowned self] in serverKeepsKeys },
            lang: { .ko },
            newKey: UUID.init,
            asked: {},
            now: { [unowned self] in now },
            wall: { [unowned self] in wall },
            quota: { [unowned self] in quota },
            sleep: { [unowned self] seconds in
                sleeps.append(seconds)
                while !wake, !Task.isCancelled {
                    await Task.yield()
                }
                wake = false
            }
        ))
        session.setAttended(true, by: UUID())
        return session
    }

    private func limited(retryAfter: Int?) -> Result<RouteGuide.Answer, Error> {
        let url = URL(string: "http://localhost:8081/v1/guide/chat")!
        let headers = retryAfter.map { ["Retry-After": String($0)] } ?? [:]
        let response = HTTPURLResponse(url: url, statusCode: 429, httpVersion: "HTTP/1.1", headerFields: headers)
        let data = Data(#"{"code":"GUIDE_LIMIT_REACHED","message":"x"}"#.utf8)
        return .failure(ErrorResponse.error(429, data, response, URLError(.badServerResponse)))
    }

    private func settle(until done: () -> Bool) async {
        for _ in 0 ..< 2000 where !done() {
            await Task.yield()
        }
    }

    /// 한도가 풀리는 때까지 시간을 돌리고 타이머를 깨운다.
    private func pass(_ seconds: TimeInterval, _ session: RouteGuideSession) async {
        await settle { !self.sleeps.isEmpty }
        now += seconds
        wake = true
        await settle { !self.wake && session.limit == .lifted }
    }

    /// 짧은 창 — 잠그고, 다시 보내지 않고, 풀리면 **같은 키·같은 요청**으로 「다시 시도」.
    func testShortLimitLocksThenRetriesWithTheSameKey() async {
        let session = makeSession()
        wire.replies = [limited(retryAfter: 20), .success(RouteGuideTurnTests.Wire.answer("이제 답"))]
        await session.ask("근처 카페", here: seoul, context: nil)

        XCTAssertEqual(session.failure, .limitReached(retryAfter: 20))
        XCTAssertEqual(session.limit, .reached(GuideLimit.Block(retryAfter: 20, now: now)))
        XCTAssertTrue(session.isLimited)
        XCTAssertFalse(session.canRetry, "풀리기 전에는 단추가 없다")
        XCTAssertFalse(session.asking)
        XCTAssertEqual(wire.sent.count, 1, "자동으로 다시 보내지 않는다")

        // 잠긴 동안에는 새 질문도 「다시 시도」 도 나가지 않는다 — 뻔히 429 다.
        await session.ask("다른 질문", here: seoul, context: nil)
        await session.retry()
        XCTAssertEqual(wire.sent.count, 1)
        XCTAssertEqual(session.turns.map(\.text), ["근처 카페"])

        await pass(20, session)
        XCTAssertEqual(sleeps, [20])
        XCTAssertEqual(session.limit, .lifted)
        XCTAssertFalse(session.isLimited)
        XCTAssertTrue(session.canRetry)

        await session.retry()
        XCTAssertEqual(wire.sent.count, 2)
        XCTAssertEqual(wire.sent[1].key, wire.sent[0].key)
        XCTAssertEqual(wire.sent[1].request, wire.sent[0].request)
        XCTAssertEqual(session.turns.map(\.text), ["근처 카페", "이제 답"])
        XCTAssertEqual(session.limit, .clear)
        XCTAssertNil(session.failure)
    }

    /// 타이머가 늦어도(앱이 뒤에 있었다) 보내기 직전과 창을 열 때 시계를 다시 본다.
    func testLateTimerDoesNotKeepTheLock() async {
        let session = makeSession()
        wire.replies = [limited(retryAfter: 20)]
        await session.ask("하나", here: seoul, context: nil)
        now += 21
        XCTAssertFalse(session.isLimited)
        session.setAttended(true, by: UUID())
        XCTAssertEqual(session.limit, .lifted)

        await session.ask("둘", here: seoul, context: nil) // 풀린 뒤의 새 질문은 새 키다
        XCTAssertEqual(wire.sent.count, 2)
        XCTAssertNotEqual(wire.sent[1].key, wire.sent[0].key)
        XCTAssertEqual(session.limit, .clear)
    }

    /// 하루 한도 — 같은 잠금이고 문구만 다르다. 걸려 있는 동안에는 단추가 없다.
    func testDayLimitLocksToo() async {
        let session = makeSession()
        wire.replies = [limited(retryAfter: 30000)]
        await session.ask("하나", here: seoul, context: nil)
        guard case let .reached(block) = session.limit else { return XCTFail("한도 안내가 없다") }
        XCTAssertEqual(block.window, .day)
        XCTAssertTrue(session.isLimited)
        XCTAssertFalse(session.canRetry)
    }

    /// 잠금은 연속 시계로 잰다 — 기기 시계를 하루 앞으로·뒤로 돌려도 늘거나 줄지 않는다.
    func testWallClockChangesDoNotMoveTheLock() async {
        let session = makeSession()
        wire.replies = [limited(retryAfter: 600)]
        await session.ask("하나", here: seoul, context: nil)

        wall.addTimeInterval(86400) // 시계를 내일로
        session.refreshLimit()
        XCTAssertTrue(session.isLimited, "시계를 앞으로 돌려 잠금을 풀 수 없다")
        wall.addTimeInterval(-3 * 86400) // 그저께로
        now += 599
        XCTAssertTrue(session.isLimited)
        now += 1
        XCTAssertFalse(session.isLimited, "시계를 뒤로 돌려도 잠금이 늘지 않는다")
        session.refreshLimit()
        XCTAssertEqual(session.limit, .lifted)
    }

    /// `Retry-After` 가 없으면 잠그지 않는다 — 안내는 보이고(「내일」 이라 하지 않는다), 「다시 시도」 가 바로 떠
    /// **같은 키**로 다시 보낸다. 새 질문도 보낼 수 있다(서버가 정한다).
    func testUnknownRetryAfterShowsNoticeWithoutLocking() async {
        let session = makeSession()
        wire.replies = [
            limited(retryAfter: nil), limited(retryAfter: nil), .success(RouteGuideTurnTests.Wire.answer("답")),
        ]
        await session.ask("하나", here: seoul, context: nil)
        XCTAssertEqual(session.limit, .reached(GuideLimit.Block(retryAfter: nil, now: now)))
        XCTAssertFalse(session.isLimited)
        XCTAssertTrue(session.canRetry, "잠그지 않으니 단추가 바로 뜬다")
        XCTAssertEqual(sleeps, [], "깨어날 때를 모른다")

        await session.retry()
        XCTAssertEqual(wire.sent.count, 2)
        XCTAssertEqual(wire.sent[1].key, wire.sent[0].key)
        XCTAssertEqual(wire.sent[1].request, wire.sent[0].request)
        XCTAssertTrue(session.canRetry)

        await session.ask("둘", here: seoul, context: nil)
        XCTAssertEqual(wire.sent.count, 3)
        XCTAssertNotEqual(wire.sent[2].key, wire.sent[0].key)
        XCTAssertEqual(session.limit, .clear)
        XCTAssertEqual(session.turns.map(\.text), ["하나", "둘", "답"])
    }

    /// 한도는 계정의 것이다 — 다른 코스를 열어도 잠겨 있고, 계정이 바뀌면 풀린다.
    func testLimitSurvivesAnotherCourseButNotAnotherAccount() async {
        let session = makeSession()
        session.bind(to: "course-1")
        wire.replies = [limited(retryAfter: 600)]
        await session.ask("하나", here: seoul, context: nil)

        session.bind(to: "course-2")
        XCTAssertTrue(session.isEmpty)
        XCTAssertNil(session.failure)
        XCTAssertTrue(session.isLimited, "대화는 새것이어도 한도는 그대로다")
        await session.ask("둘", here: seoul, context: nil)
        XCTAssertEqual(wire.sent.count, 1)

        session.forgetLimit()
        XCTAssertEqual(session.limit, .clear)
        XCTAssertFalse(session.isLimited)
        XCTAssertNil(session.quota)
    }

    /// 요청이 떠 있는 사이에 계정이 바뀌었다 — 늦게 돌아온 429 가 **새 계정**을 잠그면 안 된다. 답이어도 버린다.
    func testResultThatArrivesAfterAnAccountSwitchIsDropped() async {
        let lates: [Result<RouteGuide.Answer, Error>] = [
            limited(retryAfter: 600), .success(RouteGuideTurnTests.Wire.answer("앞 계정의 답")),
        ]
        for late in lates {
            var release = false
            var sent = 0
            let session = RouteGuideSession(environment: .init(
                send: { _, _, _ in
                    sent += 1
                    while !release { // 취소돼도 서버는 이미 답을 보내고 있다 — 끊김을 무시하고 늦게 돌아온다
                        await Task.yield()
                    }
                    return try late.get()
                },
                serverKeepsKeys: { true }, lang: { .ko }, newKey: UUID.init, asked: {},
                now: { [unowned self] in now }, wall: { [unowned self] in wall },
                quota: { .init(limit: 15, remaining: 0, resetsAt: Date(timeIntervalSince1970: 2_000_600)) },
                sleep: { _ in }
            ))
            session.setAttended(true, by: UUID())
            let asking = Task { await session.ask("앞 계정의 질문", here: seoul, context: nil) }
            await settle { sent == 1 }

            session.forgetLimit() // 로그아웃·다른 계정으로 로그인
            release = true
            await asking.value

            XCTAssertEqual(session.limit, .clear)
            XCTAssertFalse(session.isLimited)
            XCTAssertNil(session.failure)
            XCTAssertNil(session.pending)
            XCTAssertNil(session.quota, "앞 계정의 남은 양도 들이지 않는다")
            XCTAssertEqual(session.turns.map(\.text), ["앞 계정의 질문"])
            XCTAssertFalse(session.asking)
        }
    }

    /// 서버가 멱등 키를 모르면(옛 서버) 턴을 남기지 않는다 — 풀린 뒤에도 「다시 시도」 는 없고 새로 묻는다.
    func testUnkeyedTurnIsNotKept() async {
        serverKeepsKeys = false
        let session = makeSession()
        wire.replies = [limited(retryAfter: 20)]
        await session.ask("하나", here: seoul, context: nil)
        XCTAssertNil(session.pending)
        XCTAssertTrue(session.isLimited)
        await pass(20, session)
        XCTAssertEqual(session.limit, .lifted)
        XCTAssertFalse(session.canRetry)
    }

    /// 남은 양은 응답마다 장부에서 다시 읽는다. 답이 오면 한도 안내는 걷힌다.
    func testQuotaFollowsEachResponse() async {
        let session = makeSession()
        XCTAssertNil(session.quota)
        quota = .init(limit: 15, remaining: 3, resetsAt: wall.addingTimeInterval(1200))
        await session.ask("하나", here: seoul, context: nil)
        XCTAssertEqual(session.quota, .init(limit: 15, remaining: 3, refillsAt: now + 1200))
        XCTAssertEqual(GuideLimit.remaining(session.quota, now: now), .few(count: 3, refillMinutes: 20))

        // 헤더 없는 답(멱등 키로 되돌려 준 것) — 장부가 같은 값을 든다. 그 사이 기기 시계가 바뀌어도 옮겨 둔
        // 시각은 그대로다.
        wall.addTimeInterval(86400)
        now += 60
        await session.ask("하나 반", here: seoul, context: nil)
        XCTAssertEqual(session.quota?.refillsAt, 9000 + 1200)
        XCTAssertEqual(GuideLimit.remaining(session.quota, now: now), .few(count: 3, refillMinutes: 19))

        // 429 도 `RateLimit-Remaining: 0` 을 싣는다.
        quota = .init(limit: 15, remaining: 0, resetsAt: wall.addingTimeInterval(1200))
        wire.replies = [limited(retryAfter: 1200)]
        await session.ask("둘", here: seoul, context: nil)
        XCTAssertEqual(session.quota?.remaining, 0)

        // 옛 서버 — 헤더가 없어 장부가 비어 있다.
        quota = nil
        session.forgetLimit()
        await session.ask("셋", here: seoul, context: nil)
        XCTAssertNil(session.quota)
        XCTAssertEqual(GuideLimit.remaining(session.quota, now: now), .hidden)
    }
}
