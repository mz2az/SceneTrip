import SceneApiClient
@testable import SceneTrip
import XCTest

/// 설치 식별자 새로 만들기가 **생성 클라이언트 경로에 실제로 이어져 있는가** (MZ2AZ-521).
///
/// `InstallRenewalTests` 는 규칙(`InstallRenewal.handle`)만 본다 — 401 처리(`AuthIntercept.finish`)에서 그것을
/// 부르는 줄이 빠져도 그 시험은 다 통과한다. 여기서는 `CartAPI.getCart` 를 앱과 같은 빌더 팩토리
/// (`AuthRequestBuilderFactory`)로 부르고, 네트워크 자리에 URLProtocol 대역을 둔다. 서버에 닿지 않는다 —
/// 주소도 `.invalid` 다.
///
/// 생성 클라이언트의 기본 세션에는 전역 `URLProtocol` 등록이 닿지 않아(실측), 대역을 단 세션을 재시도 세션으로
/// 감싸 `AuthTransportOverride` 자리에 끼운다. 그 위의 401 처리·다시 보내기는 앱과 같은 코드다.
final class InstallRenewalWiringTests: XCTestCase {
    private let old = UUID(uuidString: "11111111-2222-3333-4444-555555555521")!

    func testGeneratedGetIsResentOnceWithRenewedInstallId() async throws {
        let factory = SceneApiClientAPI.requestBuilderFactory
        let basePath = SceneApiClientAPI.basePath
        let headers = SceneApiClientAPI.customHeaders
        let environment = InstallRenewal.environment
        let transport = AuthTransportOverride.session
        defer {
            SceneApiClientAPI.requestBuilderFactory = factory
            SceneApiClientAPI.basePath = basePath
            SceneApiClientAPI.customHeaders = headers
            InstallRenewal.environment = environment
            AuthTransportOverride.session = transport
            BoundInstallTransport.reset()
        }
        AuthTokens.clear()
        let ids = InstallIdHolder(
            secure: WiringStore(old.uuidString), legacy: WiringStore(), freshInstall: { false }
        )
        let renewals = WiringCounter()
        InstallRenewal.environment = InstallRenewal.Environment(holder: ids, renewed: { renewals.add() })
        BoundInstallTransport.reset(bound: old.uuidString)
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [BoundInstallTransport.self]
        AuthTransportOverride.session = RetryingSession(
            inner: URLSession(configuration: config), environment: RetryFakeClock().environment(),
            ledger: RateLimitLedger()
        )
        SceneApiClientAPI.basePath = "https://\(BoundInstallTransport.host)/v1"
        SceneApiClientAPI.customHeaders = [:]
        SceneApiClientAPI.requestBuilderFactory = AuthRequestBuilderFactory()

        let cart = try await CartAPI.getCart(xInstallId: ids.current)

        let renewed = ids.current
        XCTAssertNotEqual(renewed, old)
        XCTAssertEqual(cart.totalCount, 0)
        XCTAssertEqual(BoundInstallTransport.seen, [old.uuidString, renewed.uuidString])
        XCTAssertEqual(BoundInstallTransport.authorization, [nil, nil])
        XCTAssertEqual(renewals.value, 1)
    }
}

/// 묶인 번호로 온 요청에는 `401 SESSION_REQUIRED`, 다른 번호면 빈 장바구니를 준다.
private final class BoundInstallTransport: URLProtocol, @unchecked Sendable {
    static let host = "install-renewal.unit.invalid"
    private static let lock = NSLock()
    private static var boundId: String?
    private static var seenIds: [String?] = []
    private static var authHeaders: [String?] = []

    static var seen: [String?] {
        lock.lock()
        defer { lock.unlock() }
        return seenIds
    }

    static var authorization: [String?] {
        lock.lock()
        defer { lock.unlock() }
        return authHeaders
    }

    static func reset(bound: String? = nil) {
        lock.lock()
        boundId = bound
        seenIds = []
        authHeaders = []
        lock.unlock()
    }

    override static func canInit(with request: URLRequest) -> Bool {
        request.url?.host == host
    }

    override static func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        let id = request.value(forHTTPHeaderField: "X-Install-Id")
        Self.lock.lock()
        Self.seenIds.append(id)
        Self.authHeaders.append(request.value(forHTTPHeaderField: "Authorization"))
        let bound = id != nil && id == Self.boundId
        Self.lock.unlock()
        let status = bound ? 401 : 200
        let body = bound
            ? #"{"code":"SESSION_REQUIRED","message":"x"}"#
            : #"{"items":[],"totalCount":0}"#
        let response = HTTPURLResponse(
            url: request.url!, statusCode: status, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

private final class WiringStore: InstallIdStore, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: String?

    init(_ value: String? = nil) {
        stored = value
    }

    func read() -> String? {
        lock.lock()
        defer { lock.unlock() }
        return stored
    }

    func write(_ value: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        stored = value
        return true
    }

    func remove() {
        lock.lock()
        defer { lock.unlock() }
        stored = nil
    }
}

private final class WiringCounter: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0

    var value: Int {
        lock.lock()
        defer { lock.unlock() }
        return count
    }

    func add() {
        lock.lock()
        count += 1
        lock.unlock()
    }
}
