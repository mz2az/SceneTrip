import Foundation
import SceneApiClient

/// 지도 편의시설·지도 검색이 서버에 묻는 것 (MZ2AZ-547 M2·M6·M7). 재시도는 공통 계층이 한다(`RetryRules`).
enum MapPoiClient {
    /// 칩을 켰을 때 — 지금 화면을 서버의 「골고루」 순서로(`sort=spread`). 받은 순서가 곧 순위다.
    /// 여럿 켰으면 서버는 다 주고 여기서 켜진 갈래만 남긴다. 못 받으면 nil(점은 비운다).
    static func spread(
        _ request: MapPoiRequest, in viewport: MapViewport, chips: MapPoiChips, source: LocationCoordinateSource
    ) async -> [RouteGuide.Place]? {
        guard let list = try? await PoisAPI.listPoisWithRequestBuilder(
            bbox: viewport.bbox, lat: viewport.centerLat, lng: viewport.centerLng,
            categoryGroup: request.categoryGroup, sort: request.sort,
            spreadZoom: request.spreadZoom, limit: request.limit
        ).coordinateSource(source).execute().body else { return nil }
        return chips.keep(list.items.map(RouteGuide.Place.init(poi:)))
    }

    /// 촬영지 이름 검색(`GET /places?q`). `bbox` 가 없으면 범위 없이.
    static func spots(_ query: String, bbox: String?, limit: Int) async throws -> [PlaceSummary] {
        try await PlacesAPI.listPlaces(q: query, bbox: bbox, limit: limit).items
    }

    /// 가게·편의시설 이름 검색(`GET /pois?q`, 1.23.0). 실패하면 빈 목록 — 촬영지 결과는 그대로 보인다(웹과 같다).
    static func shops(_ query: String, bbox: String?, limit: Int) async -> [RouteGuide.Place] {
        let list = try? await PoisAPI.listPois(bbox: bbox, q: query, limit: limit)
        return list?.items.map(RouteGuide.Place.init(poi:)) ?? []
    }
}

/// 🔍 를 눌러 펼친 검색칸의 목록 (M6) — 2글자부터, 0.35초 멈추면 촬영지와 가게를 **동시에** 묻는다.
/// 늦게 온 답이 새 답을 덮지 않는다 — 새로 물으면 앞의 것을 취소한다.
@MainActor
final class MapSearchModel: ObservableObject {
    @Published private(set) var phase: MapSearchPhase = .idle
    private var task: Task<Void, Never>?

    func update(text: String, wide: Bool, viewport: MapViewport?) {
        task?.cancel()
        guard let query = MapSearchRules.query(text) else {
            phase = .idle
            return
        }
        phase = .loading
        let bbox = MapSearchRules.bbox(wide: wide, viewport: viewport)
        task = Task { [weak self] in
            try? await Task.sleep(for: MapSearchRules.debounce)
            guard !Task.isCancelled else { return }
            async let shops = MapPoiClient.shops(query, bbox: bbox, limit: MapSearchRules.listLimit)
            do {
                let spots = try await MapPoiClient.spots(query, bbox: bbox, limit: MapSearchRules.listLimit)
                let found = await shops
                guard !Task.isCancelled else { return }
                self?.phase = .done(spots: spots, shops: found, wide: wide)
            } catch {
                guard !Task.isCancelled, !PageFetchRules.isCancellation(error) else { return }
                self?.phase = .failed
            }
        }
    }

    func reset() {
        task?.cancel()
        phase = .idle
    }
}

/// 엔터 뒤 결과 지도의 받기 (M7). 상태는 `MapSearchResults`, 여기는 서버에 묻고 그 답을 넣는다.
@MainActor
final class MapSearchResultsModel: ObservableObject {
    @Published var state: MapSearchResults?
    /// 촬영지를 받을 때마다 오른다 — 화면이 결과 전체에 지도를 맞춘다.
    @Published private(set) var fitTick = 0
    private var task: Task<Void, Never>?

    var isOpen: Bool {
        state != nil
    }

    func open(query: String, wide: Bool, viewport: MapViewport?) {
        state = MapSearchResults(query: query, wide: wide)
        load(viewport: viewport)
    }

    func close() {
        task?.cancel()
        state = nil
    }

    func searchHere(viewport: MapViewport?) {
        state?.searchHere()
        load(viewport: viewport)
    }

    func searchEverywhere() {
        state?.searchEverywhere()
        load(viewport: nil)
    }

    private func load(viewport: MapViewport?) {
        guard var current = state else { return }
        task?.cancel()
        current.begin()
        state = current
        let query = current.query
        let bbox = MapSearchRules.bbox(wide: current.wide, viewport: viewport)
        let limit = MapSearchRules.resultsLimit
        task = Task { [weak self] in
            async let shops = MapPoiClient.shops(query, bbox: bbox, limit: limit)
            do {
                let spots = try await MapPoiClient.spots(query, bbox: bbox, limit: limit)
                guard !Task.isCancelled, let self else { return }
                state?.received(spots: spots)
                if !spots.isEmpty {
                    fitTick += 1
                }
            } catch {
                guard !Task.isCancelled, !PageFetchRules.isCancellation(error) else { return }
                self?.state?.spotsFailed()
            }
            let found = await shops
            guard !Task.isCancelled else { return }
            self?.state?.received(shops: found)
        }
    }
}
