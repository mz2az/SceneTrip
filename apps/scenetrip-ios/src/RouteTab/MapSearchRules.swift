import CoreGraphics
import Foundation
import SceneApiClient

// 지도 검색의 규칙 (MZ2AZ-547 P5 M6·M7, 웹 `proto/protoPoi.tsx` `SearchPanel`·`ResultsView`). 칩·골고루는 `MapPoiRules.swift`.
//
// 2026-11-10 사용자 지적으로 바뀌었다(웹 72f87416) — 가게·장소(`GET /pois?q`)만 찾는다(촬영지는 찾지 않는다), 고른 곳은
// 시트·서랍·정보 카드가 덮지 않은 지도 가운데로.
// 같은 날 「구글처럼」(계획 motion-works-drawer-parity.md §7, 웹 2fbdbadb) — 글자 치는 동안은 목록만(지도에 찍지 않는다),
// 결과 서랍의 지도 핀은 기본 지도 1등과 같은 갈래 아이콘, 서랍은 20개씩 더 받는다(`MapSearchPagingRules`).

// MARK: - 지도 검색 (M6)

/// 검색 목록의 상태.
enum MapSearchPhase: Equatable {
    /// 2글자가 안 된다 — 목록을 안 보인다.
    case idle
    case loading
    case failed
    /// `wide` — 「이 지역 밖에서도」(범위 없이) 찾은 것.
    case done(shops: [RouteGuide.Place], wide: Bool)
}

enum MapSearchTitle: Equatable {
    /// 「가게·장소 · 이 지역」
    case shopsHere
    /// 「가게·장소 · 전체」
    case shopsEverywhere
}

enum MapSearchEmpty: Equatable {
    /// 「이 지역엔 없어요」
    case noneHere
    /// 「찾는 곳이 없어요」
    case noPlaces
}

/// 검색 목록의 한 줄.
enum MapSearchRow: Equatable {
    case loading
    /// 「찾지 못했어요. 잠시 후 다시 해 주세요」
    case failed
    case title(MapSearchTitle)
    case shop(RouteGuide.Place)
    case empty(MapSearchEmpty)
    /// 「이 지역 밖에서도 찾기」
    case searchEverywhere
}

enum MapSearchRules {
    static let minChars = 2
    /// 입력이 멎고 묻기까지.
    static let debounce: Duration = .milliseconds(350)
    /// 목록(엔터 전)에서 한 번에 받는 수. 결과 서랍(엔터 뒤)은 `MapSearchPagingRules.pageSize` 씩 더 받는다.
    static let listLimit = 20
    /// 예전 결과 서랍 한 번에 받던 수 — §7 뒤로 쓰지 않는다(서랍은 20개씩 더 받기). 기존 시험이 이 값을 본다.
    static let resultsLimit = 40
    /// 계약의 `q` 최대 길이.
    static let maxLength = 100
    /// 고른 곳의 정보 카드(아래 단추 줄 위)가 지도 아래를 덮는 높이(pt) — 시트를 낮게 내려 두었어도 핀이 카드 밑에 숨지 않게
    /// 이만큼은 비킨다(웹 `CARD_COVER`).
    static let cardCover: CGFloat = 300
    /// 고른 곳으로 옮길 때의 최소 확대(웹 `Math.max(map.getZoom(), 16)`).
    static let focusMinZoom: Double = 16

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

    /// 목록 줄 — 가게·장소 제목 → 가게(또는 빈 문구) → 「이 지역 밖에서도 찾기」(이 지역일 때만).
    static func rows(_ phase: MapSearchPhase) -> [MapSearchRow] {
        switch phase {
        case .idle:
            return []
        case .loading:
            return [.loading]
        case .failed:
            return [.failed]
        case let .done(shops, wide):
            var rows: [MapSearchRow] = [.title(wide ? .shopsEverywhere : .shopsHere)]
            rows += shops.isEmpty ? [.empty(wide ? .noPlaces : .noneHere)] : shops.map(MapSearchRow.shop)
            if !wide {
                rows.append(.searchEverywhere)
            }
            return rows
        }
    }

    /// 목록이 떠 있는 동안 지도에 찍어 둘 곳 — 새 결과가 올 때까지는 앞의 것을 둔다(글자마다 점이 깜빡이지 않게).
    /// 2글자 아래로 지웠거나 못 찾았으면 비운다. §7(구글처럼) 뒤로 화면은 쓰지 않는다 — 글자 치는 동안 지도에 찍지 않는다.
    static func pinned(previous: [RouteGuide.Place], phase: MapSearchPhase) -> [RouteGuide.Place] {
        switch phase {
        case .idle, .failed: []
        case .loading: previous
        case let .done(shops, _): shops
        }
    }

    /// 지도에 찍을 검색 핀 — 결과 지도가 떠 있으면 그 결과, 검색칸이 열려 있으면 목록의 것, 둘 다 아니면 없음.
    /// 정보 카드가 떠 있는 곳(`picked`)은 고른 핀이 그리므로 뺀다. §7 뒤로 화면은 `searching: false` 로 부른다(결과 서랍만).
    static func mapDots(
        searching: Bool, listed: [RouteGuide.Place], results: [RouteGuide.Place]?, picked: String?
    ) -> [RouteGuide.Place] {
        let shown = results ?? (searching ? listed : [])
        return shown.filter { $0.id != picked }
    }

    /// 지도 칸 전체의 가운데에서 고른 곳을 위로 올릴 거리 — 아래를 덮은 높이(시트·결과 서랍, 카드는 적어도 300)의 반.
    /// 그러면 덮이지 않은 지도의 가운데에 온다(웹 `focusPlace` 의 `shiftY`).
    static func centerShift(covered: CGFloat) -> CGFloat {
        max(covered, cardCover) / 2
    }

    /// 지도의 `contentInset`(아래)이 이미 그 반만큼 올려 준다 — 그 위로 더 올릴 거리.
    static func extraLift(contentInset: CGFloat) -> CGFloat {
        max(centerShift(covered: contentInset) - contentInset / 2, 0)
    }

    /// 고른 곳으로 옮길 때의 확대 — 지금과 16 중 큰 것(더 확대해 둔 화면을 풀지 않는다).
    static func focusZoom(current: Double) -> Double {
        max(current, focusMinZoom)
    }

    /// 화면에서 `lift`(pt) 위에 고른 곳이 오게 하는 카메라 자리 — 그만큼 남쪽을 가리킨다. `metersPerPoint` 는 그 확대의 축척.
    static func focusTarget(
        latitude: Double, longitude: Double, lift: CGFloat, metersPerPoint: Double
    ) -> (latitude: Double, longitude: Double) {
        (latitude - Double(lift) * metersPerPoint / metersPerDegreeLatitude, longitude)
    }

    /// 위도 1도의 길이(m) — 몇 백 m 안의 옮김이라 지구를 공으로 봐도 된다.
    private static let metersPerDegreeLatitude = 111_320.0
}

// MARK: - 엔터 뒤 결과 지도 (M7)

/// 결과 지도의 상태 — 서랍 높이·받은 것·「다시 찾기」.
struct MapSearchResults: Equatable {
    /// 서랍이 덮는 지도 비율 — 처음 42%, 손잡이를 누르면 78%.
    static let shortShare: Double = 0.42
    static let tallShare: Double = 0.78
    /// 우리가 카메라를 옮긴 직후 이만큼은 사람이 옮긴 것이 아니다(초).
    static let selfMoveGrace: TimeInterval = 0.9

    /// 서랍 본문.
    enum Body: Equatable {
        case loading
        case failed
        /// `offerEverywhere` — 「이 지역 밖에서도 찾기」 단추를 보인다.
        case empty(MapSearchEmpty, offerEverywhere: Bool)
        case shops([RouteGuide.Place])
    }

    let query: String
    /// 범위 없이 찾나(「이 지역 밖에서도」).
    private(set) var wide: Bool
    private(set) var tall = false
    /// 찾은 가게·장소 — 받기 전이면 nil.
    private(set) var shops: [RouteGuide.Place]?
    private(set) var failed = false
    private(set) var stale = false
    /// 서버가 알려 준 전체 개수(`PoiList.total`) — 머리의 「N곳」 과 더 받기의 멈춤.
    private(set) var total: Int?
    /// 마지막으로 받은 쪽이 비어 왔나 — 그러면 더 묻지 않는다.
    private(set) var lastPageEmpty = false
    /// 다음 쪽을 받는 중 — 목록 끝에 도는 줄.
    private(set) var loadingMore = false
    /// 다음 쪽을 못 받았다 — 「더 불러오지 못했어요 · 다시 시도」 줄. 누를 때까지 더 묻지 않는다.
    private(set) var moreFailed = false
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
        shops = nil
        failed = false
        stale = false
        total = nil
        lastPageEmpty = false
        loadingMore = false
        moreFailed = false
    }

    /// 처음 쪽을 받았다 — 「다시 찾기」 를 거둔다.
    mutating func received(shops: [RouteGuide.Place], total: Int? = nil) {
        self.shops = shops
        self.total = total
        lastPageEmpty = shops.isEmpty
        failed = false
        stale = false
    }

    /// 다음에 물을 offset — 받은 것이 없거나, 받는 중·실패한 채이거나, 끝에 닿았으면 nil.
    var nextOffset: Int? {
        guard let shops, !loadingMore, !moreFailed else { return nil }
        return MapSearchPagingRules.nextOffset(loaded: shops.count, total: total, lastPageEmpty: lastPageEmpty)
    }

    mutating func beginMore() {
        loadingMore = true
        moreFailed = false
    }

    /// 다음 쪽을 받았다 — 이미 있는 가게는 다시 넣지 않는다.
    mutating func receivedMore(shops page: [RouteGuide.Place], total: Int?) {
        shops = MapSearchPagingRules.merge(loaded: shops ?? [], page: page)
        if let total {
            self.total = total
        }
        lastPageEmpty = page.isEmpty
        loadingMore = false
    }

    mutating func moreFailedNow() {
        loadingMore = false
        moreFailed = true
    }

    /// 「다시 시도」 — 실패 표시를 거두고 다시 물을 수 있게.
    mutating func retryMore() {
        moreFailed = false
    }

    mutating func searchFailed() {
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
        guard shops != nil else { return }
        if let moved = selfMoveAt, date.timeIntervalSince(moved) < Self.selfMoveGrace {
            return
        }
        stale = true
    }

    /// 「↻ 이 지역에서 다시 찾기」 를 보이나.
    var showsRefresh: Bool {
        stale && shops != nil
    }

    /// 받은 가게 수 — 받기 전이면 nil.
    var count: Int? {
        shops?.count
    }

    /// 검색어 알약의 「N곳」 — 서버의 전체 개수(모르면 받은 수). 받기 전이면 nil.
    var headerCount: Int? {
        guard let shops else { return nil }
        return total ?? shops.count
    }

    var body: Body {
        if failed {
            return .failed
        }
        guard let shops else { return .loading }
        if shops.isEmpty {
            return wide ? .empty(.noPlaces, offerEverywhere: false) : .empty(.noneHere, offerEverywhere: true)
        }
        return .shops(shops)
    }
}
