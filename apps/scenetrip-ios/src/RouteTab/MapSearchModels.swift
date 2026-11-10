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

    /// 가게·장소 이름 검색(`GET /pois?q`, 1.23.0). `bbox` 가 없으면 범위 없이. 촬영지는 찾지 않는다(2026-11-10 사용자 요청).
    /// 기준점이 있으면 거기서 가까운 순(`sort=distance`, §8), 없으면 서버 기본 이름순.
    static func shops(
        _ query: String, bbox: String?, origin: MapSearchOrigin?, limit: Int
    ) async throws -> [RouteGuide.Place] {
        try await shopsPage(query, bbox: bbox, origin: origin, limit: limit, offset: 0).shops
    }

    /// 한 쪽 — 가게와 조건에 맞는 전체 개수(`total`). 결과 서랍의 「더 받기」 가 쓴다(§7-4).
    static func shopsPage(
        _ query: String, bbox: String?, origin: MapSearchOrigin?, limit: Int, offset: Int
    ) async throws -> (shops: [RouteGuide.Place], total: Int) {
        let request = PoisAPI.listPoisWithRequestBuilder(
            bbox: bbox, lat: origin?.lat, lng: origin?.lng, q: query,
            sort: origin == nil ? nil : .distance, limit: limit, offset: offset > 0 ? offset : nil
        )
        let page = try await request.coordinateSource(origin?.source ?? .userSelected).execute().body
        return (page.items.map(RouteGuide.Place.init(poi:)), page.total)
    }
}

/// 🔍 를 눌러 펼친 검색칸의 목록 (M6) — 2글자부터, 0.35초 멈추면 가게·장소를 묻는다.
/// 늦게 온 답이 새 답을 덮지 않는다 — 새로 물으면 앞의 것을 취소한다.
/// 글자 치는 동안은 목록만 — 지도에는 찍지 않는다(§7). `pinned` 는 규칙(`MapSearchRules.pinned`)을 따라 들고 있지만 지도가 그리지 않는다.
@MainActor
final class MapSearchModel: ObservableObject {
    @Published private(set) var phase: MapSearchPhase = .idle {
        didSet { pinned = MapSearchRules.pinned(previous: pinned, phase: phase) }
    }

    /// 지도에 찍어 둔 목록 결과 — 새 결과가 올 때까지는 앞의 것을 둔다.
    @Published private(set) var pinned: [RouteGuide.Place] = []
    private var task: Task<Void, Never>?

    func update(text: String, wide: Bool, viewport: MapViewport?) {
        task?.cancel()
        guard let query = MapSearchRules.query(text) else {
            phase = .idle
            return
        }
        phase = .loading
        let bbox = MapSearchRules.bbox(wide: wide, viewport: viewport)
        let origin = MapSearchPagingRules.origin(viewport: viewport)
        task = Task { [weak self] in
            try? await Task.sleep(for: MapSearchRules.debounce)
            guard !Task.isCancelled else { return }
            do {
                let found = try await MapPoiClient.shops(
                    query, bbox: bbox, origin: origin, limit: MapSearchRules.listLimit
                )
                guard !Task.isCancelled else { return }
                self?.phase = .done(shops: found, wide: wide)
            } catch {
                guard !Task.isCancelled, !PageFetchRules.isCancellation(error) else { return }
                self?.phase = .failed
            }
        }
    }

    /// 검색칸을 닫았다 — 목록과 지도의 점을 다 지운다.
    func reset() {
        task?.cancel()
        phase = .idle
        pinned = []
    }
}

/// 엔터 뒤 결과 지도의 받기 (M7). 상태는 `MapSearchResults`, 여기는 서버에 묻고 그 답을 넣는다.
/// 처음 쪽 20개, 목록 끝이 보이면 같은 범위(bbox)로 다음 20개(§7-4, `MapSearchPagingRules`).
@MainActor
final class MapSearchResultsModel: ObservableObject {
    @Published var state: MapSearchResults?
    /// 처음 쪽에서 찾은 것이 있을 때마다 오른다 — 화면이 결과 전체에 지도를 맞춘다. 더 받을 때는 오르지 않는다.
    @Published private(set) var fitTick = 0
    private var task: Task<Void, Never>?
    private var moreTask: Task<Void, Never>?
    /// 처음 쪽을 물은 범위·기준점 — 다음 쪽도 같은 것으로 묻는다(지도를 옮겨도 「다시 찾기」 전까지는 그대로).
    private var pageBox: String?
    private var pageOrigin: MapSearchOrigin?

    var isOpen: Bool {
        state != nil
    }

    func open(query: String, wide: Bool, viewport: MapViewport?) {
        state = MapSearchResults(query: query, wide: wide)
        load(viewport: viewport)
    }

    func close() {
        task?.cancel()
        moreTask?.cancel()
        state = nil
    }

    /// 목록의 `index` 번 줄이 보였다 — 끝에서 한 줄 안이면 다음 쪽을 묻는다.
    func rowAppeared(_ index: Int) {
        guard let shops = state?.shops,
              MapSearchPagingRules.shouldLoadMore(lastVisibleIndex: index, loaded: shops.count) else { return }
        loadMore()
    }

    /// 「더 불러오지 못했어요 · 다시 시도」.
    func retryMore() {
        state?.retryMore()
        loadMore()
    }

    private func loadMore() {
        guard var current = state, let offset = current.nextOffset else { return }
        current.beginMore()
        state = current
        let query = current.query
        let bbox = pageBox
        let origin = pageOrigin
        let ask = current.ask
        moreTask = Task { [weak self] in
            do {
                let page = try await MapPoiClient.shopsPage(
                    query, bbox: bbox, origin: origin, limit: MapSearchPagingRules.pageSize, offset: offset
                )
                guard !Task.isCancelled, let self, state?.ask == ask else { return }
                state?.receivedMore(shops: page.shops, total: page.total)
            } catch {
                guard !Task.isCancelled, !PageFetchRules.isCancellation(error), self?.state?.ask == ask else { return }
                self?.state?.moreFailedNow()
            }
        }
    }

    func searchHere(viewport: MapViewport?) {
        state?.searchHere()
        load(viewport: viewport)
    }

    /// 범위 없이 다시 — 기준점은 지금 화면 가운데 그대로(§8-1).
    func searchEverywhere(viewport: MapViewport?) {
        state?.searchEverywhere()
        load(viewport: viewport)
    }

    private func load(viewport: MapViewport?) {
        guard var current = state else { return }
        task?.cancel()
        moreTask?.cancel()
        current.begin()
        state = current
        let query = current.query
        let bbox = MapSearchRules.bbox(wide: current.wide, viewport: viewport)
        let origin = MapSearchPagingRules.origin(viewport: viewport)
        pageBox = bbox
        pageOrigin = origin
        task = Task { [weak self] in
            do {
                let page = try await MapPoiClient.shopsPage(
                    query, bbox: bbox, origin: origin, limit: MapSearchPagingRules.pageSize, offset: 0
                )
                guard !Task.isCancelled, let self else { return }
                state?.received(shops: page.shops, total: page.total)
                if !page.shops.isEmpty, MapSearchPagingRules.fitsMap(offset: 0) {
                    fitTick += 1
                }
            } catch {
                guard !Task.isCancelled, !PageFetchRules.isCancellation(error) else { return }
                self?.state?.searchFailed()
            }
        }
    }
}
