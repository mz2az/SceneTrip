import Foundation

/// V6 목데이터 한 행 = 촬영지 하나 (작품 × 장소).
///
/// 서버 계약(`contracts/openapi/scene-api-v1.yaml`)의 ContentSummary·PlaceSummary 를
/// 합친 모양이다. 생성 클라이언트를 붙일 때(계획서 §5-5) 이 타입은 그쪽으로 갈린다 —
/// 지금은 목데이터를 읽는 것이 유일한 목적이다.
struct SceneRow: Identifiable, Hashable {
    let id: String
    let title: String
    let aliases: [String]
    let category: String
    let network: String
    let year: String
    let genre: String
    let cast: String
    let director: String
    let famousRank: Int?
    let poster: String
    let placeName: String
    let placeType: String
    let address: String
    let lat: Double
    let lng: Double
    let sceneDesc: String

    /// "네트워크 · 연도 · 장르"
    var workMeta: String {
        "\(network) · \(year) · \(genre)"
    }

    /// "김수현, 김지원" → ["김수현", "김지원"]
    var castList: [String] {
        Self.splitNames(cast)
    }

    var directorList: [String] {
        Self.splitNames(director)
    }

    /// 주소 앞 두 토막 — "서울 마포구 독막로2길 9" → ["서울", "마포구"].
    /// 지역 검색이 이 값에 걸린다 (계획서 §3-2 의 마지막 줄).
    var regionTokens: [String] {
        Array(address.split(separator: " ").map(String.init).prefix(2))
    }

    private static func splitNames(_ text: String) -> [String] {
        text.split(whereSeparator: { $0 == "," || $0 == "·" })
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
    }
}

extension SceneRow: Decodable {
    private enum CodingKeys: String, CodingKey {
        case id, title, aliases, category, network, year, genre, cast, director
        case famousRank = "famous_rank"
        case poster
        case placeName = "place_name"
        case placeType = "place_type"
        case address, lat, lng
        case sceneDesc = "scene_desc"
    }

    init(from decoder: Decoder) throws {
        let box = try decoder.container(keyedBy: CodingKeys.self)
        id = try box.decode(String.self, forKey: .id)
        title = try box.decode(String.self, forKey: .title)
        aliases = try box.decodeIfPresent([String].self, forKey: .aliases) ?? []
        category = try box.decodeIfPresent(String.self, forKey: .category) ?? ""
        network = try box.decodeIfPresent(String.self, forKey: .network) ?? ""
        // 연도는 데이터에 따라 숫자로도 문자열로도 들어온다.
        if let asText = try? box.decode(String.self, forKey: .year) {
            year = asText
        } else if let asNumber = try? box.decode(Int.self, forKey: .year) {
            year = String(asNumber)
        } else {
            year = ""
        }
        genre = try box.decodeIfPresent(String.self, forKey: .genre) ?? ""
        cast = try box.decodeIfPresent(String.self, forKey: .cast) ?? ""
        director = try box.decodeIfPresent(String.self, forKey: .director) ?? ""
        famousRank = try box.decodeIfPresent(Int.self, forKey: .famousRank)
        poster = try box.decodeIfPresent(String.self, forKey: .poster) ?? ""
        placeName = try box.decode(String.self, forKey: .placeName)
        placeType = try box.decodeIfPresent(String.self, forKey: .placeType) ?? ""
        address = try box.decodeIfPresent(String.self, forKey: .address) ?? ""
        lat = try box.decode(Double.self, forKey: .lat)
        lng = try box.decode(Double.self, forKey: .lng)
        sceneDesc = try box.decodeIfPresent(String.self, forKey: .sceneDesc) ?? ""
    }
}

/// 작품 하나 = 같은 제목의 행 묶음.
struct Work: Identifiable, Hashable {
    let title: String
    let rows: [SceneRow]

    var id: String {
        title
    }

    var head: SceneRow {
        rows[0]
    }

    var famousRank: Int {
        head.famousRank ?? 999
    }

    /// 촬영지 수 = **행 수**다. 장소명으로 묶지 않는다 — 목데이터의 도깨비는 58 행에
    /// 고유 장소명이 57 개인데, 겹치는 'BBQ' 가 주소가 다른 두 지점이라 촬영지로는
    /// 둘이 맞다. 계획서 §3-5 가 예로 드는 숫자도 58 이다.
    var placeCount: Int {
        rows.count
    }
}
