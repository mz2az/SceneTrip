import SceneApiClient
@testable import SceneTrip
import XCTest

/// 가입 계정에 묶인 설치 식별자를 버리고 새 비회원이 되는 규칙 (MZ2AZ-521, 웹 MZ2AZ-520 과 같다).
///
/// 로그인하면 서버가 이 설치본의 `X-Install-Id` 를 가입 계정에 묶는다. 로그인이 저절로 풀려 토큰만
/// 지워지면, 토큰 없는 요청이 모두 `401 SESSION_REQUIRED` 를 받아 홈·장바구니·찜이 영영 비어 있다.
/// 시험은 네트워크 없이 — 저장소는 메모리, 다시 보내기는 정해 둔 답을 돌려준다.
private typealias RenewalResult = Swift.Result<Response<Void>, ErrorResponse>

final class InstallRenewalTests: XCTestCase {
    private let old = UUID(uuidString: "11111111-2222-3333-4444-555555555555")!

    private func holder(
        _ secure: RenewalFakeStore, _ legacy: RenewalFakeStore = RenewalFakeStore()
    ) -> InstallIdHolder {
        InstallIdHolder(secure: secure, legacy: legacy, freshInstall: { false })
    }

    private func builder(
        _ method: String = "GET", installId: UUID, token: String? = nil
    ) -> URLSessionRequestBuilder<Void> {
        let made = URLSessionRequestBuilder<Void>(
            method: method, URLString: "http://unit.test/v1/cart", parameters: nil,
            headers: ["X-Install-Id": installId.uuidString], requiresAuthentication: true
        )
        if let token {
            _ = made.addHeader(name: "Authorization", value: "Bearer \(token)")
        }
        return made
    }

    private static func rejected(_ code: String, status: Int = 401) -> RenewalResult {
        let body = Data(#"{"code":"\#(code)","message":"x"}"#.utf8)
        return .failure(.error(status, body, nil, NSError(domain: "unit", code: status)))
    }

    private static var sessionRequired: RenewalResult {
        rejected("SESSION_REQUIRED")
    }

    private static var success: RenewalResult {
        .success(Response(statusCode: 200, header: [:], body: (), bodyData: nil))
    }

    private static func status(_ result: RenewalResult?) -> Int? {
        switch result {
        case let .success(response): response.statusCode
        case let .failure(.error(status, _, _, _)): status
        case nil: nil
        }
    }

    /// `InstallRenewal.handle` 을 부른다. 다시 보내면 `answer` 를 돌려준다.
    @discardableResult
    private func handle(
        _ result: RenewalResult, builder: URLSessionRequestBuilder<Void>, holder: InstallIdHolder, probe: Probe,
        hasSession: Bool = false, answer: RenewalResult = success
    ) -> Bool {
        InstallRenewal.handle(
            result, builder: builder, holder: holder, hasSession: { hasSession },
            renewed: { probe.renewed() },
            resend: { done in
                probe.resent(builder.buildHeaders()["X-Install-Id"])
                done(answer)
            },
            completion: { probe.finished($0) }
        )
    }

    // MARK: 요청 가로채기

    /// 토큰 없이 보낸 조회가 SESSION_REQUIRED 를 받으면 번호를 바꾸고 **새 번호로 한 번** 다시 보내 그 응답을 돌려준다.
    func testGetWithoutTokenRenewsAndResendsOnceWithNewId() {
        let ids = holder(RenewalFakeStore(old.uuidString))
        let probe = Probe()

        let handled = handle(Self.sessionRequired, builder: builder(installId: old), holder: ids, probe: probe)

        XCTAssertTrue(handled)
        XCTAssertNotEqual(ids.current, old)
        XCTAssertEqual(probe.resentIds, [ids.current.uuidString])
        XCTAssertEqual(probe.results.map(Self.status), [200])
        XCTAssertEqual(probe.renewals, 1)
    }

    /// 다시 보낸 것도 SESSION_REQUIRED 면 그 401 을 돌려주고 더 바꾸거나 보내지 않는다.
    func testResentGetFailingAgainIsReturnedWithoutMoreRenewal() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let probe = Probe()

        handle(
            Self.sessionRequired, builder: builder(installId: old), holder: ids, probe: probe,
            answer: Self.sessionRequired
        )

        XCTAssertEqual(probe.resentIds.count, 1)
        XCTAssertEqual(probe.results.map(Self.status), [401])
        XCTAssertEqual(secure.writes, 1)
        XCTAssertEqual(probe.renewals, 1)
    }

    /// 같은 낡은 번호로 나간 조회 셋이 모두 거절받아도 번호는 한 번만 바뀌고, 셋 다 같은 새 번호로 다시 나간다.
    func testConcurrentRejectionsWithSameOldIdRenewOnce() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let probe = Probe()
        let builders = (0 ..< 3).map { _ in builder(installId: old) }

        DispatchQueue.concurrentPerform(iterations: builders.count) { index in
            handle(Self.sessionRequired, builder: builders[index], holder: ids, probe: probe)
        }

        XCTAssertNotEqual(ids.current, old)
        XCTAssertEqual(secure.writes, 1)
        XCTAssertEqual(probe.renewals, 1)
        XCTAssertEqual(probe.resentIds, Array(repeating: ids.current.uuidString, count: 3))
        XCTAssertEqual(probe.results.map(Self.status), [200, 200, 200])
    }

    /// 쓰기는 번호를 바꾸되 다시 보내지 않는다 — 한 번 거절된 쓰기를 다른 계정에 떨구지 않는다.
    func testWriteRenewsButIsNotResent() {
        for method in ["POST", "PUT", "PATCH", "DELETE"] {
            let ids = holder(RenewalFakeStore(old.uuidString))
            let probe = Probe()

            let write = builder(method, installId: old)
            let handled = handle(Self.sessionRequired, builder: write, holder: ids, probe: probe)

            XCTAssertTrue(handled, method)
            XCTAssertNotEqual(ids.current, old, method)
            XCTAssertEqual(probe.resentIds.count, 0, method)
            XCTAssertEqual(probe.results.map(Self.status), [401], method)
            XCTAssertEqual(probe.renewals, 1, method)
        }
    }

    /// 거절이 도착할 즈음 세션이 있으면 번호를 바꾸지 않고 **기존 표(signOut)에 넘긴다** — 방금 계정에 묶인 번호를
    /// 버리면 안 되고, 액세스 토큰 없이 리프레시 토큰만 남은 상태라면 기존 처리가 세션을 정리해 로그인 화면으로 이끈다.
    func testSessionPresentNowKeepsInstallIdAndFallsThrough() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let probe = Probe()

        let handled = handle(
            Self.sessionRequired, builder: builder(installId: old), holder: ids, probe: probe, hasSession: true
        )

        XCTAssertFalse(handled, "기존 표가 이어 받아야 합니다")
        XCTAssertEqual(ids.current, old)
        XCTAssertEqual(secure.writes, 0)
        XCTAssertEqual(probe.resentIds.count, 0)
        XCTAssertEqual(probe.renewals, 0)
        XCTAssertTrue(probe.results.isEmpty, "여기서 결과를 넘기면 기존 표가 돌지 않습니다")
    }

    /// 넘겨받은 기존 표의 signOut 은 그 사이 새로 로그인했다면 아무것도 지우지 않는다(`clear(ifCurrent:)` 가 실패).
    @MainActor func testFallThroughSignOutCannotClearNewLogin() throws {
        AuthTokens.clear()
        let sentWith = AuthTokens.snapshot
        try AuthTokens.store(AuthSession(
            accessToken: "unit-new-access", accessTokenExpiresIn: 1800,
            refreshToken: "unit-new-refresh", refreshTokenExpiresIn: 2_592_000,
            user: Me(nickname: "시험회원", nicknameConfirmed: true,
                     id: XCTUnwrap(UUID(uuidString: "00000000-0000-0000-0000-000000000521")), identities: [],
                     registeredAt: Date(timeIntervalSince1970: 1)),
            isNewUser: false, merged: false
        ))
        defer { AuthTokens.clear() }
        var lost = 0

        AuthRefresher.loseSession(ifCurrent: sentWith, action: { lost += 1 })

        XCTAssertEqual(lost, 0)
        XCTAssertEqual(AuthTokens.accessToken, "unit-new-access")
    }

    /// 토큰을 실어 받은 거절은 이 규칙이 아니다 — 기존 로그아웃 처리(`AuthRules` 의 signOut)가 맡는다.
    func testTokenCarryingRejectionIsLeftToSignOut() {
        let ids = holder(RenewalFakeStore(old.uuidString))
        let probe = Probe()

        let signedIn = builder(installId: old, token: "unit")
        let handled = handle(Self.sessionRequired, builder: signedIn, holder: ids, probe: probe)

        XCTAssertFalse(handled)
        XCTAssertEqual(ids.current, old)
        XCTAssertTrue(probe.results.isEmpty)
        XCTAssertEqual(probe.renewals, 0)
    }

    /// 이미 바뀐 번호와 다른 옛 번호로 온 거절은 번호를 다시 바꾸지 않는다. 조회는 지금 번호로 한 번 다시 나간다.
    func testRejectionFromOlderIdDoesNotRenewAgain() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let current = ids.renew(from: old).current
        let probe = Probe()

        handle(Self.sessionRequired, builder: builder(installId: old), holder: ids, probe: probe)

        XCTAssertEqual(ids.current, current)
        XCTAssertEqual(secure.writes, 1)
        XCTAssertEqual(probe.renewals, 0)
        XCTAssertEqual(probe.resentIds, [current.uuidString])
        XCTAssertEqual(probe.results.map(Self.status), [200])
    }

    /// 새 번호도 거절되면(한 실행에서 두 번째) 바꾸지도 다시 보내지도 않고 기존 처리에 넘긴다 — 고리를 막는다.
    func testRejectionOfRenewedIdFallsThroughWithoutLoop() {
        let secure = RenewalFakeStore(old.uuidString)
        let ids = holder(secure)
        let renewed = ids.renew(from: old).current
        let probe = Probe()

        let handled = handle(Self.sessionRequired, builder: builder(installId: renewed), holder: ids, probe: probe)

        XCTAssertFalse(handled)
        XCTAssertEqual(ids.current, renewed)
        XCTAssertEqual(secure.writes, 1)
        XCTAssertEqual(probe.renewals, 0)
        XCTAssertEqual(probe.resentIds.count, 0)
        XCTAssertTrue(probe.results.isEmpty)
    }

    /// 다른 거절은 건드리지 않는다.
    func testOtherFailuresAreNotHandled() {
        let others: [RenewalResult] = [
            Self.rejected("ACCESS_TOKEN_INVALID"), Self.rejected("SIGN_IN_REQUIRED"),
            Self.rejected("SESSION_REQUIRED", status: 403), Self.rejected("INTERNAL", status: 500), Self.success,
        ]
        for result in others {
            let ids = holder(RenewalFakeStore(old.uuidString))
            let probe = Probe()
            XCTAssertFalse(handle(result, builder: builder(installId: old), holder: ids, probe: probe))
            XCTAssertEqual(ids.current, old)
            XCTAssertTrue(probe.results.isEmpty)
        }
    }

    // MARK: 화면 다시 읽기

    /// 번호가 바뀌면 계정이 바뀐 것과 같다 — `epoch` 를 올려 `onAccountChange` 화면이 새 번호로 다시 읽는다.
    @MainActor func testRenewalReloadsGuestScreens() {
        let auth = AuthStore(hasStoredSession: { false }, refreshLikes: {})
        auth.installRenewed()
        XCTAssertEqual(auth.epoch, 1)
        XCTAssertFalse(auth.signedIn)
    }

    /// 그 사이 로그인했으면 화면을 흔들지 않는다.
    @MainActor func testRenewalIgnoredWhileSignedIn() {
        let factory = SceneApiClientAPI.requestBuilderFactory
        defer { SceneApiClientAPI.requestBuilderFactory = factory }
        let auth = AuthStore(hasStoredSession: { true }, fetchProfile: { throw URLError(.cancelled) }, refreshLikes: {})
        auth.start()
        auth.installRenewed()
        XCTAssertEqual(auth.epoch, 0)
        XCTAssertTrue(auth.signedIn)
    }

    /// 장바구니는 부를 때마다 지금 번호를 읽는다 — 만들 때 한 번 쥔 번호로는 바뀐 뒤에도 거절받는다.
    @MainActor func testCartUsesCurrentInstallIdOnEveryCall() async {
        var current = old
        let client = RecordingCartClient()
        let store = CartStore(installId: { current }, client: client)

        await store.refresh()
        let renewed = UUID()
        current = renewed
        await store.refresh()

        XCTAssertEqual(client.installIds, [old, renewed])
    }
}

@MainActor
private final class RecordingCartClient: CartClient {
    var installIds: [UUID] = []

    func fetch(installId: UUID) async throws -> Cart {
        installIds.append(installId)
        return Cart(items: [], totalCount: 0)
    }

    func add(installId: UUID, placeId _: Int64, sourceContentId _: Int64?) async throws {
        installIds.append(installId)
    }

    func remove(installId: UUID, placeId _: Int64) async throws {
        installIds.append(installId)
    }
}

/// 다시 보낸 요청과 알림을 센다.
private final class Probe: @unchecked Sendable {
    private let lock = NSLock()
    private var _resentIds: [String?] = []
    private var _results: [RenewalResult] = []
    private var _renewals = 0

    var resentIds: [String?] {
        lock.lock()
        defer { lock.unlock() }
        return _resentIds
    }

    var results: [RenewalResult] {
        lock.lock()
        defer { lock.unlock() }
        return _results
    }

    var renewals: Int {
        lock.lock()
        defer { lock.unlock() }
        return _renewals
    }

    func resent(_ id: String?) {
        lock.lock()
        _resentIds.append(id)
        lock.unlock()
    }

    func finished(_ result: RenewalResult) {
        lock.lock()
        _results.append(result)
        lock.unlock()
    }

    func renewed() {
        lock.lock()
        _renewals += 1
        lock.unlock()
    }
}

/// 메모리 저장소 — `InstallIdHolderTests` 와 `InstallRenewalTests` 가 같이 쓴다.
final class RenewalFakeStore: InstallIdStore, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?
    var writable = true
    private(set) var writes = 0

    init(_ value: String? = nil) {
        stored = value
    }

    var value: String? {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }

    func read() -> String? {
        value
    }

    func write(_ new: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard writable else { return false }
        writes += 1
        stored = new
        return true
    }

    func remove() {
        lock.lock()
        defer { lock.unlock() }
        stored = nil
    }
}
