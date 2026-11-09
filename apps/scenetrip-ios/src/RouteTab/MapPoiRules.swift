import Foundation
import SceneApiClient

// 지도 위 편의시설 칩·골고루 찍기·지도 검색의 규칙 (MZ2AZ-547 P5 M1–M7, 웹 `proto/protoPoi.tsx`).
// 화면(`MapPoiBar`·`MapSearchPanel`·`MapSearchResultsView`, 계획 화면 쪽은 `RouteEditorMapPoi.swift`)은 이 답을 그대로 따른다.

/// 지도가 지금 보여 주는 범위 — 서랍이 덮은 아래는 뺀 것(`NMFMapView.contentBounds`).
struct MapViewport: Equatable {
    var south: Double
    var west: Double
    var north: Double
    var east: Double
    var centerLat: Double
    var centerLng: Double
    var zoom: Double
    /// 이 범위가 기기 위치에서 왔나(내 위치로 맞춘 지도) — 서버에 좌표 출처로 알린다(위치 동의).
    var source: LocationCoordinateSource = .userSelected

    /// 서버의 `bbox` — GeoJSON 순서 `minLng,minLat,maxLng,maxLat`.
    var bbox: String {
        "\(west),\(south),\(east),\(north)"
    }
}

/// 편의시설 서버 요청 하나(`GET /pois?sort=spread…`, M2).
struct MapPoiRequest: Equatable {
    /// 한 번에 받는 수 — 계약 상한.
    static let limit = 200

    let sort: PoisAPI.Sort_listPois
    let spreadZoom: Int
    let limit: Int
    /// 갈래를 하나만 켰을 때만 서버가 거른다. 여럿이면 다 받아 앱이 거른다(요청은 한 번).
    let categoryGroup: PoiCategoryGroup?
}

/// 지도 맨 위 갈래 칩 줄의 상태 (M1·M2). 처음엔 모두 꺼져 있고, 켤 때만 받는다.
struct MapPoiChips: Equatable {
    private(set) var groupsOn: Set<RoutePoiGroup> = []
    /// 받기 차례 번호 — 0 이면 받지 않는다(다 꺼짐). 칩을 켜고 끄거나 「다시 찾기」 를 누르면 오른다.
    private(set) var ask = 0

    var anyOn: Bool {
        !groupsOn.isEmpty
    }

    /// 「전체」 칩이 켜진 모양인가 — 넷이 다 켜졌다.
    var allOn: Bool {
        groupsOn.count == RoutePoiGroup.allCases.count
    }

    func isOn(_ group: RoutePoiGroup) -> Bool {
        groupsOn.contains(group)
    }

    /// 갈래 칩 하나를 누른다. 켜지면 받고, 남은 것이 있으면 다시 받고, 다 꺼지면 받지 않는다(`ask` 0).
    mutating func toggle(_ group: RoutePoiGroup) {
        if groupsOn.remove(group) == nil {
            groupsOn.insert(group)
        }
        ask = groupsOn.isEmpty ? 0 : ask + 1
    }

    /// 「전체」 — 다 켜져 있으면 다 끄고(받지 않음), 아니면 다 켜고 받는다.
    mutating func toggleAll() {
        if allOn {
            reset()
        } else {
            groupsOn = Set(RoutePoiGroup.allCases)
            ask += 1
        }
    }

    /// 「↻ 이 지역에서 다시 찾기」 — 켜진 것이 있을 때만 받는다.
    mutating func refresh() {
        if anyOn {
            ask += 1
        }
    }

    /// 다 끄고 받지 않는다 — 보기로 돌아가거나 여행을 시작할 때.
    mutating func reset() {
        groupsOn = []
        ask = 0
    }

    /// 지금 켜진 칩으로 서버에 물을 것. 다 꺼져 있으면 nil. `spreadZoom` 은 줌의 내림(0…22).
    func request(zoom: Double) -> MapPoiRequest? {
        guard anyOn else { return nil }
        let single = groupsOn.count == 1 ? groupsOn.first.flatMap { PoiCategoryGroup(rawValue: $0.rawValue) } : nil
        return MapPoiRequest(
            sort: .spread,
            spreadZoom: Int(min(22, max(0, zoom.rounded(.down)))),
            limit: MapPoiRequest.limit,
            categoryGroup: single
        )
    }

    /// 받은 것 중 켜진 갈래만 — 여럿 켰을 때 서버가 다 준 것을 여기서 거른다.
    func keep(_ places: [RouteGuide.Place]) -> [RouteGuide.Place] {
        places.filter { groupsOn.contains($0.poiGroup) }
    }

    /// 칩 옆 개수 — 갈래마다.
    static func counts(_ places: [RouteGuide.Place]) -> [RoutePoiGroup: Int] {
        places.reduce(into: [:]) { counts, place in counts[place.poiGroup, default: 0] += 1 }
    }

    /// 칩 줄이 보이나 — 편집 중이고 지도가 펼쳐져 있고 여행 안내 중이 아닐 때만(M1).
    static func showsBar(mode: RouteEditorMode, mapOpen: Bool, tripActive: Bool) -> Bool {
        mode == .editing && mapOpen && !tripActive
    }
}

/// 칩 줄 아래 상태 알약 (M3).
enum MapPoiPill: Equatable {
    case none
    /// 「찾는 중」
    case searching
    /// 「↻ 이 지역에서 다시 찾기」 — 받은 뒤 지도가 움직였다.
    case refresh
    /// 「조금 더 확대하면 보여요」
    case zoomIn
}

/// 받기와 지도 움직임 — 알약을 정한다 (M3). 지도를 옮겨도 점은 그대로 두고 「다시 찾기」 만 띄운다.
struct MapPoiFetch: Equatable {
    /// 이보다 넓으면 받지 않는다. 골고루 찍기는 제한이 없다(M2 — 예전 iOS 줌 13 제한을 뺐다).
    static let minZoom: Double = 0

    private(set) var loading = false
    private(set) var stale = false

    /// 받기 시작 — 「찾는 중」. 지난 「다시 찾기」 는 거둔다.
    mutating func begin() {
        loading = true
        stale = false
    }

    /// 다 받았다. 받는 사이 지도가 움직였으면 「다시 찾기」 는 남긴다(받은 것은 옛 범위의 것이다).
    mutating func finish() {
        loading = false
    }

    /// 지도가 멈췄다.
    mutating func cameraIdle() {
        stale = true
    }

    /// 다 끄면 알약도 없다.
    mutating func clear() {
        loading = false
        stale = false
    }

    func pill(anyOn: Bool, zoom: Double) -> MapPoiPill {
        guard anyOn else { return .none }
        if loading {
            return .searching
        }
        if zoom < Self.minZoom {
            return .zoomIn
        }
        return stale ? .refresh : .none
    }
}

/// 골고루 찍기 (M4) — 지도에 고정된 72pt 칸마다 받은 순서의 1등은 원래 점, 나머지는 작은 점.
/// 칸은 화면이 아니라 지도(웹 메르카토르)에 고정이라 지도를 옮겨도 같은 곳이 같은 칸에 든다. 확대하면 다시 받지 않고 다시 솎는다.
enum MapPoiSpread {
    /// 칸 한 변(pt, 그 줌에서).
    static let cell: Double = 72
    /// 작은 점 — 보이는 지름과 누르는 자리.
    static let minorVisible: CGFloat = 9
    static let minorHit: CGFloat = 16

    /// `tileSize` — 줌 0 에서 세계 한 변이 몇 pt 인가(네이버 지도는 256).
    static func split(
        _ places: [RouteGuide.Place], zoom: Double, tileSize: Double = 256
    ) -> (major: [RouteGuide.Place], minor: [RouteGuide.Place]) {
        // 받은 순서가 곧 순위다(서버 `sort=spread`) — 칸마다 먼저 온 것이 큰 점. 서버의 칸 안 순서와 같은 줄이라
        // 확대해 칸을 쪼개도 큰 점은 늘기만 한다.
        let world = tileSize * pow(2, zoom.rounded(.down)) / cell
        var seen = Set<String>()
        var major: [RouteGuide.Place] = []
        var minor: [RouteGuide.Place] = []
        for place in places {
            let column = ((place.longitude + 180) / 360 * world).rounded(.down)
            let lat = place.latitude * .pi / 180
            let row = ((1 - log(tan(lat) + 1 / cos(lat)) / .pi) / 2 * world).rounded(.down)
            if seen.insert("\(column):\(row)").inserted {
                major.append(place)
            } else {
                minor.append(place)
            }
        }
        return (major, minor)
    }
}
