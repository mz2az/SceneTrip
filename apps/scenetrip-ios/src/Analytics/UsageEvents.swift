import Foundation
import SceneApiClient

/// 인기도 집계용 기록. Firebase·메타와 별개이며 원문 검색어는 우리 서버에만 보낸다(MZ2AZ-408).
@MainActor
protocol UsageEventSink {
    func send(_ event: UsageEvent)
}

@MainActor
enum UsageEvents {
    static var sink: UsageEventSink = ServerUsageEventSink()

    static func record(_ event: UsageEvent) {
        sink.send(event)
    }

    static func view(_ type: UsageEntityType, id: Int64) {
        guard id > 0 else { return }
        record(UsageEvent(type: .view, entityType: type, entityId: id))
    }

    /// 실제 정보 카드가 열린 자리만 부른다. 연결된 POI는 대표 촬영지로, 개인 핀은 기록하지 않는다.
    static func opened(_ subject: ReviewSubject?) {
        switch subject {
        case let .place(id):
            AppAnalytics.log(.viewPlace(placeId: id))
            view(.place, id: id)
        case let .poi(id):
            AppAnalytics.log(.viewPoi(poiId: id))
            view(.poi, id: id)
        case nil:
            break
        }
    }
}

@MainActor
private struct ServerUsageEventSink: UsageEventSink {
    func send(_ event: UsageEvent) {
        // 값을 지금 캡처한다. 응답을 기다리는 동안 로그인·언어가 바뀌어도 원래 기록의 머리말이다.
        guard let request = UsageEventRequestBuilder.make(
            event, installId: InstallIdentity.current, language: AppLocale.lang,
            token: AuthTokens.accessToken
        ) else { return }
        // 계약의 「보내고 잊는다」. 실패는 의도적으로 버리며 화면·세션·재전송 큐를 건드리지 않는다.
        _ = request.execute(.global(qos: .utility)) { _ in }
    }
}

/// 생성 요청만 전송하는 전용 빌더. AuthIntercept·RetryingSession을 실행하지 않는다.
final class UsageEventRequestBuilder: URLSessionRequestBuilder<Void> {
    static let timeout: TimeInterval = 10
    private static let plainSession: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.waitsForConnectivity = false
        config.timeoutIntervalForRequest = timeout
        config.timeoutIntervalForResource = timeout
        return URLSession(configuration: config)
    }()

    private var session: URLSessionProtocol = plainSession
    private var snapshotHeaders: [String: String] = [:]

    required init(
        method: String, URLString: String, parameters: [String: Any]?,
        headers: [String: String] = [:], requiresAuthentication: Bool
    ) {
        super.init(
            method: method, URLString: URLString, parameters: parameters,
            headers: headers, requiresAuthentication: requiresAuthentication
        )
        snapshotHeaders = headers
    }

    static func make(
        _ event: UsageEvent, installId: UUID, language: Lang, token: String?,
        session: URLSessionProtocol? = nil
    ) -> UsageEventRequestBuilder? {
        guard let generated = EventsAPI.recordEventWithRequestBuilder(
            xInstallId: installId, usageEvent: event, acceptLanguage: language
        ) as? URLSessionRequestBuilder<Void> else { return nil }
        let request = UsageEventRequestBuilder(
            method: generated.method, URLString: generated.URLString,
            parameters: generated.parameters, headers: generated.buildHeaders(),
            requiresAuthentication: false
        )
        request.snapshotHeaders["Accept-Language"] = language.rawValue
        request.snapshotHeaders["Authorization"] = token.map { "Bearer \($0)" }
        if let session {
            request.session = session
        }
        return request
    }

    override func createURLSession() -> URLSessionProtocol {
        session
    }

    override func buildHeaders() -> [String: String] {
        snapshotHeaders
    }

    override func addCredential() {}

    override func createURLRequest(
        urlSession: URLSessionProtocol, method: HTTPMethod, encoding: ParameterEncoding,
        headers: [String: String]
    ) throws -> URLRequest {
        var request = try super.createURLRequest(
            urlSession: urlSession, method: method, encoding: encoding, headers: headers
        )
        request.timeoutInterval = Self.timeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.httpShouldHandleCookies = false
        return request
    }
}
