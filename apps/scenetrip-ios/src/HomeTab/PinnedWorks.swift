import Foundation
import SceneApiClient

/// 「지금 뜨는 작품」 맨 앞에 무조건 세우는 작품 (MZ2AZ-547 H6, 웹 MZ2AZ-534 `pinnedWorks.ts`, 사용자 결정 2026-11-07).
///
/// 나머지는 서버 인기순 그대로이고 고정 작품만 빼서 앞에 둔다(중복 없음). 받은 목록에 없으면 `GET /contents/{id}` 로
/// 한 번 받아 넣는다. 그것도 실패하거나 숨긴 작품이면 조용히 기존 순서다. 홈 선반과 전체 보기 격자가 같이 쓴다.
enum PinnedWorks {
    /// 맨 앞 고정 작품 id — 적힌 순서대로. 32 = 「케이팝 데몬 헌터스」(DEV·PRD 같은 id).
    static let ids: [Int64] = [32]

    /// 목록에 없는 고정 작품 id — 따로 받아야 하는 것(적힌 순서).
    static func missing(in list: [ContentSummary], ids: [Int64]) -> [Int64] {
        let present = Set(list.map(\.id))
        return ids.filter { !present.contains($0) }
    }

    /// 상세 → 목록 줄. 숨긴 작품(`delisted == true`)이면 nil — 목록에 안 나오는 작품을 앞에 세우지 않는다.
    static func summary(of detail: ContentDetail) -> ContentSummary? {
        guard detail.delisted != true else { return nil }
        return ContentSummary(
            id: detail.id, category: detail.category, title: detail.title, posterUrl: detail.posterUrl,
            producerUrl: detail.producerUrl, broadcaster: detail.broadcaster, releaseYear: detail.releaseYear,
            genres: detail.genres, placeCount: detail.placeCount, delisted: detail.delisted
        )
    }

    /// 고정 작품을 적힌 순서로 앞에(목록에 있으면 그것, 없으면 `fetched` 의 것, 둘 다 없으면 건너뜀),
    /// 나머지는 받은 순서 그대로(고정 작품은 뺀다).
    static func arrange(_ list: [ContentSummary], fetched: [Int64: ContentSummary], ids: [Int64]) -> [ContentSummary] {
        var front: [ContentSummary] = []
        for id in ids where !front.contains(where: { $0.id == id }) {
            if let work = list.first(where: { $0.id == id }) ?? fetched[id] {
                front.append(work)
            }
        }
        let frontIds = Set(front.map(\.id))
        return front + list.filter { !frontIds.contains($0.id) }
    }

    /// 목록에 없는 고정 작품을 `fetch` 로 받아 `arrange` 한다. `fetch` 가 nil 이면(실패) 그 작품은 건너뛴다.
    static func apply(
        to list: [ContentSummary], ids: [Int64] = ids, fetch: (Int64) async -> ContentDetail?
    ) async -> [ContentSummary] {
        var fetched: [Int64: ContentSummary] = [:]
        for id in missing(in: list, ids: ids) {
            if let detail = await fetch(id), let work = summary(of: detail) {
                fetched[id] = work
            }
        }
        return arrange(list, fetched: fetched, ids: ids)
    }
}
