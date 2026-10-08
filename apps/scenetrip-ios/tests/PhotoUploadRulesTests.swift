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
