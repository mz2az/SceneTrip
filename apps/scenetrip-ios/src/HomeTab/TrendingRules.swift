import Foundation
import SceneApiClient

/// 「지금 뜨는 작품」 전체 보기 격자가 인기순 작품을 끝까지 받는 규칙 (MZ2AZ-547 H6, 웹 MZ2AZ-534 `trendingModel.ts`).
///
/// 새 끝점은 없다 — 선반과 같은 `GET /contents`(검색어 없음 = 인기도 내림차순)를 한도 100 으로 `offset` 이어 받아 `total` 까지.
enum TrendingRules {
    /// 한 번에 받는 수 — 계약의 `limit` 상한.
    static let pageLimit = 100
    /// 이어 받는 횟수 상한 — 서버가 total 을 잘못 줘도 끝없이 돌지 않게.
    static let maxPages = 5

    /// 받은 쪽을 이어 붙인다 — 받는 사이 순위가 바뀌어 같은 작품이 두 쪽에 걸치면 먼저 받은 자리를 지킨다.
    static func merge(_ collected: [ContentSummary], page: [ContentSummary]) -> [ContentSummary] {
        var seen = Set(collected.map(\.id))
        var merged = collected
        for work in page where !seen.contains(work.id) {
            seen.insert(work.id)
            merged.append(work)
        }
        return merged
    }

    /// 한 쪽 더 받는가 — 방금 받은 쪽이 꽉 찼고(`pageLimit`) 아직 `total` 에 못 미칠 때.
    static func shouldContinue(pageCount: Int, collected: Int, total: Int) -> Bool {
        pageCount >= pageLimit && collected < total
    }

    /// 끝까지 받아 고정 작품을 앞에 세운다. `fetchPage(offset)` 는 (그 쪽의 작품, total).
    /// 쪽 받기가 실패하면 던진다(화면이 「다시 시도」 를 띄운다). 고정 작품 받기 실패는 조용히 넘어간다.
    static func load(
        fetchPage: (Int) async throws -> ([ContentSummary], Int),
        fetchDetail: (Int64) async -> ContentDetail?
    ) async throws -> [ContentSummary] {
        var works: [ContentSummary] = []
        for _ in 0 ..< maxPages {
            // 다음 쪽 위치는 걸러 낸 뒤의 수 — 웹(`offset: works.length`)과 같다.
            let (page, total) = try await fetchPage(works.count)
            works = merge(works, page: page)
            guard shouldContinue(pageCount: page.count, collected: works.count, total: total) else { break }
        }
        return await PinnedWorks.apply(to: works, fetch: fetchDetail)
    }
}
