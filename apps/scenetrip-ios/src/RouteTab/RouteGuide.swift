import CoreLocation
import Foundation

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
/// ## 지금은 프로토타입 서버를 부른다
///
/// `poi_nearby` 는 **47만 건에서 고르는 질의**라 앱 안에서 할 수 없다. 우리 서버에
/// 그 자리(`GET /pois`)가 아직 없어서(계약에도 없다 — MZ2AZ-278·279) MVP 데모
/// 동안만 프로토타입 서버를 부른다. `KakaoTransit` 이 백엔드 대신 카카오를 직접
/// 부르는 것과 같은 임시다.
///
/// **그쪽이 꺼져 있으면 조용히 실패하지 않는다** — 왜 안 되는지 화면이 말한다.
enum RouteGuide {
    /// 프로토타입 서버(`SceneTrip_navi`). `just run` 으로 띄운다.
    ///
    /// 시뮬레이터는 맥의 `localhost` 를 그대로 본다. 실기기에서는 맥의 LAN 주소로
    /// 바꿔야 하는데, 그때쯤이면 우리 서버로 옮겨 갔을 것이다.
    static let baseUrl = "http://127.0.0.1:8899"

    enum Failure: LocalizedError {
        case offline
        case server(String)

        var errorDescription: String? {
            switch self {
            case .offline:
                "가이드 서버에 연결하지 못했습니다"
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
        history: [Turn],
        here: CLLocationCoordinate2D,
        sessionId: String,
        context: Context? = nil
    ) async throws -> Answer {
        guard let url = URL(string: baseUrl + "/api/chat") else { throw Failure.offline }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // 8B 가 도구를 부르고 답을 짓는 데 실측 9~57초가 걸린다. 넉넉히 잡는다 —
        // 짧게 잡으면 잘 되던 것이 시간 초과로 보인다.
        request.timeoutInterval = 120
        var body: [String: Any] = [
            "here": [here.latitude, here.longitude],
            "messages": history.map { ["role": $0.role.rawValue, "content": $0.text] },
            "sid": sessionId,
        ]
        if let context {
            body["context"] = context.json
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let data: Data
        do {
            let (body, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                throw Failure.offline
            }
            data = body
        } catch let failure as Failure {
            throw failure
        } catch {
            throw Failure.offline
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure.offline
        }
        // 서버는 오류도 200 으로 준다 — LLM 이 꺼져 있는 것과 서버가 죽은 것은
        // 사용자에게 다른 이야기이기 때문이다.
        if let message = json["error"] as? String {
            throw Failure.server(message)
        }

        return Answer(
            reply: (json["reply"] as? String) ?? "",
            tools: ((json["used"] as? [[String: Any]]) ?? [])
                .compactMap { $0["tool"] as? String },
            places: ((json["places"] as? [[String: Any]]) ?? []).compactMap(Place.init),
            seconds: (json["took_s"] as? Double) ?? 0
        )
    }

    /// 핀을 눌렀을 때 띄울 정보 카드 (`POST /api/place-card`).
    ///
    /// 네이버에서 찾아 사진·영업시간·리뷰·별점을 묶어 온다. **못 찾아도 실패가
    /// 아니다** — 「네이버에 없다」도 사용자에게는 답이다. 우리 POI 자료(TMAP)에
    /// 있는 가게가 네이버에 없을 수 있고, 그것이 나쁜 가게라는 뜻은 아니다.
    static func card(for place: Place) async -> Card? {
        guard let url = URL(string: baseUrl + "/api/place-card") else { return nil }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // 네이버 검색 → 상세를 이어 부르므로 한 호출보다 오래 걸린다.
        request.timeoutInterval = 30
        request.httpBody = try? JSONSerialization.data(withJSONObject: [
            "name": place.name,
            "addr": place.address ?? "",
            "lat": place.latitude,
            "lng": place.longitude,
        ])

        guard let (data, response) = try? await URLSession.shared.data(for: request),
              let http = response as? HTTPURLResponse, http.statusCode == 200,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        else { return nil }

        return Card(
            found: (json["found"] as? Bool) ?? false,
            name: (json["name"] as? String) ?? place.name,
            category: json["category"] as? String,
            address: json["addr"] as? String,
            hours: json["hours"] as? String,
            phone: json["phone"] as? String,
            reviewCount: json["review_count"] as? Int,
            blogReviews: json["blog_reviews"] as? Int,
            score: json["score"] as? Double,
            images: (json["images"] as? [String]) ?? [],
            naverUrl: json["url"] as? String,
            why: json["why"] as? String
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
            address = json["addr"] as? String
            distanceMeters = Self.number(json["dist_m"]).map { Int($0) }
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
