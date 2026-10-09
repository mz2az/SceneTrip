import Foundation
import SceneApiClient

/// 코스 편집의 「장소 검색」 이 서버에 묻는 것 (MZ2AZ-372).
///
/// 전에는 `RouteStore` 가 받아 둔 촬영지(인기 상위 200곳) 안에서 글자를 걸렀다. 촬영지가 155곳일 때는 그것이
/// 전부라 맞았는데, 486곳이 되자 「Gyeongbok」 을 쳐도 결과가 없었다 — 경복궁이 그 200곳 밖이었다.
/// 이제 **서버 검색**(`GET /places?q=`)이다. 검색 탭의 장소 탭과 같은 창구라 같은 말에 같은 곳이 나온다.
///
/// - **글자마다 부르지 않는다.** 입력이 멎고 `debounce` 뒤에 한 번. 그 사이 또 치면 앞의 것을 버린다.
/// - **늦게 온 답이 새 답을 덮지 않는다.** 새로 물으면 앞 요청을 취소하고, 취소된 것은 화면에 닿지 않는다.
/// - 재시도는 공통 계층이 한다 — 조회라서 끊김·502·503·504 에 세 번(`RetryRules`). 여기서 또 돌리지 않는다.
/// - 빈 검색어면 인기순 첫 쪽을 보인다 — 빈 화면보다 무엇이든 있는 편이 다음 행동을 부른다.
@MainActor
final class RoutePlaceSearch: ObservableObject {
    enum Phase: Equatable {
        case loading
        case loaded
        case failed(ApiFailure)
    }

    /// 입력이 멎고 서버에 묻기까지.
    nonisolated static let debounce: Duration = .milliseconds(350)
    nonisolated static let pageSize = 40
    /// 계약의 `q` 최대 길이.
    nonisolated static let maxLength = 100

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var results: [PlaceSummary] = []
    @Published private(set) var paging = Paging()
    @Published private(set) var loadingMore = false
    @Published private(set) var moreFailed = false

    private var task: Task<Void, Never>?
    private var keyword: String?
    /// 지금까지 결과에 나온 곳. 검색어를 바꿔 목록에서 사라진 뒤에도 **고른 것**을 되짚는다.
    private var seen: [Int64: PlaceSummary] = [:]

    /// 입력을 서버에 보낼 검색어로. 앞뒤 공백을 떼고, 비었으면 nil(전체), 길면 계약의 상한에서 자른다.
    nonisolated static func keyword(from text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : String(trimmed.prefix(maxLength))
    }

    /// 기다렸다 물을 것인가. 빈 검색어(처음 열 때·지웠을 때)는 바로 보인다 — 기다릴 타자가 없다.
    nonisolated static func delay(for keyword: String?) -> Duration {
        keyword == nil ? .zero : debounce
    }

    /// 입력이 바뀔 때마다 부른다.
    func search(_ text: String) {
        let wanted = Self.keyword(from: text)
        task?.cancel()
        phase = .loading
        loadingMore = false
        moreFailed = false
        task = Task { [weak self] in
            let wait = Self.delay(for: wanted)
            if wait > .zero {
                try? await Task.sleep(for: wait)
            }
            guard !Task.isCancelled else { return }
            do {
                let page = try await PlacesAPI.listPlaces(q: wanted, limit: Self.pageSize)
                guard !Task.isCancelled, let self else { return }
                keyword = wanted
                results = page.items
                paging = Paging().advanced(received: page.items.count, total: page.total)
                remember(page.items)
                phase = .loaded
            } catch {
                // 다음 글자를 쳐서 끊긴 것은 실패가 아니다.
                guard !Task.isCancelled, !PageFetchRules.isCancellation(error) else { return }
                self?.phase = .failed(ApiFailure(error))
            }
        }
    }

    /// 목록 끝이 보이면 다음 쪽.
    func loadMore() {
        guard phase == .loaded, !loadingMore, paging.hasMore else { return }
        loadingMore = true
        moreFailed = false
        let asked = keyword
        let offset = paging.offset
        let running = task
        Task { [weak self] in
            let fetch = await PageFetch.attempt {
                try await PlacesAPI.listPlaces(q: asked, limit: Self.pageSize, offset: offset)
            }
            // 받는 사이 검색어가 바뀌었으면 버린다 — 옛 검색의 줄이 새 목록에 붙는다.
            guard let self, task == running, phase == .loaded else { return }
            loadingMore = false
            switch fetch {
            case let .page(page):
                results = Paging.merged(results, with: page.items, by: \.id)
                paging = paging.advanced(received: page.items.count, total: page.total)
                remember(page.items)
            case .failed:
                moreFailed = true
            case .cancelled:
                break
            }
        }
    }

    func place(_ id: Int64) -> PlaceSummary? {
        seen[id]
    }

    private func remember(_ places: [PlaceSummary]) {
        for place in places {
            seen[place.id] = place
        }
    }
}
