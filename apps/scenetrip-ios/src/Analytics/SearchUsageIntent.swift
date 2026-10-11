import Foundation
import SceneApiClient

/// 확정한 검색과 그 검색의 첫 성공 응답을 연결한다. 재시도에는 만들지 않는다(MZ2AZ-408).
struct SearchUsageIntent {
    let query: String
    let target: UsageEntityType

    init?(query: String, target: UsageEntityType) {
        let term = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (1 ... 100).contains(term.count), target == .content || target == .place else { return nil }
        self.query = term
        self.target = target
    }

    func event(contents: ContentList, places: PlaceList) -> UsageEvent? {
        // 검색 확정은 분류 칩을 해제한다. 탭에 표시할 서버 총수이며 페이지의 행 수가 아니다.
        let count = target == .content ? contents.total : places.total
        guard count >= 0 else { return nil }
        return UsageEvent(type: .search, query: query, resultCount: count)
    }
}
