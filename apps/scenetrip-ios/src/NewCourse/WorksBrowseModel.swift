import Foundation
import SceneApiClient

/// 작품 고르기 단계(A2)의 작품 목록 받기 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §1).
///
/// 서버의 작품 전부를 30개씩 이어 받고(`GET /contents?limit=30&offset=…`), 검색칸 글자로 찾는다(`q`).
/// 규칙은 `WorksBrowseRules`, 여기는 서버에 묻고 그 답을 쌓는다. `RouteStore.works`(인기 30편)는 다른 화면이 쓰므로
/// 건드리지 않는다.
@MainActor
final class WorksBrowseModel: ObservableObject {
    /// 지금 보이는 목록 — 검색 중이면 검색 결과, 아니면 원래 목록. 서버 순서 그대로(찜 먼저는 화면이 정한다).
    @Published private(set) var items: [ContentSummary] = []
    /// 검색하지 않은 원래 목록 — 「인기 작품으로 짜요」 의 앞 3편이 여기서 나온다.
    @Published private(set) var popular: [ContentSummary] = []
    /// 지금 보이는 목록의 검색어. 원래 목록이면 nil.
    @Published private(set) var query: String?
    /// 첫 쪽(또는 검색의 첫 쪽)을 기다리는 중.
    @Published private(set) var loadingFirst = false
    @Published private(set) var loadingMore = false
    @Published private(set) var failed = false

    private var paging = Paging()
    /// 원래 목록의 쪽 넘김 — 검색을 비우면 다시 받지 않고 이어서 쓴다.
    private var popularPaging = Paging()
    /// 늦게 온 답이 바뀐 검색어의 목록에 섞이지 않게 — 검색어가 바뀔 때마다 올린다.
    private var generation = 0
    private var debounceTask: Task<Void, Never>?
    private var pageTask: Task<Void, Never>?

    /// 받은 것이 있고 더 받을 수 있는가.
    var hasMore: Bool {
        paging.hasMore
    }

    /// 화면이 떴다 — 원래 목록의 첫 쪽.
    func start() async {
        guard popular.isEmpty, query == nil, !loadingFirst else { return }
        await loadPage(first: true)
    }

    /// 검색칸 글자가 바뀌었다 — 0.3초 멈추면 찾는다. 비우면 곧바로 원래 목록으로.
    func search(_ text: String) {
        debounceTask?.cancel()
        let next = WorksBrowseRules.query(text)
        guard next != query else { return }
        guard let next else {
            showPopular()
            return
        }
        debounceTask = Task { [weak self] in
            try? await Task.sleep(for: WorksBrowseRules.debounce)
            guard !Task.isCancelled, let self else { return }
            beginSearch(next)
        }
    }

    /// 포스터 하나가 화면에 나타났다 — 마지막 한 줄 안이면 다음 쪽.
    func appeared(index: Int, columns: Int) {
        guard WorksBrowseRules.shouldLoadMore(appeared: index, loaded: items.count, columns: columns) else { return }
        loadMore()
    }

    /// 다음 쪽(실패 뒤 「다시 시도」 도 이것).
    func loadMore() {
        guard !loadingFirst, !loadingMore, pageTask == nil, paging.hasMore else { return }
        pageTask = Task { [weak self] in
            guard let self else { return }
            await loadPage(first: items.isEmpty)
        }
    }

    private func showPopular() {
        generation += 1
        pageTask?.cancel()
        pageTask = nil
        query = nil
        failed = false
        loadingFirst = false
        loadingMore = false
        items = popular
        paging = popularPaging
        if popular.isEmpty {
            pageTask = Task { [weak self] in await self?.loadPage(first: true) }
        }
    }

    private func beginSearch(_ next: String) {
        generation += 1
        pageTask?.cancel()
        pageTask = nil
        query = next
        items = []
        paging = Paging()
        failed = false
        loadingMore = false
        pageTask = Task { [weak self] in await self?.loadPage(first: true) }
    }

    private func loadPage(first: Bool) async {
        guard let request = WorksBrowseRules.nextRequest(query: query, paging: paging) else {
            pageTask = nil
            return
        }
        let mine = generation
        if first {
            loadingFirst = true
        } else {
            loadingMore = true
        }
        failed = false
        let result = await PageFetch.attempt {
            try await ContentsAPI.listContents(q: request.query, limit: request.limit, offset: request.offset)
        }
        guard mine == generation else { return }
        loadingFirst = false
        loadingMore = false
        pageTask = nil
        switch result {
        case let .page(page):
            paging = paging.advanced(received: page.items.count, total: page.total)
            items = Paging.merged(items, with: page.items, by: \.id)
            if query == nil {
                popular = items
                popularPaging = paging
            }
        case .cancelled:
            break
        case .failed:
            failed = true
        }
    }
}
