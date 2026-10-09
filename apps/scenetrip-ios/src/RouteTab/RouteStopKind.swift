import Foundation
import SceneApiClient

/// 코스 줄의 **세 갈래** — 촬영지 · 편의시설 · 개인 핀 (MZ2AZ-380, 계획 `course-poi-item.md` §6-1).
///
/// 서버의 코스 항목이 셋 중 정확히 하나를 가리킨다(`CourseItemInput.placeId`·`poiId`·`customPin`, 계약 1.8.0).
/// 화면 모형도 그 셋을 **값으로** 든다. 전에는 「핀인가」(`isPinned`) 하나와 `place.id` 의 부호로 갈랐는데,
/// 그 방식으로는 편의시설을 적을 자리가 없어 편의시설이 개인 핀으로 저장됐다.
extension RouteStop {
    enum Kind: Hashable {
        /// 등록된 촬영지 — id 는 `place.id`.
        case place
        /// 편의시설(`PoiSummary.id`). 촬영지 id 와는 **다른 표의 번호**라 `place.id` 에 싣지 않는다.
        case poi(Int64)
        /// 지도에 직접 찍은 점. 우리 자료에 없는 곳이다.
        case pin
    }

    /// 촬영지가 아닌 줄의 `place.id` 가 새로 만들어질 때 받는 값. **촬영지 id 가 아니라는 표시일 뿐**이다 —
    /// 줄을 가리는 것은 `id`(UUID)와 `serverItemId`, 무엇인지는 `kind` 가 말한다.
    static let noPlaceId: Int64 = 0

    /// 지도를 눌러 직접 찍은 핀인가. 숙소처럼 우리 데이터에 없는 곳이다.
    var isPinned: Bool {
        kind == .pin
    }

    /// 편의시설이면 그 id. 상세는 `GET /pois/{id}`.
    var poiId: Int64? {
        if case let .poi(id) = kind {
            return id
        }
        return nil
    }

    /// 촬영지이면 그 id. 편의시설·개인 핀·id 없는 초안 줄은 없다. **촬영지 id 를 읽는 자리는 이것을 본다** —
    /// `place.id > 0` 으로 가르지 않는다.
    var placeId: Int64? {
        kind == .place && place.id > 0 ? place.id : nil
    }

    /// 편의시설 줄. 이름·분류·주소·좌표는 편의시설의 것(한국어 원본)이다.
    static func poi(
        _ poiId: Int64, name: String, category: String?, address: String?,
        latitude: Double, longitude: Double
    ) -> RouteStop {
        RouteStop(
            place: PlaceSummary(
                id: noPlaceId, name: name, type: category, address: address,
                latitude: latitude, longitude: longitude
            ),
            kind: .poi(poiId)
        )
    }
}

extension RouteGuide.Place {
    /// 코스에 담긴 **편의시설 줄**을 편의시설 카드(`RoutePlaceCard`)가 받는 모양으로. 편의시설 줄이 아니면 nil.
    ///
    /// `id` 가 `poi-N` 이라 카드가 `GET /pois/{N}` 을 받고 리뷰도 편의시설의 것이 된다(`target`). 상세를 못
    /// 받으면 카드는 줄이 가진 이름·분류·주소로 선다.
    init?(poiStop stop: RouteStop) {
        guard let poiId = stop.poiId else { return nil }
        id = "poi-\(poiId)"
        name = stop.place.name
        category = stop.place.type
        address = stop.place.address
        distanceMeters = nil
        latitude = stop.place.latitude
        longitude = stop.place.longitude
        group = nil
        source = .poi
    }
}
