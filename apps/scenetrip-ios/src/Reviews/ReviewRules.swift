import Foundation
import SceneApiClient

/// 리뷰 화면의 순수 규칙 (MZ2AZ-363). 화면 없이 시험한다.
enum ReviewRules {
    /// 별점 줄의 세 상태. **서버가 별점을 안 실어 주면 줄 자체가 없다** — 없는 기능을 있는 척하지 않는다
    /// (서버 구현 MZ2AZ-362 전의 응답, 옛 서버).
    enum Line: Equatable {
        case hidden
        /// 「아직 리뷰가 없어요」
        case empty
        /// 「★4.6 · 리뷰 128」
        case rated(average: String, count: Int)
    }

    static func line(for rating: RatingSummary?) -> Line {
        guard let rating else { return .hidden }
        guard rating.count > 0 else { return .empty }
        return .rated(average: average(rating.average), count: rating.count)
    }

    /// 평균을 소수 한 자리로. 리뷰가 있는데 평균이 비어 오면 「-」.
    static func average(_ value: Double?) -> String {
        guard let value else { return "-" }
        return String(format: "%.1f", value)
    }

    /// 「리뷰 128」. 영어는 하나일 때 「1 review」 — 번역 표의 `리뷰 %d|하나`.
    static func countText(_ count: Int) -> String {
        String(format: count == 1 ? tr("리뷰 %d", at: "하나") : tr("리뷰 %d"), count)
    }

    /// 낭독용 별점 — 리뷰 한 건의 정수 별점은 「4」, 평균은 「4.6」.
    static func starValue(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : average(value)
    }

    /// 작성자 이름 — 탈퇴하면 서버가 `author` 를 비워 보낸다.
    static func authorName(_ author: ReviewAuthor?) -> String {
        author?.nickname ?? tr("탈퇴한 사용자")
    }

    /// 고친 적이 있는가 — 쓴 때와 고친 때가 다르다. 같은 초 안의 차이는 고친 것으로 치지 않는다.
    static func edited(createdAt: Date, updatedAt: Date) -> Bool {
        updatedAt.timeIntervalSince(createdAt) >= 1
    }

    /// 점수 막대 — 5점부터 1점까지, 각 점수가 전체에서 차지하는 몫(0~1).
    /// `distribution` 은 길이 5, 첫 칸이 1점이다. 길이가 다르면 빈 막대로 둔다.
    static func bars(_ distribution: [Int]) -> [(score: Int, count: Int, share: Double)] {
        let counts = distribution.count == 5 ? distribution : Array(repeating: 0, count: 5)
        let total = max(counts.reduce(0, +), 1)
        return (1 ... 5).reversed().map { score in
            let count = max(counts[score - 1], 0)
            return (score, count, Double(count) / Double(total))
        }
    }

    enum Star: Equatable {
        case full
        case half
        case empty

        var symbol: String {
            switch self {
            case .full: "star.fill"
            case .half: "star.leadinghalf.filled"
            case .empty: "star"
            }
        }
    }

    /// 별 다섯 개의 모양 — 반 개 단위로 가장 가까운 쪽. 4.6 → 넷 + 반, 4.8 → 다섯, 4.2 → 넷.
    static func stars(for value: Double) -> [Star] {
        (1 ... 5).map { index in
            let position = Double(index)
            if value >= position - 0.25 {
                return .full
            }
            return value >= position - 0.75 ? .half : .empty
        }
    }

    /// 화면 낭독용 한 문장 — 「별점 4.6점, 리뷰 128」.
    static func spoken(_ rating: RatingSummary) -> String {
        guard rating.count > 0 else { return tr("아직 리뷰가 없어요") }
        return String(format: tr("별점 %@점"), average(rating.average)) + ", " + countText(rating.count)
    }

    /// 다음 쪽이 있는가.
    static func hasMore(loaded: Int, total: Int) -> Bool {
        loaded < total
    }
}
