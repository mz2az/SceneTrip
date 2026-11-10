import Foundation

// 지도 검색 결과 서랍의 「더 받기」 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §7-4).
// 한 쪽은 20개(`GET /pois?q&bbox&limit=20&offset=…`). 목록 끝에서 한 줄 안이 보이면 다음 쪽을 묻고,
// `total` 에 닿거나 빈 쪽이 오면 멈춘다. 이미 받은 가게(같은 id)는 다시 넣지 않는다.
// 지도를 결과에 맞추는 것은 처음 쪽(offset 0)에서만.

enum MapSearchPagingRules {
    /// 한 번에 받는 개수(`limit`).
    static let pageSize: Int = 20

    /// 다음에 물을 `offset`. 더 받을 것이 없으면 nil.
    /// - Parameters:
    ///   - loaded: 지금까지 목록에 담긴 개수.
    ///   - total: 서버가 알려 준 전체 개수(`PoiList.total`). 모르면 nil.
    ///   - lastPageEmpty: 마지막으로 받은 쪽이 비어 왔나.
    static func nextOffset(loaded: Int, total: Int?, lastPageEmpty: Bool) -> Int? {
        if lastPageEmpty {
            return nil
        }
        if let total, loaded >= total {
            return nil
        }
        return loaded
    }

    /// 보이는 줄 가운데 가장 아래 줄의 번호가 `lastVisibleIndex` 일 때 다음 쪽을 물을까 — 끝에서 한 줄 안(index >= loaded-2).
    /// 목록이 비었으면 묻지 않는다.
    static func shouldLoadMore(lastVisibleIndex: Int, loaded: Int) -> Bool {
        loaded > 0 && lastVisibleIndex >= loaded - 2
    }

    /// 받은 쪽을 목록 뒤에 붙인다 — 이미 있는 id 는 빼고, 순서는 (있던 것, 새것을 서버 순서대로).
    static func merge<Item: Identifiable>(loaded: [Item], page: [Item]) -> [Item] {
        var seen = Set(loaded.map(\.id))
        var merged = loaded
        for item in page where seen.insert(item.id).inserted {
            merged.append(item)
        }
        return merged
    }

    /// 이 쪽을 받았을 때 지도를 결과에 맞출까 — 처음 쪽(offset 0)만.
    static func fitsMap(offset: Int) -> Bool {
        offset == 0
    }

    /// 지도에 결과 핀을 찍는 최대 개수(§8-2). 목록은 이보다 길어질 수 있다.
    static let pinLimit: Int = 100

    /// 결과 목록 가운데 지도에 핀으로 찍을 것 — 앞에서부터 `pinLimit` 개(가까운 순이므로 가까운 곳들).
    static func pinnedResults<Item>(_ shops: [Item]) -> [Item] {
        Array(shops.prefix(pinLimit))
    }

    /// 검색의 기준점(§8-1) — 물을 때의 화면 가운데. 화면을 모르면 nil(서버 기본 이름순).
    static func origin(viewport: MapViewport?) -> MapSearchOrigin? {
        guard let viewport else { return nil }
        return MapSearchOrigin(lat: viewport.centerLat, lng: viewport.centerLng, source: viewport.source)
    }
}

/// 지도 검색이 「가까운 순」 으로 물을 기준점(`lat`·`lng`, `sort=distance`).
struct MapSearchOrigin: Equatable {
    var lat: Double
    var lng: Double
    /// 좌표 출처 — 화면 범위의 출처를 그대로 알린다(위치 동의).
    var source: LocationCoordinateSource
}
