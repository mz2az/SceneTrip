import CoreLocation
import Foundation
import SceneApiClient

/// 여행 가이드 챗봇 — **백엔드 창구를 부른다** (`POST /guide/chat`, MZ2AZ-321).
///
/// 프로토타입(`SceneTrip_navi`) v6 가 실측으로 얻은 규칙 넷은 그대로다. 다만 지키는 자리가
/// 서버로 옮겨 갔다 — 앱은 모델 이름도 키도 주소도 모른다(ADR 0012 · 0013).
///
/// ## 1. RAG 가 아니라 **도구 호출**이다
///
/// 「반경 300 m 안 음식점을 리뷰순으로」는 의미 검색이 아니라 **조건 질의**다. 모델은 말을
/// 알아듣고 `poi_nearby(300, 음식)` 를 부를 뿐이다. 그 도구는 에이전트(`agents/trip-guide`)에 있다.
///
/// ## 2. 모델에게 **좌표를 주지 않는다**
///
/// 화면 상태(`Context`)에 좌표를 싣지만 그것은 서버가 번호를 좌표로 바꾸는 데 쓰고, 모델에게는
/// 번호와 이름만 간다. 좌표를 보면 모델이 스스로 거리를 재려 들고 틀린다(프로토타입 실측).
///
/// ## 3. **지어내지 못하게 막는다**
///
/// 도구가 준 목록에 없는 곳은 존재하지 않는 것으로 다룬다 — 에이전트 쪽 규칙이고, 앱은 그
/// 결과(`places`)를 그대로 그린다.
///
/// ## 4. **부른 도구를 함께 보인다**
///
/// 답만 보면 그럴듯한 헛소리를 걸러 낼 수 없다. `toolsUsed` 가 화면의 「근거」줄이다.
///
/// ## 답은 셋으로 갈린다
///
/// `reply`·`places` 는 시트가 그리고, `effects`·`ui` 는 편집 화면이 적용한다
/// (`RouteEditorView.applyGuideAnswer`). 모르는 명령은 무시한다 — 에이전트가 명령을 늘려도
/// 옛 앱이 깨지지 않게(계약 `GuideEffect`·`GuideUiDirective`).
enum RouteGuide {
    /// 앱이 기다리는 시간의 벽. 서버 벽(40초)보다 길다 — 앱이 먼저 끊으면 서버가 뒤늦게
    /// `cart.add` 를 저장하는데 앱은 실패로 보인다(MZ2AZ-321 §6). 자동 재시도는 없다.
    static let timeoutSeconds = 50.0

    /// 화면 상태. 모델이 「1번 주변 맛집」·「다 돌았어?」·「2일차에서 빼 줘」를 알아듣게 하는 재료다.
    /// 질문마다 다시 만든다 — 계약 `GuideContext`.
    struct Context {
        /// 코스에 담긴 지점. 지도의 번호 핀과 **같은 번호**여야 한다.
        let stops: [Spot]
        /// 지금 고른 곳. 「여기 주변」이 가리키는 자리.
        let picked: Spot?
        /// 여행 상태. 여행 중이 아니면 nil.
        var trip: Trip?
        /// 편집 중인 일정 전체. 편집 중엔 앱이 정본이다 — 없으면 「2일차에서 빼 줘」가 거절된다.
        var plan: GuidePlan?

        struct Spot {
            let number: Int
            let name: String
            let kind: String?
            let latitude: Double
            let longitude: Double
            var visited = false
        }

        struct Trip {
            enum Phase {
                case plan, guiding, arrived
            }

            let phase: Phase
            let course: String
            let day: Int
            let days: Int
            let targetNumber: Int?
            /// 내 자리에서 가는 곳까지 **직선** m. 도로 거리가 아니고 모델에게도 그렇게 말한다.
            let targetMeters: Int?
            let walkedKilometers: Double
        }

        var contract: GuideContext {
            GuideContext(
                stops: stops.map(Self.stop(from:)),
                picked: picked.map(Self.stop(from:)),
                trip: trip.map { trip in
                    let phase: GuideTrip.Phase = switch trip.phase {
                    case .plan: .plan
                    case .guiding: .guiding
                    case .arrived: .arrived
                    }
                    return GuideTrip(
                        phase: phase,
                        course: trip.course,
                        day: trip.day,
                        days: trip.days,
                        targetNumber: trip.targetNumber,
                        targetMeters: trip.targetMeters,
                        walkedKilometers: trip.walkedKilometers
                    )
                },
                plan: plan
            )
        }

        private static func stop(from spot: Spot) -> GuideStop {
            GuideStop(
                number: spot.number, name: spot.name, category: spot.kind,
                latitude: spot.latitude, longitude: spot.longitude, visited: spot.visited
            )
        }
    }

    /// 한 번 주고받는다.
    ///
    /// - Parameters:
    ///   - history: 지금까지의 대화. 마지막이 이번 질문이다. 서버는 이력을 저장하지 않는다.
    ///   - here: 지금 위치. **없으면 부를 수 없다** — 「주변」이 어디인지 모른다.
    ///   - sessionId: 대화를 잇는 열쇠. 앞 턴에서 보여 준 장소를 서버가 기억한다.
    static func ask(
        history: [Turn],
        here: CLLocationCoordinate2D,
        sessionId: UUID,
        context: Context? = nil
    ) async throws -> Answer {
        let request = GuideChatRequest(
            sessionId: sessionId,
            latitude: here.latitude,
            longitude: here.longitude,
            // 계약 상한 40 — 넘치면 오래된 것부터 잊는다. 마지막이 이번 질문이라 뒤를 남긴다.
            messages: history.suffix(40).map {
                GuideMessage(role: $0.role == .user ? .user : .assistant, content: $0.text)
            },
            context: context?.contract
        )
        let deviceId = InstallIdentity.current
        let reply = try await RouteGuideTimeout.run(seconds: timeoutSeconds) {
            try await GuideAPI.chatWithGuide(xDeviceId: deviceId, guideChatRequest: request)
        }
        return Answer(
            reply: reply.reply,
            tools: reply.toolsUsed.map(\.tool),
            places: reply.places.map(Place.init(guide:)),
            seconds: reply.tookSeconds,
            effects: reply.effects,
            ui: reply.ui,
            route: reply.route
        )
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

    /// 핀을 눌렀을 때 띄울 정보 카드. **출처가 상세 API 를 정한다**(계약 `GuidePlaceSource`) —
    /// 편의시설은 `GET /pois/{id}/card`(네이버 장소, ADR 0011), 촬영지는 `GET /places/{id}`.
    /// 촬영지와 편의시설은 다른 표라 숫자 id 만으로는 못 가른다.
    ///
    /// 편의시설 카드는 처음 부르면 `pending` 으로 올 수 있다 — 서버가 뒤에서 채우므로 다시 열면 있다.
    static func card(for place: Place) async -> Card? {
        if let placeId = place.placeId {
            guard let detail = try? await PlacesAPI.getPlace(placeId: placeId) else { return nil }
            return Card(
                found: true,
                name: detail.name,
                category: detail.type ?? place.category,
                address: detail.address ?? place.address,
                hours: nil,
                phone: nil,
                reviewCount: nil,
                blogReviews: nil,
                score: nil,
                images: detail.imageUrls ?? [detail.imageUrl].compactMap { $0 },
                naverUrl: detail.naverPlaceUrl,
                why: nil
            )
        }
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

    /// 정보 카드 — 편의시설은 네이버에서, 촬영지는 우리 상세에서 온다.
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
        /// 백엔드가 이미 수행한(`cart.*`) 또는 앱이 수행할(`plan.*`) 명령. 편집 화면이 적용한다.
        let effects: [GuideEffect]
        /// 앱이 수행할 화면 명령. `Directive` 로 읽는다.
        let ui: [GuideUiDirective]
        /// `route` 도구가 그린 경로. 지금 화면은 안 그린다 — 코스 밖 목적지의 선은 따로 정한다.
        let route: NextLeg?
    }

    /// 에이전트의 화면 명령. **아는 것만 갈래로, 모르는 것은 `nil`** — 응답 전체를 버리지 않는다.
    enum Directive: Equatable {
        case mapFocus([Int64])
        case routeDraw([Int64])
        case placeCard(Int64)
        case courseOpen(Int)
        case courseFocus(day: Int, changed: [String])
        case sheetCollapse

        init?(contract directive: GuideUiDirective) {
            switch directive.op {
            case "map.focus":
                guard let ids = directive.placeIds, !ids.isEmpty else { return nil }
                self = .mapFocus(ids)
            case "route.draw":
                guard let ids = directive.placeIds, !ids.isEmpty else { return nil }
                self = .routeDraw(ids)
            case "place.card":
                guard let id = directive.placeId else { return nil }
                self = .placeCard(id)
            case "course.open":
                guard let day = directive.day else { return nil }
                self = .courseOpen(day)
            case "course.focus":
                guard let day = directive.day else { return nil }
                self = .courseFocus(day: day, changed: directive.changed ?? [])
            case "sheet.collapse":
                self = .sheetCollapse
            default:
                return nil
            }
        }
    }

    /// 가이드가 찾아 준 장소. 좌표가 있으므로 **지도에 찍을 수 있다.**
    ///
    /// `id` 앞에 출처가 붙는다(`place-N`·`poi-N`) — 촬영지와 편의시설은 다른 표라 숫자만으로는
    /// 못 가르고, 상세를 어느 API 로 부를지도 이것이 정한다.
    struct Place: Identifiable, Equatable {
        enum Source: Equatable {
            /// 촬영지 — `PlaceSummary.id`, 상세는 `GET /places/{id}`.
            case place
            /// 편의시설 — `PoiSummary.id`, 상세는 `GET /pois/{id}`.
            case poi
        }

        let id: String
        let name: String
        let category: String?
        let address: String?
        let distanceMeters: Int?
        let latitude: Double
        let longitude: Double

        /// 서버가 준 큰 갈래(food·stay·sight·transit). 코스 촬영지를 옮긴 것은 없다.
        let group: String?

        /// 어느 표에서 왔나. 코스 촬영지를 옮긴 것(길찾기 목적지)은 없다.
        let source: Source?

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
            source = nil
        }

        /// 계약의 편의시설(`PoiSummary`) → 화면 장소. 지도 범위 조회(`pois`)가 준 것.
        init(poi: PoiSummary) {
            id = "poi-\(poi.id)"
            name = poi.name
            category = poi.category
            address = poi.address
            distanceMeters = poi.distanceMeters
            latitude = poi.latitude
            longitude = poi.longitude
            group = poi.categoryGroup.rawValue
            source = .poi
        }

        /// 가이드가 찾아 준 장소(`GuidePlace`) → 화면 장소. 출처를 id 에 새긴다.
        init(guide place: GuidePlace) {
            source = place.source == .place ? .place : .poi
            id = "\(place.source == .place ? "place" : "poi")-\(place.id)"
            name = place.name
            category = place.category
            address = place.address
            distanceMeters = place.distanceMeters
            latitude = place.latitude
            longitude = place.longitude
            group = place.categoryGroup.rawValue
        }

        /// 편의시설의 서버 id. 촬영지·옮긴 것은 nil.
        var poiId: Int64? {
            guard id.hasPrefix("poi-") else { return nil }
            return Int64(id.dropFirst(4))
        }

        /// 촬영지의 서버 id. 편의시설·옮긴 것은 nil.
        var placeId: Int64? {
            guard id.hasPrefix("place-") else { return nil }
            return Int64(id.dropFirst(6))
        }

        /// 출처와 무관한 서버 id — `ui` 의 `placeIds` 가 이것을 가리킨다(이번 턴 `places[].id`).
        var serverId: Int64? {
            placeId ?? poiId
        }
    }
}
