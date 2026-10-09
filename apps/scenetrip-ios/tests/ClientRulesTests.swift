import SceneApiClient
@testable import SceneTrip
import XCTest

/// **클라이언트에만 있는 규칙**을 고정한다.
///
/// 검색 자체(§3-2 표)와 자동완성 랭킹(§3-3)은 여기서 검사하지 않는다 — 서버가
/// 하는 일이기 때문이다. `GET /contents` 와 `GET /places` 에 같은 `q` 를 넣으면 두 탭이
/// 채워지고, 그 대칭은 서버 쪽 통합 테스트(`SearchSymmetryIntegrationTest`)가 지킨다.
/// 같은 규칙을 앱에서 한 번 더 구현하면 iOS 와 Android 가 갈린다 (계획서 §1).
///
/// 남는 것은 둘이다 — 칩 매핑(§3-5)과 오류 화면이 읽는 값(§3-6).
final class ClientRulesTests: XCTestCase {
    // MARK: §3-5 카테고리 칩

    func testChipMappingCoversKnownTypes() {
        XCTAssertEqual(CategoryChip.of("카페"), "음식점·카페")
        XCTAssertEqual(CategoryChip.of("음식점"), "음식점·카페")
        XCTAssertEqual(CategoryChip.of("한옥마을"), "명소·자연")
        XCTAssertEqual(CategoryChip.of("역/교통"), "거리·다리")
        XCTAssertEqual(CategoryChip.of("호텔"), "건물·시설")
    }

    /// 표에 없는 값이 언제든 온다(수집 원본의 분류가 그대로 남은 행). 버리지 않고 「기타」 로 묶는다 —
    /// 버리면 그 장소가 어느 칩에서도 안 보인다. 전에는 「건물·시설」 로 떨어뜨렸는데, 「보증보험」·「피아노」 가
    /// 건물은 아니다(MZ2AZ-372).
    func testUnknownTypeFallsBackInsteadOfDisappearing() {
        XCTAssertEqual(CategoryChip.of("처음보는유형"), "기타")
        XCTAssertEqual(CategoryChip.of(nil), "기타")
    }

    /// 칩 목록의 첫 항목은 "전체" 이고, 나머지는 묶음 순서를 따른다 — 끝이 「기타」 다.
    func testChipNamesStartWithAll() {
        XCTAssertEqual(CategoryChip.names.first, CategoryChip.all)
        XCTAssertEqual(CategoryChip.names.count, PlaceType.Group.allCases.count + 1)
        XCTAssertEqual(CategoryChip.names.last, "기타")
    }

    // MARK: §3-6 오류 화면이 읽는 값

    /// 「다시 시도」 는 다시 누르면 달라질 수 있는 것에만 — 서버 사정(500·502·503·504)과 분당 한도(429).
    /// `400`·`409` 에 재시도를 걸면 같은 요청을 그대로 다시 보내 같은 오류를 받는다.
    func testOnlyServerErrorsAreRetryable() {
        XCTAssertTrue(ApiFailure(statusCode: 500, traceId: "abc").isRetryable)
        // 전에는 503·429 에 단추가 없었다 — 「요청을 처리하지 못했습니다」 만 뜨고 다시 해 볼 길이 없었다 (MZ2AZ-366).
        for status in [429, 502, 503, 504] {
            XCTAssertTrue(ApiFailure(statusCode: status, traceId: nil).isRetryable, "\(status)")
        }
        XCTAssertFalse(ApiFailure(statusCode: 400, traceId: nil).isRetryable)
        XCTAssertFalse(ApiFailure(statusCode: 404, traceId: nil).isRetryable)
        XCTAssertFalse(ApiFailure(statusCode: 409, traceId: nil).isRetryable)
    }

    /// 생성 클라이언트가 연결 실패에 쓰는 음수 코드(-1, -2)를 HTTP 코드로 넘기면
    /// 안 된다. 넘기면 서버가 꺼져 있을 때 재시도 버튼이 사라진다.
    func testNegativeCodesAreTreatedAsUnreachable() {
        XCTAssertNil(ApiFailure(ErrorResponse.error(-1, nil, nil, URLError(.cannotConnectToHost))).statusCode)
        XCTAssertNil(ApiFailure(ErrorResponse.error(-2, nil, nil, URLError(.badServerResponse))).statusCode)
        XCTAssertTrue(ApiFailure(ErrorResponse.error(-1, nil, nil, URLError(.timedOut))).isRetryable)
    }

    /// 기기가 오프라인이면 서버가 아니라 인터넷 연결을 말한다 (MZ2AZ-366). 다시 시도는 여전히 된다.
    func testOfflineIsToldApartFromAnUnreachableServer() {
        let offline = ApiFailure(ErrorResponse.error(-1, nil, nil, URLError(.notConnectedToInternet)))
        XCTAssertTrue(offline.isOffline)
        XCTAssertTrue(offline.isRetryable)
        XCTAssertNotEqual(offline.message, ApiFailure(statusCode: nil, traceId: nil).message)
        XCTAssertFalse(ApiFailure(ErrorResponse.error(-1, nil, nil, URLError(.cannotConnectToHost))).isOffline)
        XCTAssertFalse(ApiFailure(ErrorResponse.error(503, nil, nil, URLError(.notConnectedToInternet))).isOffline)
    }

    /// 한도와 잠시 장애는 저마다의 말이 있다 — 「요청을 처리하지 못했습니다」 로 뭉뚱그리지 않는다.
    func testRateLimitAndOutageHaveTheirOwnWords() {
        let generic = ApiFailure(statusCode: 400, traceId: nil).message
        XCTAssertNotEqual(ApiFailure(statusCode: 429, traceId: nil).message, generic)
        XCTAssertEqual(
            ApiFailure(statusCode: 503, traceId: nil).message, ApiFailure(statusCode: 500, traceId: nil).message
        )
    }

    /// 서버에 닿지도 못한 경우(코드 없음)도 재시도 대상이다 — 앱이 고칠 것이 없다는
    /// 점에서 `500` 과 성격이 같다. 로컬 클러스터가 안 떠 있을 때 이 경로로 온다.
    func testUnreachableServerIsRetryable() {
        XCTAssertTrue(ApiFailure(statusCode: nil, traceId: nil).isRetryable)
    }

    /// `traceId` 는 `500` 응답에만 실린다. 화면은 "없으면 숨긴다" 가 아니라 **있을 때만
    /// 그린다** — 다른 코드에서는 키 자체가 응답에서 빠진다.
    func testTraceIdIsCarriedOnlyWhenPresent() {
        XCTAssertEqual(ApiFailure(statusCode: 500, traceId: "trace-1").traceId, "trace-1")
        XCTAssertNil(ApiFailure(statusCode: 404, traceId: nil).traceId)
    }
}
