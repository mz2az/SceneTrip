import CoreLocation
import Foundation
import SceneApiClient

/// 여행 가이드 챗봇 — **도구를 부르는 로컬 LLM** (MZ2AZ-201 · MZ2AZ-223).
///
/// 프로토타입(`SceneTrip_navi`) v6 를 앱으로 옮긴 것이다. 설계에서 가져올 것이
/// 넷 있고, 넷 다 프로토타입이 실측으로 얻은 것이다.
///
/// ## 1. RAG 가 아니라 **도구 호출**이다
///
/// 「반경 300 m 안 음식점을 리뷰순으로」는 의미 검색이 아니라 **조건 질의**다 —
/// 좌표로 거르고 점수로 정렬하는 계산이라 코드가 정확하고 빠르다. 임베딩도 벡터
/// 검색도 쓰지 않는다. 모델은 말을 알아듣고 `poi_nearby(300, 음식)` 를 부를 뿐이다.
///
/// ## 2. 모델에게 **좌표를 주지 않는다**
///
/// 화면에는 주고 모델에게만 지운다. 좌표를 보면 8B 는 스스로 거리를 재려 드는데
/// 하버사인을 틀린다(프로토타입 실측). 받는 쪽이 다르니 서버가 따로 담아 준다.
///
/// ## 3. **지어내지 못하게 막는다**
///
/// `poi_nearby` 가 준 목록에 없는 곳은 존재하지 않는 것으로 다룬다. 그냥 두면
/// 8B 는 `poi_id: "123456"` 같은 값을 지어내고 없는 계단 수를 말한다.
///
/// ## 4. **부른 도구를 함께 보인다**
///
/// 답만 보면 그럴듯한 헛소리를 걸러 낼 수 없다. 「무엇을 근거로 말하는가」가
/// 보여야 한다.
///
/// ## main 에서는 아직 열리지 않는다 (2026-09-02, MZ2AZ-297)
///
/// `poi_nearby` 는 **47만 건에서 고르는 질의**라 앱 안에서 할 수 없고, 우리
/// 백엔드에 그 자리가 아직 없다(계약에도 없다 — MZ2AZ-283·284, 챗봇은 285).
/// **main 의 프론트는 백엔드 계약에만 의존한다** — 없는 API 를 프로토타입
/// 직접 호출로 메우지 않고, 화면이 「준비 중」이라고 정직하게 말한다.
/// 프로토타입을 직접 부르는 판은 navi-proto 브랜치에만 있다.
enum RouteGuide {
    enum Failure: LocalizedError {
        case notReady
        case server(String)

        var errorDescription: String? {
            switch self {
            case .notReady:
                "여행 가이드는 준비 중이에요 — 백엔드 API(MZ2AZ-283·284·285)가 서면 열립니다"
            case let .server(message):
                message
            }
        }
    }

    /// 한 번 주고받는다.
    ///
    /// - Parameters:
    ///   - history: 지금까지의 대화. 서버가 맥락을 이어 준다.
    ///   - here: 지금 위치. **없으면 부를 수 없다** — 「주변」이 어디인지 모른다.
    ///   - sessionId: 대화를 잇는 열쇠. 앞 턴에서 보여 준 장소를 서버가 기억한다.
    /// 화면 상태. 모델이 「1번 주변 맛집」을 알아듣게 하는 재료다.
    ///
    /// **좌표는 안 넣는다** — 서버가 번호와 이름만 모델에게 주고, 번호를 좌표로
    /// 바꾸는 것은 서버가 한다. 8B 에게 좌표를 보이면 스스로 거리를 재려 든다.
    struct Context {
        /// 코스에 담긴 지점. 지도의 번호 핀과 **같은 번호**여야 한다.
        let stops: [Spot]
        /// 지금 고른 곳. 「여기 주변」이 가리키는 자리.
        let picked: Spot?

        struct Spot {
            let number: Int
            let name: String
            let kind: String?
            let latitude: Double
            let longitude: Double
        }

        var json: [String: Any] {
            var out: [String: Any] = [
                "cart": stops.map { spot in
                    [
                        "no": spot.number, "name": spot.name,
                        "kind": spot.kind ?? "",
                        "lat": spot.latitude, "lng": spot.longitude,
                    ]
                },
            ]
            if let picked {
                out["picked"] = [
                    "name": picked.name,
                    "lat": picked.latitude, "lng": picked.longitude,
                ]
            }
            return out
        }
    }

    static func ask(
        history _: [Turn],
        here _: CLLocationCoordinate2D,
        sessionId _: String,
        context _: Context? = nil
    ) async throws -> Answer {
        // 백엔드가 서기 전에는 묻지 않는다 — 화면은 Failure 의 문구를 그대로 보인다.
        throw Failure.notReady
    }

    /// 지도 범위 안의 편의시설 — **백엔드 계약** `GET /pois` (MZ2AZ-314, 2026-09-05 연결).
    ///
    /// 뷰포트(`bbox`)와 화면 중심(`lat`·`lng`)을 보내고 중심에 가까운 순으로 받는다.
    /// 상한에 걸릴 때 화면 가운데부터 채우는 이유는 계약 설명에 있다 — 앞에서부터
    /// 자르면 자료에 먼저 적힌 동네가 상한을 다 먹는다. 실패하면 조용히 빈 목록 —
    /// 주변 점은 장식이라 화면이 막히지 않는다.
    static func pois(
        south: Double, west: Double, north: Double, east: Double,
        centerLat: Double, centerLng: Double, limit: Int = 30
    ) async -> [Place] {
        // bbox 는 GeoJSON 순서 — minLng,minLat,maxLng,maxLat.
        let bbox = "\(west),\(south),\(east),\(north)"
        guard let list = try? await PoisAPI.listPois(
            bbox: bbox, lat: centerLat, lng: centerLng, sort: .distance, limit: limit
        ) else { return [] }
        return list.items.map(Place.init(poi:))
    }

    /// 핀을 눌렀을 때 띄울 정보 카드 — 서버가 네이버 장소에서 채운다
    /// (`GET /pois/{poiId}/card`, ADR 0011, 데모 한정). 처음 부르면 `pending` 으로 올 수
    /// 있다 — 서버가 뒤에서 채우므로 다시 열면 있다. 우리 편의시설이 아닌 곳(챗봇 결과)은
    /// id 가 없어 카드도 없다.
    static func card(for place: Place) async -> Card? {
        guard let poiId = place.poiId,
              let card = try? await PoisAPI.getPoiCard(poiId: poiId)
        else { return nil }
        return Card(
            found: card.found ?? false,
            name: card.name ?? place.name,
            category: card.category ?? place.category,
            address: card.address ?? place.address,
            hours: card.hours,
            phone: card.phone,
            reviewCount: card.reviewCount,
            blogReviews: card.blogReviews,
            score: card.score,
            images: card.images ?? [],
            naverUrl: card.naverUrl,
            why: card.pending == true ? "아직 채우는 중이에요 — 잠시 뒤 다시 열어 주세요" : card.why
        )
    }

    /// 네이버에서 온 정보 카드.
    struct Card {
        let found: Bool
        let name: String
        let category: String?
        let address: String?
        let hours: String?
        let phone: String?
        let reviewCount: Int?
        let blogReviews: Int?
        let score: Double?
        let images: [String]
        /// 「네이버에서 열기」가 갈 곳.
        let naverUrl: String?
        /// 못 찾았을 때 왜인지.
        let why: String?
    }

    // MARK: 주고받는 것

    struct Turn: Identifiable, Equatable {
        enum Role: String {
            case user
            case assistant
        }

        let id = UUID()
        let role: Role
        let text: String
    }

    struct Answer {
        let reply: String
        /// 모델이 실제로 부른 도구. **화면에 보여 준다** — 근거 없이 답한 것을
        /// 사용자가 알아볼 수 있어야 한다.
        let tools: [String]
        let places: [Place]
        let seconds: Double
    }

    /// 가이드가 찾아 준 장소. 좌표가 있으므로 **지도에 찍을 수 있다.**
    struct Place: Identifiable, Equatable {
        let id: String
        let name: String
        let category: String?
        let address: String?
        let distanceMeters: Int?
        let latitude: Double
        let longitude: Double

        /// 서버가 준 큰 갈래(음식·숙박·명소·교통). 옛 서버는 안 보내므로 없을 수 있다.
        let group: String?

        /// 코스의 촬영지를 가이드 장소 모양으로 바꿀 때 쓴다(성지 카드의
        /// 「여기로 길찾기」 — 갈아탈 목적지는 이 타입이다).
        init(
            id: String, name: String, category: String?,
            latitude: Double, longitude: Double
        ) {
            self.id = id
            self.name = name
            self.category = category
            self.latitude = latitude
            self.longitude = longitude
            address = nil
            distanceMeters = nil
            group = nil
        }

        init?(_ json: [String: Any]) {
            guard let latitude = Self.number(json["lat"]),
                  let longitude = Self.number(json["lng"]),
                  let name = json["name"] as? String
            else { return nil }
            self.name = name
            self.latitude = latitude
            self.longitude = longitude
            id = String(describing: json["id"] ?? name)
            category = json["cat"] as? String ?? json["kind"] as? String
            group = json["group"] as? String
            address = json["addr"] as? String
            distanceMeters = Self.number(json["dist_m"]).map { Int($0) }
        }

        /// 계약의 편의시설(`PoiSummary`) → 화면 장소. id 는 `poi-<서버 id>` — 챗봇이
        /// 준 것(id 가 이름)과 섞여도 갈리고, 카드 조회가 서버 id 를 되찾는다.
        init(poi: PoiSummary) {
            id = "poi-\(poi.id)"
            name = poi.name
            category = poi.category
            address = poi.address
            distanceMeters = poi.distanceMeters
            latitude = poi.latitude
            longitude = poi.longitude
            group = poi.categoryGroup.rawValue
        }

        /// 계약 편의시설의 서버 id. 챗봇이 준 곳은 nil.
        var poiId: Int64? {
            guard id.hasPrefix("poi-") else { return nil }
            return Int64(id.dropFirst(4))
        }

        /// 서버가 숫자를 문자열로 줄 때가 있다(원본 자료가 그렇다).
        private static func number(_ value: Any?) -> Double? {
            if let double = value as? Double {
                return double
            }
            if let int = value as? Int {
                return Double(int)
            }
            if let text = value as? String {
                return Double(text)
            }
            return nil
        }
    }
}
