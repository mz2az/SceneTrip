import Foundation
import SceneApiClient

// 지도 검색의 규칙 (MZ2AZ-547 P5 M6·M7, 웹 `proto/protoPoi.tsx` `SearchPanel`·`ResultsView`). 칩·골고루는 `MapPoiRules.swift`.

// MARK: - 지도 검색 (M6)

/// 검색 목록의 상태.
enum MapSearchPhase: Equatable {
    /// 2글자가 안 된다 — 목록을 안 보인다.
    case idle
    case loading
    case failed
    /// `wide` — 「이 지역 밖에서도」(범위 없이) 찾은 것.
    case done(spots: [PlaceSummary], shops: [RouteGuide.Place], wide: Bool)
}

enum MapSearchTitle: Equatable {
    /// 「촬영지 · 이 지역」
    case spotsHere
    /// 「촬영지 · 전체」
    case spotsEverywhere
    /// 「가게·편의시설 · 이 지역」
    case shopsHere
    /// 「가게·편의시설 · 전체」
    case shopsEverywhere
}

enum MapSearchEmpty: Equatable {
    /// 「이 지역엔 없어요」
    case noneHere
    /// 「찾는 촬영지가 없어요」
    case noSpots
    /// 「찾는 가게가 없어요」
    case noShops
}

/// 검색 목록의 한 줄.
enum MapSearchRow: Equatable {
    case loading
    /// 「찾지 못했어요. 잠시 후 다시 해 주세요」
    case failed
    case title(MapSearchTitle)
    case spot(PlaceSummary)
    case shop(RouteGuide.Place)
    case empty(MapSearchEmpty)
    /// 「이 지역 밖에서도 찾기」
    case searchEverywhere
}

enum MapSearchRules {
    static let minChars = 2
    /// 입력이 멎고 묻기까지.
    static let debounce: Duration = .milliseconds(350)
    /// 목록(엔터 전)과 결과 지도(엔터 뒤)에서 한 번에 받는 수.
    static let listLimit = 20
    static let resultsLimit = 40
    /// 계약의 `q` 최대 길이.
    static let maxLength = 100

    /// 입력을 서버에 보낼 검색어로 — 앞뒤 공백을 떼고 2글자가 안 되면 nil, 길면 100자에서 자른다.
    static func query(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.count >= minChars else { return nil }
        return String(trimmed.prefix(maxLength))
    }

    /// 엔터를 받을 수 있나(2글자 이상).
    static func canCommit(_ text: String) -> Bool {
        query(text) != nil
    }

    /// 범위 — 「이 지역 밖에서도」 면 없음, 아니면 지금 지도.
    static func bbox(wide: Bool, viewport: MapViewport?) -> String? {
        wide ? nil : viewport?.bbox
    }

    /// 목록 줄 — 촬영지 제목 → 촬영지(또는 빈 문구) → 「이 지역 밖에서도 찾기」(이 지역일 때만) → 가게 제목 → 가게(또는 빈 문구).
    static func rows(_ phase: MapSearchPhase) -> [MapSearchRow] {
        switch phase {
        case .idle:
            return []
        case .loading:
            return [.loading]
        case .failed:
            return [.failed]
        case let .done(spots, shops, wide):
            var rows: [MapSearchRow] = [.title(wide ? .spotsEverywhere : .spotsHere)]
            rows += spots.isEmpty ? [.empty(wide ? .noSpots : .noneHere)] : spots.map(MapSearchRow.spot)
            if !wide {
                rows.append(.searchEverywhere)
            }
            rows.append(.title(wide ? .shopsEverywhere : .shopsHere))
            rows += shops.isEmpty ? [.empty(.noShops)] : shops.map(MapSearchRow.shop)
            return rows
        }
    }
}

// MARK: - 엔터 뒤 결과 지도 (M7)

/// 결과 지도의 상태 — 서랍 높이·탭·받은 것·「다시 찾기」·고른 핀.
struct MapSearchResults: Equatable {
    /// 서랍이 덮는 지도 비율 — 처음 42%, 손잡이를 누르면 78%.
    static let shortShare: Double = 0.42
    static let tallShare: Double = 0.78
    /// 결과 핀 지름 — 보통 34, 고르면 46.
    static let pinSmall: CGFloat = 34
    static let pinBig: CGFloat = 46
    /// 우리가 카메라를 옮긴 직후 이만큼은 사람이 옮긴 것이 아니다(초).
    static let selfMoveGrace: TimeInterval = 0.9

    enum Tab: Equatable {
        case spots
        case shops
    }

    /// 서랍 본문.
    enum Body: Equatable {
        case loading
        case failed
        /// `offerEverywhere` — 「이 지역 밖에서도 찾기」 단추를 보인다.
        case empty(MapSearchEmpty, offerEverywhere: Bool)
        case spots([PlaceSummary])
        case shops([RouteGuide.Place])
    }

    let query: String
    /// 범위 없이 찾나(「이 지역 밖에서도」).
    private(set) var wide: Bool
    var tab: Tab = .spots
    private(set) var tall = false
    /// 촬영지 — 받기 전이면 nil.
    private(set) var spots: [PlaceSummary]?
    private(set) var shops: [RouteGuide.Place] = []
    private(set) var failed = false
    private(set) var stale = false
    private(set) var selected: Int64?
    /// 받기 차례 번호 — 처음·「다시 찾기」·「이 지역 밖에서도」 에 오른다.
    private(set) var ask = 0
    private var selfMoveAt: Date?

    init(query: String, wide: Bool) {
        self.query = query
        self.wide = wide
    }

    var drawerShare: Double {
        tall ? Self.tallShare : Self.shortShare
    }

    mutating func toggleDrawer() {
        tall.toggle()
    }

    /// 새로 받기 시작 — 목록을 비운다.
    mutating func begin() {
        spots = nil
        shops = []
        failed = false
        stale = false
    }

    /// 촬영지를 받았다 — 「다시 찾기」 를 거둔다.
    mutating func received(spots: [PlaceSummary]) {
        self.spots = spots
        failed = false
        stale = false
    }

    mutating func received(shops: [RouteGuide.Place]) {
        self.shops = shops
    }

    mutating func spotsFailed() {
        failed = true
    }

    /// 「↻ 이 지역에서 다시 찾기」 — 지금 지도 범위로.
    mutating func searchHere() {
        wide = false
        ask += 1
    }

    /// 「이 지역 밖에서도 찾기」 — 범위 없이.
    mutating func searchEverywhere() {
        wide = true
        ask += 1
    }

    /// 앱이 카메라를 옮겼다(결과에 맞춤·고른 곳으로).
    mutating func markSelfMove(at date: Date) {
        selfMoveAt = date
    }

    /// 지도가 멈췄다 — 사람이 옮긴 것이고 받은 것이 있으면 「다시 찾기」.
    mutating func cameraIdle(at date: Date) {
        guard spots != nil else { return }
        if let moved = selfMoveAt, date.timeIntervalSince(moved) < Self.selfMoveGrace {
            return
        }
        stale = true
    }

    mutating func select(_ id: Int64?) {
        selected = id
    }

    /// 「↻ 이 지역에서 다시 찾기」 를 보이나.
    var showsRefresh: Bool {
        stale && spots != nil
    }

    /// 탭 글자의 수 — 촬영지는 받기 전이면 nil(「촬영지」 만), 가게는 늘 수.
    func count(of tab: Tab) -> Int? {
        switch tab {
        case .spots: spots?.count
        case .shops: shops.count
        }
    }

    var body: Body {
        switch tab {
        case .shops:
            return shops.isEmpty ? .empty(wide ? .noShops : .noneHere, offerEverywhere: false) : .shops(shops)
        case .spots:
            if failed {
                return .failed
            }
            guard let spots else { return .loading }
            if spots.isEmpty {
                return wide ? .empty(.noSpots, offerEverywhere: false) : .empty(.noneHere, offerEverywhere: true)
            }
            return .spots(spots)
        }
    }
}
