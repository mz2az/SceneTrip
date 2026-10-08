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

    /// 글의 상한(계약 `ReviewInput.body`).
    static let bodyLimit = 2000

    /// 서버에 보낼 글 — 앞뒤 공백을 뗀다. 빈 글은 nil(별점만 남긴 리뷰).
    static func normalizedBody(_ raw: String) -> String? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }

    /// 저장할 수 있는가 — 별점은 필수(1~5), 글은 선택이되 상한을 넘지 않는다.
    static func canSave(rating: Int, body: String) -> Bool {
        (1 ... 5).contains(rating) && bodyLength(body) <= bodyLimit
    }

    /// 글의 길이 — **서버가 세는 법**(UTF-16 단위)으로 센다. 글자 덩이로 세면 이모지 1,001개가
    /// 「1001/2000」 으로 보이는데 서버는 2,002 로 보고 거절한다.
    static func bodyLength(_ raw: String) -> Int {
        normalizedBody(raw)?.utf16.count ?? 0
    }

    /// 고칠 것이 있는가 — 이미 쓴 리뷰와 별점·글이 같으면 다시 보낼 이유가 없다.
    static func changed(from existing: Review?, rating: Int, body: String) -> Bool {
        guard let existing else { return true }
        return existing.rating != rating || existing.body != normalizedBody(body)
    }

    /// 저장이 「사진을 다시 올려 주세요」(`REVIEW_PHOTO_INVALID`)로 끝났는데 **사실은 저장돼 있는가** (MZ2AZ-366).
    ///
    /// 사진 붙은 리뷰의 PUT 은 두 번 보내면 안 된다 — 첫 요청이 임시 사진을 제자리로 옮기므로, 응답만 잃고
    /// 다시 보낸(또는 사람이 다시 누른) 두 번째는 「그런 사진 없다」 로 거절된다. 그때 서버의 내 리뷰를 다시
    /// 읽어 방금 보낸 것과 같으면 저장된 것이다 — 별점과 글이 같고 **사진이 같은 순서로 같다.**
    ///
    /// 사진은 장수가 아니라 **파일 이름**으로 견준다. 서버는 `uploads/tmp/<이름>` 을 `reviews/<이름>` 으로
    /// 옮기므로 키의 마지막 조각이 그대로 남는다. 장수만 보면, 고치기에서 사진을 같은 장수로 바꿔 끼웠다가
    /// 진짜로 거절됐을 때(임시 사진 만료) 옛 리뷰를 「저장됨」 으로 읽어 새 사진이 말없이 사라진다.
    static func landed(_ saved: Review?, rating: Int, body: String?, photoKeys: [String]) -> Bool {
        guard let saved else { return false }
        return saved.rating == rating
            && normalizedBody(saved.body ?? "") == body
            && saved.photos.map { photoName($0.key) } == photoKeys.map(photoName)
    }

    /// 사진 키의 파일 이름 — 마지막 경로 조각.
    static func photoName(_ key: String) -> String {
        key.split(separator: "/").last.map(String.init) ?? key
    }

    /// 다음 쪽이 있는가.
    static func hasMore(loaded: Int, total: Int) -> Bool {
        loaded < total
    }
}
