import SceneApiClient
@testable import SceneTrip
import XCTest

final class UsageEventsTests: XCTestCase {
    @MainActor
    func testPendingGeneratedRequestReturnsWithoutWaitingForStatistics() async throws {
        let session = RetryFakeSession([.hang])
        let builder = try XCTUnwrap(UsageEventRequestBuilder.make(
            UsageEvent(type: .view, entityType: .content, entityId: 1),
            installId: UUID(), language: .en, token: nil, session: session
        ))
        let stopped = expectation(description: "시험 요청을 취소하면 한 번 완료")
        let task = builder.execute { _ in stopped.fulfill() }
        XCTAssertEqual(session.requests.count, 1, "통계 응답 없이 바로 화면 코드로 돌아온다")
        task.cancel()
        await fulfillment(of: [stopped], timeout: 2)
        XCTAssertEqual(session.requests.count, 1)
    }

    @MainActor
    func testGeneratedViewRequestsCarrySnapshotHeadersAndOnlyEntityFields() async throws {
        let priorFactory = SceneApiClientAPI.requestBuilderFactory
        let priorHeaders = SceneApiClientAPI.customHeaders
        defer {
            SceneApiClientAPI.requestBuilderFactory = priorFactory
            SceneApiClientAPI.customHeaders = priorHeaders
        }
        SceneApiClientAPI.requestBuilderFactory = AuthRequestBuilderFactory()
        let installId = UUID()
        for type in [UsageEntityType.content, .place, .poi] {
            let session = RetryFakeSession([.status(204)])
            let builder = try XCTUnwrap(UsageEventRequestBuilder.make(
                UsageEvent(type: .view, entityType: type, entityId: 73),
                installId: installId, language: .en, token: "unit-snapshot", session: session
            ))
            SceneApiClientAPI.customHeaders["Accept-Language"] = "ko"
            SceneApiClientAPI.customHeaders["Authorization"] = "Bearer changed-after-capture"
            _ = try await builder.execute()
            let request = try XCTUnwrap(session.requests.first)
            XCTAssertEqual(session.requests.count, 1)
            XCTAssertEqual(request.url?.path, "/v1/events")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "X-Install-Id"), installId.uuidString)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Accept-Language"), "en")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer unit-snapshot")
            XCTAssertEqual(request.timeoutInterval, 10)
            XCTAssertFalse(request.httpShouldHandleCookies)
            XCTAssertEqual(request.cachePolicy, .reloadIgnoringLocalCacheData)
            let body = try body(of: request)
            XCTAssertEqual(Set(body.keys), ["type", "entityType", "entityId"])
            XCTAssertEqual(body["type"] as? String, "view")
            XCTAssertEqual(body["entityType"] as? String, type.rawValue)
            XCTAssertEqual(body["entityId"] as? Int, 73)
        }
    }

    @MainActor
    func testGeneratedSearchRequestForGuestHasNoTokenOrEntity() async throws {
        let priorHeaders = SceneApiClientAPI.customHeaders
        defer { SceneApiClientAPI.customHeaders = priorHeaders }
        SceneApiClientAPI.customHeaders["Authorization"] = "Bearer unrelated-global-token"
        let session = RetryFakeSession([.status(204)])
        let builder = try XCTUnwrap(UsageEventRequestBuilder.make(
            UsageEvent(type: .search, query: "제주", resultCount: 0),
            installId: UUID(), language: .ko, token: nil, session: session
        ))
        _ = try await builder.execute()
        let request = try XCTUnwrap(session.requests.first)
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
        let body = try body(of: request)
        XCTAssertEqual(Set(body.keys), ["type", "query", "resultCount"])
        XCTAssertEqual(body["query"] as? String, "제주")
        XCTAssertEqual(body["resultCount"] as? Int, 0)
    }

    @MainActor
    func testFailedGeneratedRequestsAreSentOnceAndDoNotChangeAuthOrAnalytics() async throws {
        let priorFactory = SceneApiClientAPI.requestBuilderFactory
        let priorSink = AppAnalytics.sink
        defer {
            SceneApiClientAPI.requestBuilderFactory = priorFactory
            AppAnalytics.sink = priorSink
        }
        SceneApiClientAPI.requestBuilderFactory = AuthRequestBuilderFactory()
        let recorder = UsageAnalyticsRecorder()
        AppAnalytics.sink = recorder
        let priorAccess = AuthTokens.accessToken
        let priorRefresh = AuthTokens.refreshToken
        let priorEpoch = AuthStore.shared.epoch
        let priorSignIn = AuthStore.shared.showingSignIn
        let priorMessage = AuthStore.shared.message
        let failures: [FakeReply] = [
            .status(503), .status(401, code: "ACCESS_TOKEN_EXPIRED"),
            .status(429, code: "RATE_LIMITED", headers: ["Retry-After": "300"]),
            .error(.cannotConnectToHost), .error(.networkConnectionLost), .error(.timedOut),
        ]
        for failure in failures {
            let session = RetryFakeSession([failure, .status(204)])
            let builder = try XCTUnwrap(UsageEventRequestBuilder.make(
                UsageEvent(type: .view, entityType: .poi, entityId: 2),
                installId: UUID(), language: .en, token: "unit-snapshot", session: session
            ))
            do {
                _ = try await builder.execute()
                XCTFail("실패 응답을 성공으로 처리했다")
            } catch {
                XCTAssertEqual(session.requests.count, 1, "\(failure)")
            }
        }
        XCTAssertEqual(AuthTokens.accessToken, priorAccess)
        XCTAssertEqual(AuthTokens.refreshToken, priorRefresh)
        XCTAssertEqual(AuthStore.shared.epoch, priorEpoch)
        XCTAssertEqual(AuthStore.shared.showingSignIn, priorSignIn)
        XCTAssertEqual(AuthStore.shared.message, priorMessage)
        XCTAssertEqual(recorder.events, [])
    }

    @MainActor
    func testOpeningCardsRecordsResolvedTargetAndLeavesPersonalPinsEmpty() {
        let priorAnalytics = AppAnalytics.sink
        let priorUsage = UsageEvents.sink
        defer {
            AppAnalytics.sink = priorAnalytics
            UsageEvents.sink = priorUsage
        }
        let analytics = UsageAnalyticsRecorder()
        let usage = UsageRecorder()
        AppAnalytics.sink = analytics
        UsageEvents.sink = usage
        UsageEvents.opened(.poi(73))
        UsageEvents.opened(.place(4)) // 촬영지와 연결된 편의시설의 대표 대상
        UsageEvents.opened(nil)
        UsageEvents.view(.content, id: 9)
        UsageEvents.view(.poi, id: 0)
        XCTAssertEqual(analytics.events, [.viewPoi(poiId: 73), .viewPlace(placeId: 4)])
        XCTAssertEqual(analytics.events[0].parameters, ["poi_id": Int64(73)])
        XCTAssertEqual(usage.events.map(\.entityType), [.poi, .place, .content])
        XCTAssertEqual(usage.events.map(\.entityId), [73, 4, 9])
        XCTAssertTrue(usage.events.allSatisfy { $0.type == .view && $0.query == nil && $0.resultCount == nil })
    }

    private func body(of request: URLRequest) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.httpBody)) as? [String: Any])
    }

    @MainActor
    func testDataRefreshAndRetryDoNotCountAsSearchCommit() async {
        let priorSink = AppAnalytics.sink
        let priorFactory = SceneApiClientAPI.requestBuilderFactory
        defer {
            AppAnalytics.sink = priorSink
            SceneApiClientAPI.requestBuilderFactory = priorFactory
        }
        let sink = UsageAnalyticsRecorder()
        AppAnalytics.sink = sink
        SceneApiClientAPI.requestBuilderFactory = UsageRejectingFactory()
        let data = SceneData()
        data.search("제주")
        await waitForFailure(data)
        data.retry()
        await waitForFailure(data)
        XCTAssertEqual(sink.events, [], "검색 확정만 기록하고 데이터 재조회·재시도는 기록하지 않는다")
    }

    @MainActor
    private func waitForFailure(_ data: SceneData) async {
        for _ in 0 ..< 100 {
            if case .failed = data.phase {
                return
            }
            await Task.yield()
        }
        XCTFail("가짜 생성 요청의 실패 응답이 도착하지 않았다")
    }
}

@MainActor
private final class UsageRecorder: UsageEventSink {
    var events: [UsageEvent] = []
    func send(_ event: UsageEvent) {
        events.append(event)
    }
}

private final class UsageAnalyticsRecorder: AnalyticsSink {
    var events: [AppEvent] = []
    func log(_ event: AppEvent) {
        events.append(event)
    }

    func setUserProperty(_: String?, for _: String) {}
}

struct UsageRejectingFactory: RequestBuilderFactory {
    func getNonDecodableBuilder<T>() -> RequestBuilder<T>.Type {
        UsageRejectingBuilder<T>.self
    }

    func getBuilder<T: Decodable>() -> RequestBuilder<T>.Type {
        UsageRejectingBuilder<T>.self
    }
}

private final class UsageRejectingBuilder<T>: RequestBuilder<T> {
    override func execute(
        _: DispatchQueue,
        _ completion: @escaping (Swift.Result<Response<T>, ErrorResponse>) -> Void
    ) -> RequestTask {
        completion(.failure(.error(503, nil, nil, URLError(.badServerResponse))))
        return requestTask
    }
}
