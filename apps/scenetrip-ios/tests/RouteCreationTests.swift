import SceneApiClient
@testable import SceneTrip
import XCTest

/// 생성 클라이언트·재시도·실제 동의 게이트를 모두 지나고, 마지막 전송만 가짜로 바꾼다.
@MainActor
final class RouteCreationTests: XCTestCase {
    func testManualCreationWithoutEitherConsentSendsNoCoordinates() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            let draft = store.emptyCourse(span: .sameDay, startDate: nil)
            XCTAssertTrue(transport.requests.isEmpty, "마법사는 초안만 열고 편집 완료 때 저장한다")
            let saved = await store.save(draft)
            XCTAssertNotNil(saved)
            XCTAssertNil(store.saveFailure)
            let request = try XCTUnwrap(transport.requests.first { $0.httpMethod == "POST" })
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.path, "/v1/courses")
            XCTAssertNotNil(request.value(forHTTPHeaderField: "X-Install-Id"))
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            let body = try json(request)
            XCTAssertEqual(body["origin"] as? String, "self")
            XCTAssertEqual(body["dayCount"] as? Int, 1)
            XCTAssertNil(body["latitude"])
            XCTAssertNil(body["longitude"])
            XCTAssertTrue(transport.requests.contains { $0.httpMethod == "PUT" })
        }
    }

    func testAICourseSaveWithoutLocationConsentStillSendsCreation() async throws {
        try await withTransport(aiAllowed: true) { transport in
            let store = RouteStore()
            var draft = store.emptyCourse(span: .sameDay, startDate: nil)
            draft.madeByAI = true
            let saved = await store.save(draft)
            XCTAssertNotNil(saved)
            let request = try XCTUnwrap(transport.requests.first { $0.httpMethod == "POST" })
            let body = try json(request)
            XCTAssertEqual(body["origin"] as? String, "ai")
            XCTAssertNil(body["latitude"])
            XCTAssertNil(body["longitude"])
        }
    }

    func testPreviousReadFailureDoesNotBecomeDraftSaveFailureOrBlockCreation() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            transport.readStatus = 400
            await store.refresh()
            XCTAssertEqual(store.failure?.statusCode, 400)
            let draft = store.emptyCourse(span: .sameDay, startDate: nil)
            XCTAssertNil(store.saveFailure, "편집 화면 진입만으로 저장 오류가 뜨면 안 된다")
            let saved = await store.save(draft)
            XCTAssertNotNil(saved)
            XCTAssertEqual(store.failure?.statusCode, 400, "저장 뒤 조회는 별도로 실패했다")
            XCTAssertNil(store.saveFailure, "저장 성공을 조회 실패로 덮어쓰지 않는다")
            XCTAssertEqual(transport.requests.filter { $0.httpMethod == "POST" }.count, 1)
        }
    }

    func testActualSaveFailureSurvivesSuccessfulReadAndCanBeDismissed() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            transport.writeStatus = 400
            let saved = await store.save(store.emptyCourse(span: .sameDay, startDate: nil))
            XCTAssertNil(saved)
            XCTAssertEqual(store.saveFailure?.statusCode, 400)
            await store.refreshCourses()
            XCTAssertNil(store.failure)
            XCTAssertEqual(store.saveFailure?.statusCode, 400)
            store.clearFailure()
            XCTAssertNil(store.saveFailure)
        }
    }

    func testProgressWriteFailureSurvivesItsRecoveryRefresh() async throws {
        try await withTransport { transport in
            transport.writeStatus = 400
            let store = RouteStore()
            var course = store.emptyCourse(span: .sameDay, startDate: nil)
            course.serverId = 42
            await store.setRunning(course, true)
            XCTAssertNil(store.failure, "복구 조회는 성공했다")
            XCTAssertEqual(store.saveFailure?.statusCode, 400, "상태 변경 실패는 사용자에게 남긴다")
        }
    }

    func testDetailFailureDoesNotOpenEditorAndSummarySaveIsRefused() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            let opening = RouteCourseOpening()
            let summary = summaryCourse()
            transport.readStatus = 400
            await opening.open(summary, using: store)
            XCTAssertNil(opening.editing)
            XCTAssertEqual(opening.failedCourse?.serverId, summary.serverId)
            XCTAssertFalse(opening.isLoading)
            XCTAssertNil(store.saveFailure, "상세 조회 오류는 목록에서 안내한다")

            let requestCount = transport.requests.count
            let saved = await store.save(summary)
            XCTAssertNil(saved)
            XCTAssertEqual(store.saveFailure, .courseDetailNotLoaded)
            XCTAssertEqual(transport.requests.count, requestCount, "요약으로 PUT을 보내면 기존 장소가 삭제된다")
        }
    }

    func testRetryAfterDetailFailureOpensFullCourseAndPreservesStopsOnSave() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            let opening = RouteCourseOpening()
            let summary = summaryCourse()
            transport.readStatus = 400
            await opening.open(summary, using: store)
            transport.readStatus = 200
            await store.refresh()
            XCTAssertEqual(opening.failedCourse?.serverId, summary.serverId, "다른 조회 성공이 재시도를 지우면 안 된다")
            await opening.retry(using: store)
            let course = try XCTUnwrap(opening.editing)
            XCTAssertNil(opening.failedCourse)
            XCTAssertTrue(course.canSave)
            XCTAssertEqual(course.serverId, summary.serverId)
            XCTAssertEqual(course.stops.map(\.serverItemId), [101])
            XCTAssertEqual(transport.requests.filter { $0.url?.path == "/v1/courses/42" }.count, 2)

            // 같은 ID의 상세를 받았어도 예전 목록 사본에 저장 권한이 생기지 않는다.
            let refused = await store.save(summary)
            XCTAssertNil(refused)
            XCTAssertFalse(transport.requests.contains { $0.httpMethod == "PUT" })
            let saved = await store.save(course)
            XCTAssertNotNil(saved)
            XCTAssertNil(store.saveFailure)
            let request = try XCTUnwrap(transport.requests.first { $0.httpMethod == "PUT" })
            let days = try XCTUnwrap(json(request)["days"] as? [[String: Any]])
            let items = try XCTUnwrap(days.first?["items"] as? [[String: Any]])
            XCTAssertEqual(items.first?["id"] as? Int, 101)
            XCTAssertEqual(items.first?["placeId"] as? Int, 7)
        }
    }

    func testSummarySaveIsRefusedEvenWithoutAnEarlierDetailFailure() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            let summary = summaryCourse()
            XCTAssertFalse(summary.canSave, "편집기 저장 버튼도 같은 조건으로 잠근다")
            let revised = RouteGuidePlan.revising(summary, with: RouteGuidePlan.plan(from: summary))
            XCTAssertFalse(revised.canSave, "AI 일정 변경으로 상세 미조회 사본을 저장 가능하게 만들지 않는다")
            let saved = await store.save(summary)
            XCTAssertNil(saved)
            XCTAssertEqual(store.saveFailure, .courseDetailNotLoaded)
            XCTAssertTrue(transport.requests.isEmpty)
        }
    }

    func testLoadedCourseCanIntentionallySaveWithoutStops() async throws {
        try await withTransport { transport in
            let store = RouteStore()
            let loaded = await store.detail(summaryCourse())
            var course = try XCTUnwrap(loaded)
            course = RouteGuidePlan.revising(course, with: RouteGuidePlan.plan(from: course))
            XCTAssertTrue(course.canSave, "AI 일정 변경 뒤에도 상세 조회 기록을 유지한다")
            course.days[0].stops.removeAll()
            XCTAssertTrue(course.canSave, "상세를 받은 뒤 모든 장소를 빼는 편집은 허용한다")
            let saved = await store.save(course)
            XCTAssertNotNil(saved)
            let request = try XCTUnwrap(transport.requests.first { $0.httpMethod == "PUT" })
            let days = try XCTUnwrap(json(request)["days"] as? [[String: Any]])
            XCTAssertEqual((days.first?["items"] as? [Any])?.count, 0)
        }
    }

    private func summaryCourse() -> RouteCourse {
        RouteBridge.course(from: CourseSummary(
            id: 42, title: "내 코스", dayCount: 1, status: .upcoming, origin: ._self,
            placeCount: 1, createdAt: Date(), updatedAt: Date()
        ))
    }

    func testAIPlanStripsOptionalDeviceCoordinatesUnlessLocationConsentIsAllowed() async throws {
        for allowed in [false, true] {
            try await withTransport(aiAllowed: true, locationAllowed: allowed) { transport in
                let request = try await capture(GuideAPI.planWithGuideWithRequestBuilder(
                    guidePlanRequest: GuidePlanRequest(
                        titles: ["도깨비"], days: 1, pace: .packed, latitude: 37.5, longitude: 127
                    )
                ), transport: transport)
                let body = try json(request)
                XCTAssertEqual(body["latitude"] as? Double, allowed ? 37.5 : nil)
                XCTAssertEqual(body["longitude"] as? Double, allowed ? 127 : nil)
                XCTAssertEqual(body["titles"] as? [String], ["도깨비"])
                XCTAssertEqual(body["days"] as? Int, 1)
            }
        }
    }

    func testNonLocationWritesPassBothConsentGates() async throws {
        try await withTransport { transport in
            let cart = try await capture(CartAPI.addCartItemWithRequestBuilder(
                xInstallId: UUID(), cartItemCreate: CartItemCreate(placeId: 7)
            ), transport: transport)
            XCTAssertEqual(try json(cart)["placeId"] as? Int, 7)
            for builder in [
                ReviewsAPI.putMyPlaceReviewWithRequestBuilder(
                    placeId: 7, reviewInput: ReviewInput(rating: 5, photoKeys: [])
                ),
                ReviewsAPI.putMyPoiReviewWithRequestBuilder(
                    poiId: 8, reviewInput: ReviewInput(rating: 5, photoKeys: [])
                ),
            ] {
                let request = try await capture(builder, transport: transport)
                XCTAssertEqual(try json(request)["rating"] as? Int, 5)
            }
            let post = try await capture(PostsAPI.createPostWithRequestBuilder(
                tripPostCreate: TripPostCreate(title: "여행", body: "후기", photoKeys: [])
            ), transport: transport)
            XCTAssertEqual(try json(post)["body"] as? String, "후기")
        }
    }

    func testUserSelectedViewportPassesBothConsentGatesAndKeepsBounds() async throws {
        try await withTransport { transport in
            let bbox = "126,37,127,38"
            let places = try await capture(PlacesAPI.listPlacesWithRequestBuilder(bbox: bbox)
                .coordinateSource(.userSelected), transport: transport)
            let pois = try await capture(PoisAPI.listPoisWithRequestBuilder(bbox: bbox, lat: 37.5, lng: 127)
                .coordinateSource(.userSelected), transport: transport)
            for request in [places, pois] {
                let query = try XCTUnwrap(URLComponents(url: XCTUnwrap(request.url), resolvingAgainstBaseURL: false))
                XCTAssertEqual(query.queryItems?.first { $0.name == "bbox" }?.value, bbox)
                XCTAssertNil(request.value(forHTTPHeaderField: LocationCoordinateSource.header))
            }
        }
    }

    private func json(_ request: URLRequest) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
    }

    /// 다른 API의 응답 해석은 범위 밖이므로 가짜 서버의 400까지 도달했는지만 확인한다.
    private func capture(
        _ builder: RequestBuilder<some Any>, transport: CourseRegressionTransport
    ) async throws -> URLRequest {
        transport.readStatus = 400
        let count = transport.requests.count
        do {
            _ = try await builder.execute()
            XCTFail("가짜 서버의 400을 성공으로 처리했다")
        } catch {
            guard case let ErrorResponse.error(status, _, _, _) = error else {
                XCTFail("예상하지 못한 오류: \(error)")
                throw error
            }
            XCTAssertEqual(status, 400, "로컬 동의 차단이 아니라 가짜 서버 응답이어야 한다")
        }
        XCTAssertEqual(transport.requests.count, count + 1)
        return try XCTUnwrap(transport.requests.dropFirst(count).first)
    }

    private func withTransport(
        aiAllowed: Bool = false, locationAllowed: Bool = false,
        action: (CourseRegressionTransport) async throws -> Void
    ) async throws {
        let factory = SceneApiClientAPI.requestBuilderFactory
        let basePath = SceneApiClientAPI.basePath
        let headers = SceneApiClientAPI.customHeaders
        let previous = CourseRegressionBuilderSession.current
        defer {
            SceneApiClientAPI.requestBuilderFactory = factory
            SceneApiClientAPI.basePath = basePath
            SceneApiClientAPI.customHeaders = headers
            CourseRegressionBuilderSession.current = previous
        }
        let transport = CourseRegressionTransport()
        var environment = RetryFakeClock().environment()
        environment.aiConsent = AIConsentStore(storage: MemoryAIConsentStorage())
        environment.locationConsent = LocationConsentStore(storage: MemoryAIConsentStorage())
        if aiAllowed {
            environment.aiConsent.allow()
        }
        if locationAllowed {
            environment.locationConsent.allow()
        }
        CourseRegressionBuilderSession.current = RetryingSession(
            inner: transport, environment: environment, ledger: RateLimitLedger()
        )
        SceneApiClientAPI.basePath = "https://example.invalid/v1"
        SceneApiClientAPI.customHeaders = [:]
        SceneApiClientAPI.requestBuilderFactory = CourseRegressionFactory()
        try await action(transport)
    }
}

private enum CourseRegressionBuilderSession {
    static var current: URLSessionProtocol = RetryFakeSession([])
}

private struct CourseRegressionFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        CourseRegressionPlainBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        CourseRegressionBuilder<T>.self
    }
}

private final class CourseRegressionBuilder<T: Decodable>: URLSessionDecodableRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        CourseRegressionBuilderSession.current
    }
}

private final class CourseRegressionPlainBuilder<T>: URLSessionRequestBuilder<T> {
    override func createURLSession() -> URLSessionProtocol {
        CourseRegressionBuilderSession.current
    }
}

private final class CourseRegressionTransport: URLSessionProtocol, @unchecked Sendable {
    var requests: [URLRequest] = []
    var readStatus = 200
    var writeStatus = 200

    func dataTaskFromProtocol(
        with request: URLRequest,
        completionHandler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void
    ) -> URLSessionDataTaskProtocol {
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? ""
        let courseWrite = method != "GET"
            && ["/v1/courses", "/v1/courses/42", "/v1/courses/42/progress"].contains(path)
        let status = method == "GET" ? readStatus : (courseWrite ? writeStatus : 400)
        let body = if courseWrite {
            #"""
            {"id":42,"title":"내 코스","dayCount":1,"status":"upcoming","origin":"self","placeCount":0,
             "createdAt":"2026-10-16T00:00:00Z","updatedAt":"2026-10-16T00:00:00Z",
             "days":[{"dayNumber":1,"items":[],"dwellMinutes":0,"travelMinutes":0,
                      "totalMinutes":0,"travelBasis":"straight-line"}]}
            """#
        } else if path == "/v1/courses/42" {
            #"""
            {"id":42,"title":"내 코스","dayCount":1,"status":"upcoming","origin":"self","placeCount":1,
             "createdAt":"2026-10-16T00:00:00Z","updatedAt":"2026-10-16T00:00:00Z",
             "days":[{"dayNumber":1,"items":[{"id":101,"source":"place","placeId":7,"name":"촬영지",
                      "latitude":37.5,"longitude":127,"dwellMinutes":30}],"dwellMinutes":30,
                      "travelMinutes":0,"totalMinutes":30,"travelBasis":"straight-line"}]}
            """#
        } else {
            #"{"items":[],"totalCount":0,"total":0,"limit":200,"offset":0}"#
        }
        return CourseRegressionTask {
            self.requests.append(request)
            completionHandler(Data(body.utf8), HTTPURLResponse(
                url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil
            ), nil)
        }
    }
}

private final class CourseRegressionTask: URLSessionDataTaskProtocol {
    let taskIdentifier = 1
    let progress = Progress()
    private var action: (() -> Void)?

    init(action: @escaping () -> Void) {
        self.action = action
    }

    func resume() {
        let run = action
        action = nil
        run?()
    }

    func cancel() {
        action = nil
    }
}
