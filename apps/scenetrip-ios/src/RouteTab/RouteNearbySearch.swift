import Combine
import CoreLocation
import Foundation
import SceneApiClient

/// AI 세션과 독립된 촬영지 조회. 생성 GET /places의 bbox 검색만 사용한다.
@MainActor
final class RouteNearbySearch: ObservableObject {
    struct Query {
        private static let halfSideKilometers = 3.0
        private static let kilometersPerDegree = 111.0
        private static let radiansPerDegree = Double.pi / 180
        private static let minimumLongitudeScale = 0.01
        let center: TripSpot
        let source: LocationCoordinateSource
        let bbox: String

        /// 중심에서 남북·동서 약 3km인 사각 영역. 고위도·날짜 변경선에서는 유효 범위로 제한한다.
        init(anchor: RouteGuideAnchor) {
            center = anchor.spot
            source = anchor.source
            let latitudeDelta = Self.halfSideKilometers / Self.kilometersPerDegree
            let longitudeDelta = latitudeDelta / max(
                cos(center.latitude * Self.radiansPerDegree), Self.minimumLongitudeScale
            )
            bbox = [
                max(-180, center.longitude - longitudeDelta), max(-90, center.latitude - latitudeDelta),
                min(180, center.longitude + longitudeDelta), min(90, center.latitude + latitudeDelta),
            ].map { String($0) }.joined(separator: ",")
        }
    }

    enum Phase: Equatable {
        case idle, loading, loaded, needsConsent
        case failed(ApiFailure)
    }

    typealias Fetch = @MainActor (Query) async throws -> PlaceList
    static let limit = 40
    let gate: AIConsentGate
    @Published private(set) var phase: Phase = .idle
    @Published private(set) var places: [PlaceSummary] = []
    private let consent: LocationConsentStore
    private let fetch: Fetch
    private var generation = 0

    init(consent: LocationConsentStore = .shared, fetch: @escaping Fetch = RouteNearbySearch.fetchPlaces) {
        self.consent = consent
        gate = AIConsentGate(store: consent)
        self.fetch = fetch
    }

    static func builder(_ query: Query) -> RequestBuilder<PlaceList> {
        PlacesAPI.listPlacesWithRequestBuilder(bbox: query.bbox, limit: limit)
            .coordinateSource(query.source)
    }

    private static func fetchPlaces(_ query: Query) async throws -> PlaceList {
        try await builder(query).execute().body
    }

    /// 출처는 조회 시작부터 인증 재전송까지 보존한다. 전송 계층도 직전에 철회를 검사한다.
    func load(_ anchor: RouteGuideAnchor) async {
        generation += 1
        let asked = generation
        places = []
        phase = .loading
        do {
            guard anchor.source != .device || consent.isAllowed else { throw LocationConsentRequired() }
            guard CLLocationCoordinate2DIsValid(anchor.coordinate) else { throw URLError(.badURL) }
            let page = try await fetch(Query(anchor: anchor))
            guard !Task.isCancelled, generation == asked else { return }
            places = page.items
            phase = .loaded
        } catch {
            guard !Task.isCancelled, generation == asked else { return }
            phase = error is LocationConsentRequired ? .needsConsent : .failed(ApiFailure(error))
        }
    }
}
