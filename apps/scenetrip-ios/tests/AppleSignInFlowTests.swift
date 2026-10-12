import Foundation
import SceneApiClient
@testable import SceneTrip
import XCTest

final class AppleSignInFlowTests: XCTestCase {
    @MainActor
    func testGuestWritesFinishBeforeServerReceivesLoginAndMerge() async throws {
        let installId = UUID()
        let credential = body()
        let session = response()
        var writeFinished = false
        var exchanges = 0
        PendingWrites.begin()
        let writing = Task { @MainActor in
            try await Task.sleep(for: .milliseconds(100))
            writeFinished = true
            PendingWrites.end()
        }
        defer {
            writing.cancel()
            if !writeFinished {
                PendingWrites.end()
            }
        }
        let received = try await AppleSignInFlow.signIn(installId: installId, authorize: { credential },
                                                        exchange: { id, payload in
                                                            exchanges += 1
                                                            XCTAssertTrue(writeFinished, "합치기 전 비회원 계정에 저장 중인 내용을 남기지 않는다")
                                                            XCTAssertEqual(id, installId)
                                                            XCTAssertEqual(payload.identityToken, credential.identityToken)
                                                            XCTAssertEqual(payload.authorizationCode, credential.authorizationCode)
                                                            XCTAssertEqual(payload.nonce, credential.nonce)
                                                            return session
                                                        })
        XCTAssertEqual(exchanges, 1)
        XCTAssertEqual(received.accessToken, session.accessToken)
        XCTAssertEqual(received.refreshToken, session.refreshToken)
        XCTAssertEqual(received.user.id, session.user.id)
        XCTAssertTrue(received.isNewUser)
        XCTAssertTrue(received.merged)
    }

    @MainActor
    func testCancellingAppleSheetNeverCallsServer() async {
        var waits = 0
        var exchanges = 0
        do {
            _ = try await AppleSignInFlow.signIn(installId: UUID(), authorize: {
                throw AppleOAuth.Failure.cancelled
            }, settle: { waits += 1 }, exchange: { _, _ in
                exchanges += 1
                return self.response()
            })
            XCTFail("취소를 로그인 성공으로 처리했다")
        } catch AppleOAuth.Failure.cancelled {
        } catch {
            XCTFail("취소가 다른 오류로 바뀌었다")
        }
        XCTAssertEqual(waits, 0)
        XCTAssertEqual(exchanges, 0)
    }

    @MainActor
    func testUnfinishedGuestWritesPreventServerLogin() async {
        var exchanges = 0
        do {
            _ = try await AppleSignInFlow.signIn(installId: UUID(), authorize: { self.body() }, settle: {
                throw PendingWrites.Failure.stillRunning
            }, exchange: { _, _ in
                exchanges += 1
                return self.response()
            })
            XCTFail("쓰기가 끝나지 않았는데 로그인을 진행했다")
        } catch PendingWrites.Failure.stillRunning {
        } catch {
            XCTFail("저장 대기 오류가 바뀌었다")
        }
        XCTAssertEqual(exchanges, 0)
    }

    @MainActor
    func testCancelledWriteWaitDoesNotExchangeSingleUseCode() async {
        let entered = expectation(description: "저장 완료 대기 시작")
        var exchanges = 0
        PendingWrites.begin()
        defer { PendingWrites.end() }
        let login = Task { @MainActor in
            try await AppleSignInFlow.signIn(installId: UUID(), authorize: { self.body() }, settle: {
                entered.fulfill()
                try await PendingWrites.settle()
            }, exchange: { _, _ in
                exchanges += 1
                return self.response()
            })
        }
        await fulfillment(of: [entered], timeout: 1)
        login.cancel()
        do {
            _ = try await login.value
            XCTFail("취소한 로그인이 성공했다")
        } catch is CancellationError {
        } catch {
            XCTFail("취소가 아닌 오류다")
        }
        XCTAssertEqual(exchanges, 0)
    }

    @MainActor
    func testGuestWriteTimeoutFailsRatherThanMergingEarly() async {
        PendingWrites.begin()
        defer { PendingWrites.end() }
        do {
            try await PendingWrites.settle()
            XCTFail("3초 뒤 저장 중인 계정을 병합하게 놔뒀다")
        } catch PendingWrites.Failure.stillRunning {
        } catch {
            XCTFail("저장 대기 시간 초과가 아닌 오류다")
        }
    }

    private func body() -> AppleSignIn {
        AppleSignIn(identityToken: "unit-identity", authorizationCode: "unit-code", nonce: "unit-raw-nonce-at-least-16")
    }

    @MainActor
    func testGeneratedAppleEndpointCarriesInstallIdAndRawNonce() async throws {
        let installId = UUID()
        let received = try await withServer(status: 200) {
            try await AppleSignInFlow.signIn(installId: installId, authorize: { self.body() })
        }
        let requests = AppleTransport.captured()
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/v1/auth/apple")
        XCTAssertEqual(request.value(forHTTPHeaderField: "X-Install-Id"), installId.uuidString)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
        XCTAssertEqual(json["identityToken"] as? String, "unit-identity")
        XCTAssertEqual(json["authorizationCode"] as? String, "unit-code")
        XCTAssertEqual(json["nonce"] as? String, body().nonce)
        XCTAssertTrue(received.merged)
        XCTAssertEqual(received.user.id.uuidString.lowercased(), "9d38bdc9-ab71-402c-907f-0986d2edab64")
    }

    @MainActor
    func testServerRejectionNeverRetriesCodeOrCreatesSession() async throws {
        let priorAccess = AuthTokens.accessToken
        let priorRefresh = AuthTokens.refreshToken
        let priorEpoch = AuthStore.shared.epoch
        for status in [401, 429, 501, 503] {
            do {
                _ = try await withServer(status: status) {
                    try await AppleSignInFlow.signIn(installId: UUID(), authorize: { self.body() })
                }
                XCTFail("인증 거절을 성공으로 바꿨다")
            } catch let ErrorResponse.error(received, _, _, _) {
                XCTAssertEqual(received, status)
                XCTAssertEqual(AppleTransport.captured().count, 1, "일회용 코드를 다시 보내지 않는다")
            } catch {
                XCTFail("서버 인증 오류가 바뀌었다: \(error)")
            }
        }
        XCTAssertEqual(AuthTokens.accessToken, priorAccess)
        XCTAssertEqual(AuthTokens.refreshToken, priorRefresh)
        XCTAssertEqual(AuthStore.shared.epoch, priorEpoch)
    }

    @MainActor
    private func withServer<T>(status: Int, operation: () async throws -> T) async rethrows -> T {
        let factory = SceneApiClientAPI.requestBuilderFactory
        let base = SceneApiClientAPI.basePath
        let headers = SceneApiClientAPI.customHeaders
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AppleTransport.self]
        let session = URLSession(configuration: config)
        AppleTransport.reset(session: session, status: status)
        SceneApiClientAPI.basePath = "https://api.example.invalid/v1"
        SceneApiClientAPI.customHeaders = ["Accept-Language": "en"]
        SceneApiClientAPI.requestBuilderFactory = AppleApiFactory()
        defer {
            session.invalidateAndCancel()
            SceneApiClientAPI.requestBuilderFactory = factory
            SceneApiClientAPI.basePath = base
            SceneApiClientAPI.customHeaders = headers
        }
        return try await operation()
    }

    private func response() -> AuthSession {
        AuthSession(accessToken: "unit-access", accessTokenExpiresIn: 1800, refreshToken: "unit-refresh",
                    refreshTokenExpiresIn: 3600,
                    user: Me(id: UUID(), identities: [], registeredAt: Date(timeIntervalSince1970: 1_000_000)),
                    isNewUser: true, merged: true)
    }
}

private struct AppleApiFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        ApplePlainApiBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        AppleApiBuilder<T>.self
    }
}

private final class AppleApiBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        RetryingSession(inner: AppleTransport.session)
    }
}

private final class ApplePlainApiBuilder<T>: URLSessionRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        RetryingSession(inner: AppleTransport.session)
    }
}

/// 생성 API의 실제 요청·응답을 검사한다. 모든 주소를 가로채 실제 네트워크를 쓰지 않는다.
private final class AppleTransport: URLProtocol, @unchecked Sendable {
    static var session = URLSession.shared
    private static let lock = NSLock()
    private static var status = 200
    private static var requests: [URLRequest] = []

    static func reset(session: URLSession, status: Int) {
        lock.lock()
        defer { lock.unlock() }
        self.session = session
        self.status = status
        requests = []
    }

    static func captured() -> [URLRequest] {
        lock.lock()
        defer { lock.unlock() }
        return requests
    }

    override class func canInit(with _: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard request.url?.host == "api.example.invalid", request.url?.path == "/v1/auth/apple" else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
            return
        }
        var captured = request
        if captured.httpBody == nil, let stream = request.httpBodyStream {
            stream.open()
            defer { stream.close() }
            var body = Data()
            var bytes = [UInt8](repeating: 0, count: 1024)
            while stream.hasBytesAvailable {
                let size = stream.read(&bytes, maxLength: bytes.count)
                guard size > 0 else { break }
                body.append(bytes, count: size)
            }
            captured.httpBody = body
        }
        Self.lock.lock()
        Self.requests.append(captured)
        let status = Self.status
        Self.lock.unlock()
        let success = #"""
        {"accessToken":"unit-access","accessTokenExpiresIn":1800,"refreshToken":"unit-refresh",
         "refreshTokenExpiresIn":3600,"isNewUser":true,"merged":true,
         "user":{"id":"9d38bdc9-ab71-402c-907f-0986d2edab64","identities":[],"registeredAt":"2026-10-12T00:00:00Z"}}
        """#
        let body = status == 200 ? success : #"{"code":"AUTH_PROVIDER_UNAVAILABLE","message":"fixture"}"#
        guard let url = request.url, let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: "HTTP/1.1",
                                                                    headerFields: ["Content-Type": "application/json"]) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: status == 501 ? Data() : Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
