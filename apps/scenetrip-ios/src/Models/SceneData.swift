import Foundation
import SceneApiClient

/// 화면이 쓰는 데이터 저장소. **서버가 정본이다.**
///
/// 검색은 서버가 한다. `GET /v1/contents` 와 `GET /v1/places` 에 **같은 `q` 를 넣으면**
/// 두 탭이 같이 채워진다 (계획서 §3-2 · §4). 배우 이름으로도 장소가 걸리도록 서버가
/// 이미 넓혀 뒀으므로(MZ2AZ-167) 프론트가 우회할 것이 없다 — 그래서 이 타입에는
/// 검색 로직이 없다.
@MainActor
final class SceneData: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed(ApiFailure)
    }

    /// 한 번에 받는 양 (MZ2AZ-372). 작품은 줄이 크고 128편에서 계속 늘어 화면 몇 장 분량씩 받는다.
    /// 촬영지는 지도 핀으로도 쓰여 계약의 상한(200)만큼 받는다 — 그보다 많으면 `total` 로 알린다.
    enum PageSize {
        static let contents = 50
        static let places = 200
    }

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var contents: [ContentSummary] = []
    @Published private(set) var places: [PlaceSummary] = []

    /// 어디까지 받았고 전부 몇 건인가. 화면의 개수는 여기의 `total` 이다 — 받은 줄 수가 아니다.
    @Published private(set) var contentPaging = Paging()
    @Published private(set) var placePaging = Paging()
    @Published private(set) var loadingMoreContents = false
    @Published private(set) var loadingMorePlaces = false
    /// 이어 받기가 실패했다 — 목록 끝에 「다시 시도」 를 둔다. 저절로 다시 부르지 않는다.
    @Published private(set) var moreContentsFailed = false
    @Published private(set) var morePlacesFailed = false

    private var inFlight: Task<Void, Never>?
    private var lastQuery = ""

    /// 지금 목록이 **무엇을 물은 결과인가** — 이어 받을 때 같은 조건에 `offset` 만 바꿔 보낸다.
    private var contentKeyword: String?
    private var placeScope = PlaceScope.keyword(nil)

    private enum PlaceScope {
        case keyword(String?)
        case viewport(String)
    }

    /// 검색이 새로 걸릴 때마다 오른다. 이어 받던 응답이 그 뒤에 오면 버린다 — 옛 검색의 줄이 새 목록에 붙는다.
    private var contentRun = 0
    private var placeRun = 0

    /// 첫 쪽이 새로 깔릴 때마다 오른다(검색·지도 범위). 화면이 이것으로 목록을 맨 위로 되돌린다 —
    /// 앞 검색에서 내려가 있던 자리에 새 결과의 가운데가 보이면 안 된다.
    @Published private(set) var listSerial = 0

    /// 검색어 하나로 두 탭을 채운다. 빈 문자열이면 전체를 받는다.
    /// `kind` 는 분석용 — 추천·자동완성에서 고른 갈래. 직접 쳤으면 없다.
    func search(_ query: String, kind: String? = nil) {
        if !query.isEmpty {
            // 검색어 원문은 보내지 않는다 — 길이와 갈래만 (MZ2AZ-353).
            AppAnalytics.log(.search(termLength: query.count, kind: kind ?? "typed"))
        }
        lastQuery = query
        inFlight?.cancel()
        phase = .loading
        let term = query.trimmingCharacters(in: .whitespaces)
        let keyword: String? = term.isEmpty ? nil : term
        resetContentPaging()
        resetPlacePaging()
        inFlight = Task { [weak self] in
            do {
                // 두 탭은 서로를 기다릴 이유가 없다.
                async let works = ContentsAPI.listContents(q: keyword, limit: PageSize.contents)
                async let spots = PlacesAPI.listPlaces(q: keyword, limit: PageSize.places)
                let (contentList, placeList) = try await (works, spots)
                guard !Task.isCancelled, let self else { return }
                contentKeyword = keyword
                placeScope = .keyword(keyword)
                contents = contentList.items
                contentPaging = Paging().advanced(received: contentList.items.count, total: contentList.total)
                places = placeList.items
                placePaging = Paging().advanced(received: placeList.items.count, total: placeList.total)
                listSerial += 1
                phase = .loaded
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.phase = .failed(ApiFailure(error))
            }
        }
    }

    /// **지금 화면에 보이는 지도 범위** 안의 촬영지만 (목업 "현 지도 내 성지 검색").
    ///
    /// 반경이 아니라 뷰포트다. 사용자가 얼마나 확대했는지·어디를 보고 있는지는
    /// 그때그때 다르고, 이 기능은 **보이는 그대로** 를 묻는 것이다. 그래서 고정
    /// 반경(`radiusMeters`)이 아니라 `bbox` 를 보낸다.
    ///
    /// **`q` 를 함께 보내지 않는다** — "이 화면 안" 과 "이 단어" 는 서로 다른
    /// 질문이라 섞으면 결과를 설명할 수 없다.
    ///
    /// 작품 탭은 건드리지 않는다. 뷰포트는 장소의 성질이지 작품의 성질이 아니다.
    func searchInViewport(bbox: String) {
        lastQuery = ""
        inFlight?.cancel()
        phase = .loading
        resetPlacePaging()
        inFlight = Task { [weak self] in
            do {
                let spots = try await PlacesAPI.listPlaces(bbox: bbox, limit: PageSize.places)
                guard !Task.isCancelled, let self else { return }
                placeScope = .viewport(bbox)
                places = spots.items
                placePaging = Paging().advanced(received: spots.items.count, total: spots.total)
                listSerial += 1
                phase = .loaded
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                self?.phase = .failed(ApiFailure(error))
            }
        }
    }

    // MARK: 이어 받기 (MZ2AZ-372)

    /// 작품 목록의 다음 쪽. 목록 끝이 보일 때 화면이 부른다 — 받는 중이거나 다 받았으면 아무 일도 없다.
    func loadMoreContents() {
        guard phase == .loaded, !loadingMoreContents, contentPaging.hasMore else { return }
        loadingMoreContents = true
        moreContentsFailed = false
        let run = contentRun
        let keyword = contentKeyword
        let offset = contentPaging.offset
        Task { [weak self] in
            let fetch = await PageFetch.attempt {
                try await ContentsAPI.listContents(q: keyword, limit: PageSize.contents, offset: offset)
            }
            guard let self, run == contentRun else { return }
            loadingMoreContents = false
            switch fetch {
            case let .page(page):
                contents = Paging.merged(contents, with: page.items, by: \.id)
                contentPaging = contentPaging.advanced(received: page.items.count, total: page.total)
            case .failed:
                moreContentsFailed = true
            case .cancelled:
                break
            }
        }
    }

    /// 촬영지 목록의 다음 쪽 — 검색어든 지도 범위든 **처음 물은 조건 그대로** 이어 받는다.
    /// 목록과 지도가 같은 배열을 쓰므로 받은 만큼 핀도 는다.
    func loadMorePlaces() {
        Task { await loadNextPlaces() }
    }

    /// 다음 쪽을 받고 **끝날 때까지 기다린다.** 한 쪽을 붙였으면 참 — 더 받을 것이 없거나, 못 받았거나,
    /// 그 사이 새 검색이 걸렸으면 거짓이다. 분류 칩이 켜졌을 때 화면이 이것을 이어 불러 끝까지 받는다.
    ///
    /// 다른 곳에서 이미 받는 중이면(목록 끝줄이 부른 것) 그것이 끝나기를 기다렸다가 이어 간다 —
    /// 같은 쪽을 두 번 받지 않는다.
    @discardableResult
    func loadNextPlaces() async -> Bool {
        while loadingMorePlaces {
            try? await Task.sleep(for: .milliseconds(80))
            if Task.isCancelled {
                return false
            }
        }
        guard phase == .loaded, placePaging.hasMore else { return false }
        loadingMorePlaces = true
        morePlacesFailed = false
        let run = placeRun
        let offset = placePaging.offset
        let scope = placeScope
        let fetch = await PageFetch.attempt {
            switch scope {
            case let .keyword(keyword):
                try await PlacesAPI.listPlaces(q: keyword, limit: PageSize.places, offset: offset)
            case let .viewport(bbox):
                try await PlacesAPI.listPlaces(bbox: bbox, limit: PageSize.places, offset: offset)
            }
        }
        // 받는 사이 새 검색이 걸렸으면 버린다 — 옛 검색의 줄이 새 목록에 붙는다.
        guard run == placeRun else { return false }
        loadingMorePlaces = false
        switch fetch {
        case let .page(page):
            places = Paging.merged(places, with: page.items, by: \.id)
            placePaging = placePaging.advanced(received: page.items.count, total: page.total)
            return !page.items.isEmpty
        case .failed:
            morePlacesFailed = true
            return false
        case .cancelled:
            // 칩을 끄거나 화면을 떠나 그만둔 것이다 — 실패로 적지 않는다(`PageFetch`).
            return false
        }
    }

    private func resetContentPaging() {
        contentRun += 1
        loadingMoreContents = false
        moreContentsFailed = false
    }

    private func resetPlacePaging() {
        placeRun += 1
        loadingMorePlaces = false
        morePlacesFailed = false
    }

    /// §3-6 의 재시도. 마지막 검색어를 그대로 다시 보낸다.
    func retry() {
        search(lastQuery)
    }

    /// 작품 하나를 골랐을 때 그 작품의 촬영지 **전부**.
    ///
    /// 이 엔드포인트의 `limit` 상한은 100 이다 — 목록 API 의 200 을 그대로 넣으면
    /// `400 INVALID_PARAMETER` 가 온다(실측). 상한을 넘는 작품은 `offset` 으로
    /// 이어 받는다. 핀 번호가 곧 행 번호라 일부만 그리면 번호가 어긋난다.
    func places(ofContent id: Int64) async throws -> [PlaceSummary] {
        var all: [PlaceSummary] = []
        while true {
            let page = try await PlacesAPI.listContentPlaces(
                contentId: id, limit: 100, offset: all.count
            )
            all.append(contentsOf: page.items)
            if page.items.isEmpty || all.count >= page.total {
                return all
            }
        }
    }

    /// 촬영지 상세. 목록용 `PlaceSummary` 에는 없는 것들이 여기 있다 — 장소 사진 여러
    /// 장, 네이버 링크, 그리고 **작품별 장면**(`scenes`). 한 장소에서 여러 작품을
    /// 찍었으면 그 수만큼 온다.
    func detail(ofPlace id: Int64) async throws -> PlaceDetail {
        try await PlacesAPI.getPlace(placeId: id)
    }
}

/// 화면이 오류를 다루는 데 필요한 만큼만 추린 것 (계획서 §3-6).
///
/// 계약이 `ApiError.message` 를 "사용자에게 그대로 보여 줄 문구가 아니다" 라고 못 박아
/// 뒀으므로 여기서 화면 문구를 만들지 않는다. 상태 코드와 `traceId` 만 들고 나간다.
struct ApiFailure: Equatable {
    let statusCode: Int?
    let traceId: String?
    /// 기기가 오프라인이라 실패했다 — 서버 탓이 아니라 「인터넷 연결」 을 말해야 한다 (MZ2AZ-366).
    var isOffline = false

    /// 「다시 시도」 가 의미 있는 경우 — **다시 누르면 달라질 수 있는 것.** 서버에 닿지 못했거나(statusCode
    /// 없음), 서버 사정(500·502·503·504)이거나, 분당 한도(429)다. 400·404 처럼 요청이 틀린 것은 눌러도 같다.
    ///
    /// 여기까지 올라온 실패는 공통 계층(`RetryingSession`)이 이미 몇 번 다시 보내 본 것이다 — 그래도 안 된
    /// 것이라 사람에게 단추를 준다. 전에는 500 과 연결 실패에만 줘서 503·429 에는 「요청을 처리하지
    /// 못했습니다」 만 뜨고 다시 해 볼 길이 없었다.
    var isRetryable: Bool {
        guard let statusCode else { return true }
        return [429, 500, 502, 503, 504].contains(statusCode)
    }

    /// 사용자에게 보일 한 줄.
    ///
    /// **안드로이드의 `ApiFailure.message` 와 같은 문구여야 한다** — 두 앱이 같은
    /// 상황에서 다른 말을 하면 같은 앱으로 보이지 않는다. 앞서 이 문구가 검색 탭의
    /// `ErrorView` 안에만 있어서 다른 화면이 재사용할 수 없었다.
    var message: String {
        switch statusCode {
        case nil where isOffline: tr("인터넷 연결을 확인해 주세요.")
        case nil: tr("서버에 연결하지 못했습니다.")
        case 500, 502, 503, 504: tr("잠시 문제가 생겼습니다.")
        case 429: tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요.")
        default: tr("요청을 처리하지 못했습니다.")
        }
    }

    init(statusCode: Int?, traceId: String?, isOffline: Bool = false) {
        self.statusCode = statusCode
        self.traceId = traceId
        self.isOffline = isOffline
    }

    init(_ error: Error) {
        guard case let ErrorResponse.error(code, data, _, underlying) = error else {
            self.init(statusCode: nil, traceId: nil)
            return
        }
        let offline = (underlying as? URLError).map { RetryRules.isOffline($0.code) } ?? false
        // **생성 클라이언트는 음수를 HTTP 코드가 아닌 신호로 쓴다.** 연결 자체가
        // 실패하면 -1, 응답이 HTTP 가 아니면 -2 다
        // (URLSessionImplementations.swift:164,169). 그것을 상태 코드로 그대로 넘기면
        // 서버가 꺼져 있을 때 "요청을 처리하지 못했습니다" 가 뜨고 재시도 버튼이
        // 사라진다(실측) — 정작 재시도가 필요한 상황인데.
        let reachedServer = code > 0
        self.init(
            statusCode: reachedServer ? code : nil,
            traceId: reachedServer ? Self.traceId(from: data) : nil,
            isOffline: !reachedServer && offline
        )
    }

    /// `traceId` 는 `500` 응답에만 실린다. 다른 코드에서는 키 자체가 빠지므로
    /// 디코딩이 실패하거나 nil 이 나오는 것이 정상이다.
    private static func traceId(from data: Data?) -> String? {
        guard let data,
              let body = try? JSONDecoder().decode(ApiError.self, from: data)
        else { return nil }
        return body.traceId
    }
}
