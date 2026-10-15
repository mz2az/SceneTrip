import Combine
import Foundation
import SceneApiClient

/// 좌표값이 같아도 기기에서 얻었는지, 사용자가 지도·목적지를 골랐는지는 다르다.
enum LocationCoordinateSource: String, Sendable {
    case device
    case userSelected

    /// 재시도·인증 재전송까지 유지하는 앱 내부 표식. 실제 전송 직전에 제거한다.
    static let header = "X-SceneTrip-Coordinate-Source"
}

extension RequestBuilder {
    @discardableResult
    func coordinateSource(_ source: LocationCoordinateSource) -> Self {
        _ = addHeader(name: LocationCoordinateSource.header, value: source.rawValue)
        return self
    }
}

extension AIConsentGate {
    func requestLocation(for source: LocationCoordinateSource, _ action: @escaping () -> Void) {
        if source == .device {
            request(action)
        } else {
            action()
        }
    }
}

/// iOS 권한과 별개인 위치 서비스 동의. AI 동의와 저장·철회·전송 잠금을 공유한다.
final class LocationConsentStore: VersionedConsentStore, @unchecked Sendable {
    static let shared = LocationConsentStore(storage: UserDefaults.standard)
    static let key = "scenetrip.locationConsentVersion"
    static let currentVersion = 1

    init(storage: AIConsentStorage, version: Int = currentVersion) {
        super.init(storage: storage, key: Self.key, version: version)
    }

    /// 선택 위치인 코스 생성은 좌표 없이 계속하고, 필수 위치 요청은 전송하지 않는다.
    /// 지도·질문의 출처가 생략됐으면 기기 위치로 취급해 우회를 막는다.
    func requestForSending(_ request: URLRequest) throws -> URLRequest {
        guard !isAllowed else { return request }
        let path = request.url?.path ?? ""
        if path.contains("/navigation/") {
            throw LocationConsentRequired()
        }
        let userSelected = request.value(forHTTPHeaderField: LocationCoordinateSource.header)
            == LocationCoordinateSource.userSelected.rawValue
        let query = request.url.flatMap { URLComponents(url: $0, resolvingAgainstBaseURL: false)?.queryItems } ?? []
        let mapCoordinates = query.contains { ["bbox", "lat", "lng"].contains($0.name) }
        if !userSelected, mapCoordinates, ["/places", "/pois"].contains(where: { path.hasSuffix($0) }) {
            throw LocationConsentRequired()
        }
        guard ["/guide/chat", "/guide/plan"].contains(where: { path.hasSuffix($0) }),
              let data = request.httpBody,
              var body = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              body["latitude"] != nil || body["longitude"] != nil
        else { return request }
        if path.hasSuffix("/guide/chat") {
            guard userSelected else { throw LocationConsentRequired() }
            return request
        }
        body.removeValue(forKey: "latitude")
        body.removeValue(forKey: "longitude")
        var safe = request
        safe.httpBody = try JSONSerialization.data(withJSONObject: body, options: [.sortedKeys])
        safe.setValue(nil, forHTTPHeaderField: "Content-Length")
        return safe
    }

    /// 좌표 제거와 전송 시작 사이에 철회가 끼어들지 않게 같은 잠금으로 처리한다.
    func start(_ request: URLRequest, action: (URLRequest) -> Void) throws {
        try withConsentLock { try action(requestForSending(request)) }
    }
}

struct LocationConsentRequired: Error, Equatable {}

/// 사용자 동작을 식별해 GPS 갱신·중복 응답에서 다시 표시하지 않는다.
@MainActor
final class KakaoDirectionsNotice: ObservableObject {
    @Published private(set) var visibleRequest: UUID?
    private var notified: Set<UUID> = []

    @discardableResult
    func beginRequest() -> UUID {
        let id = UUID()
        visibleRequest = nil
        return id
    }

    func succeeded(_ id: UUID) {
        guard notified.insert(id).inserted else { return }
        visibleRequest = id
    }

    func hide(_ id: UUID) {
        if visibleRequest == id {
            visibleRequest = nil
        }
    }
}

/// 최초 GPS 수신을 포함해 한 사용자 요청에서 동의 시트는 한 번만 연다.
@MainActor
final class LocationDirectionsConsent: ObservableObject {
    let gate: AIConsentGate
    let notice = KakaoDirectionsNotice()
    private let store: LocationConsentStore
    private var asked = false
    private(set) var requestId = UUID()

    init(store: LocationConsentStore = .shared) {
        self.store = store
        gate = AIConsentGate(store: store)
    }

    var isAllowed: Bool {
        store.isAllowed
    }

    func beginRequest() {
        gate.cancel()
        asked = false
        requestId = notice.beginRequest()
    }

    func proceedOrAsk(_ resume: @escaping () -> Void) -> Bool {
        if store.isAllowed {
            return true
        }
        guard !asked else { return false }
        asked = true
        let id = requestId
        gate.request { [weak self] in
            guard self?.requestId == id else { return }
            resume()
        }
        return false
    }
}
