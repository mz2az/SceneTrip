import SceneApiClient
@testable import SceneTrip
import XCTest

/// 사진 올리기의 규칙 (MZ2AZ-363) — 한도, 오류 가르기, 저장소로 보낼 요청.
final class PhotoUploadRulesTests: XCTestCase {
    /// 티켓 「긴 변 2,048px · 10MB」, 올리는 형식은 언제나 JPEG.
    func testLimitsMatchTheTicket() {
        XCTAssertEqual(PhotoUploadRules.longestSide, 2048)
        XCTAssertEqual(PhotoUploadRules.maxBytes, 10_485_760)
        XCTAssertEqual(PhotoUploadRules.contentType, "image/jpeg")
    }

    /// 서버 문장이 아니라 code 로 가른다.
    func testFailureIsDecidedByCode() {
        XCTAssertEqual(PhotoUploadRules.failure(status: 400, code: "UPLOAD_TOO_LARGE"), .tooLarge)
        XCTAssertEqual(PhotoUploadRules.failure(status: 400, code: "UPLOAD_TYPE_UNSUPPORTED"), .unsupportedType)
        XCTAssertEqual(PhotoUploadRules.failure(status: 503, code: "UPLOAD_UNAVAILABLE"), .unavailable)
        XCTAssertEqual(PhotoUploadRules.failure(status: 429, code: "RATE_LIMITED"), .rateLimited)
        XCTAssertEqual(PhotoUploadRules.failure(status: 401, code: "SIGN_IN_REQUIRED"), .signedOut)
        XCTAssertEqual(PhotoUploadRules.failure(status: 401, code: "ACCESS_TOKEN_INVALID"), .signedOut)
    }

    /// 본문이 없거나 모르는 code 면 상태로 물러선다. 그 밖은 「다시 시도」 할 수 있는 실패다.
    func testFailureFallsBackToTheStatus() {
        XCTAssertEqual(PhotoUploadRules.failure(status: 429, code: nil), .rateLimited)
        XCTAssertEqual(PhotoUploadRules.failure(status: 401, code: nil), .signedOut)
        XCTAssertEqual(PhotoUploadRules.failure(status: 500, code: "INTERNAL_ERROR"), .network)
        XCTAssertEqual(PhotoUploadRules.failure(status: 503, code: nil), .network)
        XCTAssertEqual(PhotoUploadRules.failure(status: 400, code: "INVALID_PARAMETER"), .network)
        // 연결이 안 되면 생성 클라이언트가 음수 상태를 준다.
        XCTAssertEqual(PhotoUploadRules.failure(status: -1, code: nil), .network)
    }

    /// 너무 크거나 못 읽는 사진은 다시 해도 같다 — 「다시 시도」 를 주지 않는다.
    func testRetryIsOfferedOnlyWhenItCanHelp() {
        XCTAssertFalse(PhotoUploadRules.retryable(.tooLarge))
        XCTAssertFalse(PhotoUploadRules.retryable(.unreadable))
        XCTAssertFalse(PhotoUploadRules.retryable(.unsupportedType))
        for failure in [PhotoUploadFailure.rateLimited, .unavailable, .signedOut, .expired, .storage, .network] {
            XCTAssertTrue(PhotoUploadRules.retryable(failure), "\(failure)")
        }
    }

    /// 다음 사진도 똑같이 겪을 실패(한도·저장소 없음·네트워크·세션)는 줄을 멈춘다. 사진 자체의 문제는 그 칸만.
    func testOnlySharedFailuresStopTheQueue() {
        for failure in [PhotoUploadFailure.rateLimited, .unavailable, .network, .signedOut, .storage] {
            XCTAssertTrue(PhotoUploadRules.stopsQueue(failure), "\(failure)")
        }
        for failure in [PhotoUploadFailure.tooLarge, .unreadable, .unsupportedType, .expired] {
            XCTAssertFalse(PhotoUploadRules.stopsQueue(failure), "\(failure)")
        }
    }

    /// 실패마다 문구가 있다(빈 글자가 아니다).
    func testEveryFailureHasAText() {
        let all: [PhotoUploadFailure] = [
            .unreadable, .tooLarge, .unsupportedType, .rateLimited, .unavailable, .signedOut, .expired,
            .storage, .network,
        ]
        for failure in all {
            XCTAssertFalse(PhotoUploadRules.text(failure).isEmpty, "\(failure)")
        }
    }

    /// 저장소로 가는 PUT — 서버가 준 헤더를 그대로 싣고, **`Authorization` 은 붙이지 않는다**.
    func testPutCarriesTheRequiredHeadersAndNoAuthorization() throws {
        let headers = ["Content-Type": "image/jpeg", "Content-Length": "34567"]
        let request = try XCTUnwrap(PhotoUploadRules.putRequest(
            uploadUrl: "http://localhost:9000/scenetrip-user-media/uploads/tmp/abc.jpg?X-Amz-Signature=1",
            requiredHeaders: headers
        ))
        XCTAssertEqual(request.httpMethod, "PUT")
        XCTAssertEqual(request.url?.host, "localhost")
        XCTAssertEqual(request.url?.query, "X-Amz-Signature=1")
        XCTAssertEqual(request.allHTTPHeaderFields, headers)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    /// 주소가 주소 꼴이 아니면 보내지 않는다.
    func testPutRejectsABrokenAddress() {
        XCTAssertNil(PhotoUploadRules.putRequest(uploadUrl: "", requiredHeaders: [:]))
        XCTAssertNil(PhotoUploadRules.putRequest(uploadUrl: "uploads/tmp/abc.jpg", requiredHeaders: [:]))
    }
}

/// 생성 클라이언트의 예약 → 실제 URLSession PUT 경계를 서버 없이 검증한다(MZ2AZ-387).
@MainActor
final class PhotoUploadTransportTests: XCTestCase {
    func testReservationFailuresNeverSendAStorageRequest() async {
        for (status, expected) in [(429, PhotoUploadFailure.rateLimited), (401, .signedOut)] {
            let (result, requests) = await exercise(apiStatus: status)
            XCTAssertEqual(result, .failure(expected))
            XCTAssertEqual(requests.map(\.httpMethod), ["POST"])
        }
    }

    func testRejectedPutDoesNotReturnAKeyOrRetryTheWrite() async {
        let (result, requests) = await exercise(storageStatus: 403)
        XCTAssertEqual(result, .failure(.storage))
        XCTAssertEqual(requests.map(\.httpMethod), ["POST", "PUT"])
        XCTAssertFalse(ReviewPhotoRules.canSave([.failed(.storage)]))
        XCTAssertTrue(PhotoUploadRules.retryable(.storage))
    }

    func testPutConnectionFailureStaysRetryableAndUnsavable() async {
        let (result, requests) = await exercise(storageError: .notConnectedToInternet)
        XCTAssertEqual(result, .failure(.network))
        XCTAssertEqual(requests.count, 2)
        XCTAssertFalse(ReviewPhotoRules.canSave([.failed(.network)]))
        XCTAssertTrue(PhotoUploadRules.retryable(.network))
    }

    func testSuccessfulPutReturnsTheKeyWithoutForwardingApiAuthorization() async throws {
        let (result, requests) = await exercise()
        XCTAssertEqual(result, .success("uploads/tmp/fixture.jpg"))
        let reserve = try XCTUnwrap(requests.first)
        let put = try XCTUnwrap(requests.last)
        XCTAssertEqual(reserve.url?.host, "api.example.invalid")
        XCTAssertEqual(reserve.value(forHTTPHeaderField: "Authorization"), "Bearer test-only")
        XCTAssertEqual(put.url?.host, "storage.example.invalid")
        XCTAssertEqual(put.httpMethod, "PUT")
        XCTAssertEqual(put.value(forHTTPHeaderField: "Content-Type"), "image/jpeg")
        XCTAssertNil(put.value(forHTTPHeaderField: "Authorization"))
    }

    private func exercise(
        apiStatus: Int = 200, storageStatus: Int = 204, storageError: URLError.Code? = nil
    ) async -> (Result<String, PhotoUploadFailure>, [URLRequest]) {
        let factory = SceneApiClientAPI.requestBuilderFactory
        let headers = SceneApiClientAPI.customHeaders
        let base = SceneApiClientAPI.basePath
        defer {
            SceneApiClientAPI.requestBuilderFactory = factory
            SceneApiClientAPI.customHeaders = headers
            SceneApiClientAPI.basePath = base
        }
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [UploadTransportProbe.self]
        let session = URLSession(configuration: config)
        defer { session.invalidateAndCancel() }
        UploadTransportProbe.reset(session, api: apiStatus, storage: storageStatus, error: storageError)
        SceneApiClientAPI.requestBuilderFactory = UploadProbeFactory()
        SceneApiClientAPI.customHeaders = ["Authorization": "Bearer test-only"]
        SceneApiClientAPI.basePath = "https://api.example.invalid/v1"
        let result = await PhotoUploader.upload(Data([1, 2, 3]), session: session)
        return (result, UploadTransportProbe.captured())
    }
}

private final class UploadProbeFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        UploadProbePlainBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        UploadProbeBuilder<T>.self
    }
}

private final class UploadProbeBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        UploadTransportProbe.session
    }
}

private final class UploadProbePlainBuilder<T>: URLSessionRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        UploadTransportProbe.session
    }
}

/// 모든 URL을 가로채며, 예상 밖 요청은 실패시킨다 — 실제 네트워크는 쓰지 않는다.
private final class UploadTransportProbe: URLProtocol, @unchecked Sendable {
    static var session = URLSession.shared
    private static let lock = NSLock()
    private static var apiStatus = 200
    private static var storageStatus = 204
    private static var storageError: URLError.Code?
    private static var requests: [URLRequest] = []

    static func reset(_ value: URLSession, api: Int, storage: Int, error: URLError.Code?) {
        lock.lock()
        defer { lock.unlock() }
        session = value
        apiStatus = api
        storageStatus = storage
        storageError = error
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
        Self.lock.lock()
        Self.requests.append(request)
        let (api, storage, error) = (Self.apiStatus, Self.storageStatus, Self.storageError)
        Self.lock.unlock()
        if request.httpMethod == "POST", request.url?.host == "api.example.invalid" {
            let ticket = #"""
            {"key":"uploads/tmp/fixture.jpg",
             "uploadUrl":"https://storage.example.invalid/fixture.jpg",
             "requiredHeaders":{"Content-Type":"image/jpeg"},
             "expiresAt":"2030-01-01T00:00:00Z"}
            """#
            let code = api == 401 ? "SIGN_IN_REQUIRED" : "RATE_LIMITED"
            reply(status: api, body: api == 200 ? ticket : #"{"code":"\#(code)"}"#)
        } else if request.httpMethod == "PUT", request.url?.host == "storage.example.invalid" {
            if let error {
                client?.urlProtocol(self, didFailWithError: URLError(error))
            } else {
                reply(status: storage, body: "")
            }
        } else {
            client?.urlProtocol(self, didFailWithError: URLError(.unsupportedURL))
        }
    }

    private func reply(status: Int, body: String) {
        guard let url = request.url,
              let response = HTTPURLResponse(
                  url: url, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"]
              ) else { return }
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
