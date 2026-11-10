import Foundation
import SceneApiClient

// 작품으로 여행 짜기 — 작품 고르기 단계(A2)의 작품 목록 규칙 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §1).
// 서버의 작품 전부를 30개씩 이어 받고, 검색칸으로 찾고, 고른 작품은 검색·쪽 넘김과 상관없이 남긴다.
// 고르기·찜 미리 고르기는 `NewCourseRules.swift` 의 `WorkPickRules` 그대로.

/// 작품 목록 한 쪽 요청 — `GET /contents?q=…&limit=…&offset=…`.
struct WorksPageRequest: Equatable {
    /// 검색어. 검색칸이 비었으면 nil(원래 목록).
    let query: String?
    let limit: Int
    let offset: Int
}

enum WorksBrowseRules {
    /// 한 번에 받는 작품 수.
    static let pageSize = 30
    /// 검색칸에 글자를 넣고 이만큼 멈추면 찾는다.
    static let debounce: Duration = .milliseconds(300)
    /// 계약의 `q` 최대 길이.
    static let maxQueryLength = 100
    /// 「인기 작품으로 짜요」 — 아무것도 안 고르면 앞에서 이만큼의 제목을 보낸다.
    static let popularFallbackCount = 3

    /// 검색칸 글 → 서버에 보낼 검색어. 앞뒤 공백을 떼고, 비면 nil(원래 목록), 길면 `maxQueryLength` 에서 자른다.
    static func query(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxQueryLength))
    }

    /// 다음에 받을 쪽. `total` 에 닿았거나 빈 쪽을 받았으면(`paging.hasMore == false`) nil.
    static func nextRequest(query: String?, paging: Paging) -> WorksPageRequest? {
        guard paging.hasMore else { return nil }
        return WorksPageRequest(query: query, limit: pageSize, offset: paging.offset)
    }

    /// 포스터 하나가 화면에 나타났다 — 그것이 목록 마지막 한 줄 안이면 다음 쪽을 받을 때다.
    /// - Parameters:
    ///   - index: 나타난 포스터의 자리(0부터).
    ///   - loaded: 지금 목록에 쌓인 수.
    ///   - columns: 한 줄의 포스터 수.
    static func shouldLoadMore(appeared index: Int, loaded: Int, columns: Int) -> Bool {
        guard loaded > 0 else { return false }
        return index >= loaded - max(columns, 1)
    }

    /// 화면에 그릴 순서 — 받은 것 안에서 찜한 작품 먼저, 그다음 서버 순서. 각 무리 안의 순서는 서버 순서 그대로.
    static func ordered(_ works: [ContentSummary], liked: Set<Int64>) -> [ContentSummary] {
        works.filter { liked.contains($0.id) } + works.filter { !liked.contains($0.id) }
    }

    /// 고른 작품의 기억 — 검색 결과·지금 목록에 없어도 고른 것은 남긴다.
    /// `kept`(앞서 기억한 것) 가운데 아직 고른 것을 그 순서대로, 그다음 `shown`(지금 보이는 목록)에서 새로 고른 것을 그 순서대로.
    /// 고르기를 푼 작품은 뺀다. 같은 작품은 한 번만.
    static func keptPicks(selected: Set<Int64>, kept: [ContentSummary], shown: [ContentSummary]) -> [ContentSummary] {
        var seen = Set<Int64>()
        return (kept + shown).filter { selected.contains($0.id) && seen.insert($0.id).inserted }
    }

    /// AI 일정 요청(`POST /guide/plan`)의 `titles` — 고른 작품의 제목(최대 `WorkPickRules.maxWorks`).
    /// 아무것도 안 골랐으면 `popular`(검색하지 않은 원래 목록) 앞 `popularFallbackCount` 개의 제목.
    static func requestTitles(picked: [ContentSummary], popular: [ContentSummary]) -> [String] {
        if picked.isEmpty {
            return popular.prefix(popularFallbackCount).map(\.title)
        }
        return picked.prefix(WorkPickRules.maxWorks).map(\.title)
    }
}
