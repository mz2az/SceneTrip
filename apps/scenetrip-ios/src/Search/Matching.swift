import Foundation

/// 검색어 하나가 문자열에 걸리는 정도. 낮을수록 앞에 온다.
///
/// 계획서 §3-3 의 "앞글자 일치 > 단어 앞 일치 > 중간 포함". 걸리지 않으면 nil 이다.
/// 서버 명세에도 같은 규칙이 적혀 있어(§4 대조표) 화면과 서버가 같은 순서를 낸다.
enum MatchScore {
    static let prefix = 0
    static let wordPrefix = 1
    static let contains = 2

    static func of(_ text: String, _ query: String) -> Int? {
        let haystack = text.lowercased()
        let needle = query.lowercased().trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return nil }
        if haystack.hasPrefix(needle) {
            return prefix
        }
        for word in haystack.split(separator: " ") where word.hasPrefix(needle) {
            return wordPrefix
        }
        if haystack.contains(needle) {
            return contains
        }
        return nil
    }
}

/// 검색이 걸리는 범위 (계획서 §3-2).
///
/// | 입력 | 작품 탭 | 장소 탭 |
/// | 작품 제목·별칭 | 그 작품 | 그 작품의 촬영지 |
/// | 배우·감독 이름 | 그 사람의 작품 | **그 작품들의 촬영지 전부** |
/// | 장소명 | — | 그 장소 |
/// | 지역 | — | 그 지역의 촬영지 |
///
/// 배우로 검색했을 때 장소 탭까지 채워지는 것이 핵심이다. 서버도 같은 판단을 했다 —
/// `GET /places` 의 `q` 가 출연진 이름에도 걸린다 (MZ2AZ-167, 계획서 §4).
enum SceneSearch {
    /// 촬영지 한 건이 검색어에 걸리는지. 걸리면 가장 좋은 점수를 돌려준다.
    static func score(_ row: SceneRow, _ query: String) -> Int? {
        var best: Int?
        func consider(_ text: String) {
            guard let score = MatchScore.of(text, query) else { return }
            best = min(best ?? score, score)
        }
        consider(row.placeName)
        consider(row.title)
        row.aliases.forEach(consider)
        row.castList.forEach(consider)
        row.directorList.forEach(consider)
        row.regionTokens.forEach(consider)
        return best
    }

    static func matches(_ row: SceneRow, _ query: String) -> Bool {
        score(row, query) != nil
    }

    /// 작품 탭. 인기도순으로 동점을 가른다.
    static func works(_ all: [Work], query: String) -> [Work] {
        guard !query.trimmingCharacters(in: .whitespaces).isEmpty else { return all }
        return all
            .compactMap { work -> (work: Work, score: Int)? in
                let best = work.rows.compactMap { row -> Int? in
                    var rowBest: Int?
                    if let score = MatchScore.of(row.title, query) {
                        rowBest = score
                    }
                    for alias in row.aliases {
                        if let score = MatchScore.of(alias, query) {
                            rowBest = min(rowBest ?? score, score)
                        }
                    }
                    for name in row.castList + row.directorList {
                        if let score = MatchScore.of(name, query) {
                            rowBest = min(rowBest ?? score, score)
                        }
                    }
                    return rowBest
                }.min()
                return best.map { (work: work, score: $0) }
            }
            .sorted { lhs, rhs in
                lhs.score == rhs.score
                    ? lhs.work.famousRank < rhs.work.famousRank
                    : lhs.score < rhs.score
            }
            .map(\.work)
    }

    /// 작품이 **배우로** 걸렸는지 — 제목으로 걸린 것과 구분해 배지를 단다 (§3-3).
    static func castBadge(_ work: Work, query: String) -> String? {
        guard MatchScore.of(work.title, query) == nil,
              work.head.aliases.allSatisfy({ MatchScore.of($0, query) == nil })
        else { return nil }
        let names = work.head.castList + work.head.directorList
        for name in names where MatchScore.of(name, query) != nil {
            return "출연 \(name)"
        }
        return nil
    }
}

/// 자동완성 한 줄 (§3-3 — 작품 → 인물 → 장소 순).
struct Suggestion: Identifiable, Hashable {
    enum Kind: Int { case work = 0, person = 1, place = 2 }

    let kind: Kind
    let text: String
    let detail: String
    let score: Int

    var id: String {
        "\(kind.rawValue)-\(text)"
    }

    var symbol: String {
        switch kind {
        case .work: "film"
        case .person: "person"
        case .place: "mappin.and.ellipse"
        }
    }
}

extension SceneSearch {
    /// 검색창에 글자를 칠 때 뜨는 목록. 작품 → 인물 → 장소 순으로 묶고,
    /// 묶음 안에서는 앞글자 일치가 먼저다.
    static func suggestions(
        rows: [SceneRow], works: [Work], query: String, limit: Int = 8
    ) -> [Suggestion] {
        let needle = query.trimmingCharacters(in: .whitespaces)
        guard !needle.isEmpty else { return [] }

        let merged = workSuggestions(works, needle)
            + personSuggestions(rows, works, needle)
            + placeSuggestions(rows, needle)

        return Array(
            merged.sorted { lhs, rhs in
                lhs.kind.rawValue == rhs.kind.rawValue
                    ? lhs.score < rhs.score
                    : lhs.kind.rawValue < rhs.kind.rawValue
            }.prefix(limit)
        )
    }

    private static func workSuggestions(_ works: [Work], _ needle: String) -> [Suggestion] {
        var out: [Suggestion] = []
        for work in works {
            var best: Int?
            if let score = MatchScore.of(work.title, needle) {
                best = score
            }
            for alias in work.head.aliases {
                if let score = MatchScore.of(alias, needle) {
                    best = min(best ?? score, score)
                }
            }
            if let score = best {
                out.append(.init(kind: .work, text: work.title,
                                 detail: "촬영지 \(work.placeCount)", score: score))
            }
        }
        return out
    }

    private static func personSuggestions(
        _ rows: [SceneRow], _ works: [Work], _ needle: String
    ) -> [Suggestion] {
        var out: [Suggestion] = []
        var seen = Set<String>()
        for row in rows {
            for name in row.castList + row.directorList {
                guard let score = MatchScore.of(name, needle),
                      seen.insert(name).inserted else { continue }
                let count = works.filter {
                    $0.head.castList.contains(name) || $0.head.directorList.contains(name)
                }.count
                out.append(.init(kind: .person, text: name,
                                 detail: "작품 \(max(count, 1))", score: score))
            }
        }
        return out
    }

    private static func placeSuggestions(
        _ rows: [SceneRow], _ needle: String
    ) -> [Suggestion] {
        var out: [Suggestion] = []
        var seen = Set<String>()
        for row in rows {
            guard let score = MatchScore.of(row.placeName, needle),
                  seen.insert(row.placeName).inserted else { continue }
            out.append(.init(kind: .place, text: row.placeName,
                             detail: row.regionTokens.joined(separator: " "), score: score))
        }
        return out
    }
}
