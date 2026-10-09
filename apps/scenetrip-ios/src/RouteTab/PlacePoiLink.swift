import Foundation
import SceneApiClient

/// **촬영지와 같은 곳인 편의시설** — 어디서든 촬영지가 대표한다 (MZ2AZ-378, 계획 `place-poi-link.md` §5·§7).
///
/// 서버가 편의시설에 `placeId` 를 실어 준다(`PoiSummary.placeId`, 계약 1.7.0). 있으면
///
/// 1. 누를 때 — 촬영지 상세(`GET /places/{placeId}`)를 연다. 리뷰도 촬영지의 것이다.
/// 2. 지도 — 그 촬영지의 핀이 **지금 그려져 있으면** 편의시설 점을 그리지 않는다.
/// 3. 코스에 담기 — 개인 핀이 아니라 촬영지로 담는다(`CourseItemInput.placeId`).
///
/// 연결이 없는 편의시설은 **편의시설로** 담는다(`CourseItemInput.poiId`, MZ2AZ-380) — 무엇으로 담는지는
/// `entry` 한 곳이 정한다.
///
/// 판단은 전부 여기 있다. 네트워크도 화면도 모르는 순수 함수라 시험이 그대로 부른다
/// (`PlacePoiLinkTests`·`CoursePoiItemTests`).
enum PlacePoiLink {
    // MARK: 1. 누를 때

    /// 장소가 가리키는 **대상** — 상세를 어느 API 로 받고 리뷰가 어디에 붙는지를 정한다.
    enum Target: Equatable {
        /// 촬영지 — 상세는 `GET /places/{id}`, 리뷰는 촬영지 리뷰.
        case place(Int64)
        /// 편의시설 — 상세는 `GET /pois/{id}`, 리뷰는 편의시설 리뷰.
        case poi(Int64)
    }

    /// 촬영지면 촬영지, **연결된 편의시설도 촬영지**, 그 밖의 편의시설은 편의시설.
    /// 코스에서 옮긴 것(둘 다 아님)은 대상이 없다.
    static func target(placeId: Int64?, poiId: Int64?, linkedPlaceId: Int64?) -> Target? {
        if let placeId {
            return .place(placeId)
        }
        guard let poiId else { return nil }
        if let linkedPlaceId {
            return .place(linkedPlaceId)
        }
        return .poi(poiId)
    }

    // MARK: 2. 지도

    /// 지도에 **지금 핀으로 그려진** 촬영지의 id.
    ///
    /// 코스 지도가 촬영지를 그리는 길은 셋이다 — 보고 있는 일차의 번호 핀, 검색·장바구니 시트의
    /// 미리보기 핀, 챗봇이 찾아 준 촬영지(해태 핀). 직접 찍은 핀은 id 가 음수라 세지 않는다.
    static func drawnPlaceIds(
        stops: [PlaceSummary], previews: [PlaceSummary] = [], guidePlaceIds: [Int64] = []
    ) -> Set<Int64> {
        Set((stops + previews).map(\.id).filter { $0 > 0 } + guidePlaceIds)
    }

    /// 이 편의시설 점을 그리는가. **그 촬영지의 핀이 화면에 있을 때만 숨긴다.**
    ///
    /// 연결만 있다고 늘 숨기면, 촬영지 핀이 없는 화면(코스에 안 담은 곳)에서는 그 가게가 지도에서
    /// 통째로 사라진다 — 계획 §5: 「촬영지 핀을 끄고 편의시설 갈래만 볼 때는 점이 보이되 누르면 촬영지 상세」.
    ///
    /// **코스에 편의시설로 담긴 곳**(`takenPoiIds`)도 그리지 않는다 — 그 자리는 번호 핀의 것이다(MZ2AZ-380).
    static func drawsDot(
        linkedPlaceId: Int64?, drawnPlaceIds: Set<Int64>,
        poiId: Int64? = nil, takenPoiIds: Set<Int64> = []
    ) -> Bool {
        if let poiId, takenPoiIds.contains(poiId) {
            return false
        }
        guard let linkedPlaceId else { return true }
        return !drawnPlaceIds.contains(linkedPlaceId)
    }

    // MARK: 3. 코스에 담기

    /// 코스에 담을 모양. 갈래(`kind`)가 저장을 정한다 — 촬영지는 `placeId`, 편의시설은 `poiId`,
    /// 개인 핀은 `customPin`(`RouteBridge.item`).
    struct Entry: Equatable {
        let place: PlaceSummary
        let kind: RouteStop.Kind

        /// 코스에 넣을 줄.
        var stop: RouteStop {
            RouteStop(place: place, kind: kind)
        }
    }

    /// 무엇으로 담는가 (MZ2AZ-378 · 380, 계획 `course-poi-item.md` §1).
    ///
    /// | 담는 것 | 갈래 |
    /// | --- | --- |
    /// | 촬영지(챗봇이 찾아 준 `source: place`) | 촬영지 — 전에는 개인 핀으로 담겼다 |
    /// | 촬영지와 같은 곳인 편의시설 | **그 촬영지** — id 가 촬영지 id 라 촬영지 중복 검사에 걸린다 |
    /// | 그 밖의 편의시설 | **편의시설**(`poiId`) — 전에는 개인 핀이었다 |
    /// | 둘 다 아닌 것(코스에서 옮긴 것) | 개인 핀(`pin`) |
    ///
    /// 촬영지로 담는 줄의 이름·분류·주소·좌표는 받은 것을 일단 싣는다 — 서버에는 `placeId` 만 가므로 저장에는
    /// 영향이 없고, 화면은 촬영지 상세가 오면 그 값으로 바꾼다(`adopting`).
    static func entry(
        placeId: Int64? = nil, poiId: Int64? = nil, linkedPlaceId: Int64?,
        name: String, category: String?, address: String?,
        latitude: Double, longitude: Double, pin: () -> PlaceSummary
    ) -> Entry {
        if let asPlace = placeId ?? linkedPlaceId {
            return Entry(
                place: PlaceSummary(
                    id: asPlace, name: name, type: category, address: address,
                    latitude: latitude, longitude: longitude
                ),
                kind: .place
            )
        }
        if let poiId {
            let stop = RouteStop.poi(
                poiId, name: name, category: category, address: address,
                latitude: latitude, longitude: longitude
            )
            return Entry(place: stop.place, kind: stop.kind)
        }
        return Entry(place: pin(), kind: .pin)
    }

    /// 촬영지 상세가 왔다 — 편의시설 이름으로 담아 둔 줄을 **촬영지의 이름·유형·주소·좌표**로 바꾼다.
    /// 다른 곳의 상세면 그대로 둔다.
    static func adopting(_ detail: PlaceDetail, over place: PlaceSummary) -> PlaceSummary {
        guard place.id == detail.id else { return place }
        var next = place
        next.name = detail.name
        next.type = detail.type
        next.address = detail.address ?? place.address
        next.latitude = detail.latitude
        next.longitude = detail.longitude
        next.imageUrl = detail.imageUrl ?? place.imageUrl
        return next
    }

    /// 저장하고 다시 받은 줄들에서 **이 줄**을 찾는다 — 방금 담은 곳으로 곧바로 길찾기를 켤 때(`startTrip`).
    ///
    /// 서버가 준 줄은 새 줄(`UUID` 가 다르다)이라 내용으로 찾는다. **촬영지 줄은 촬영지 id 로** —
    /// 편의시설 이름·좌표로 담긴 줄(`entry`)은 서버가 촬영지의 이름·좌표로 돌려주므로 이름 + 좌표로는
    /// 못 찾는다(상세가 아직 안 왔거나 못 받았을 때). **편의시설 줄은 편의시설 id 로**(MZ2AZ-380). 그 밖(개인 핀 —
    /// id 가 저장마다 바뀐다)과 편의시설 id 로 못 찾은 줄(서버가 촬영지로 바꿔 저장했다)은 전처럼 이름 + 좌표.
    static func row(of stop: RouteStop, in saved: [RouteStop]) -> RouteStop? {
        if let placeId = stop.placeId {
            return saved.first { $0.placeId == placeId }
        }
        if let poiId = stop.poiId, let found = saved.first(where: { $0.poiId == poiId }) {
            return found
        }
        let key = RouteDedupe.key(stop.place)
        return saved.first { RouteDedupe.key($0.place) == key }
    }

    /// 코스에 담긴 곳(`stop`)이 이 편의시설과 **같은 곳**인가 — 「경로에 있음」 표시와 빼기가 쓴다.
    ///
    /// 연결된 편의시설은 촬영지 id 로 본다(검색·장바구니로 먼저 담은 촬영지도 같은 곳이다). **촬영지는 촬영지 id 로,
    /// 편의시설은 편의시설 id 로**(MZ2AZ-380) — 촬영지로 담은 줄은 상세가 오면 이름·좌표가 바뀌어(`adopting`) 이름 +
    /// 좌표로는 못 찾는다. 그 밖(전에 개인 핀으로 담긴 같은 가게)은 전처럼 이름 + 좌표(`RouteDedupe.key`).
    static func holds(
        stop: RouteStop, placeId: Int64? = nil, poiId: Int64? = nil, linkedPlaceId: Int64?, pinKey: String
    ) -> Bool {
        if let asPlace = placeId ?? linkedPlaceId, stop.placeId == asPlace {
            return true
        }
        if let poiId, stop.poiId == poiId {
            return true
        }
        return RouteDedupe.key(stop.place) == pinKey
    }
}

// MARK: - 가이드 장소에 얹은 것

extension RouteGuide.Place {
    /// 상세·리뷰가 가리킬 대상. 연결된 편의시설이면 촬영지다.
    var target: PlacePoiLink.Target? {
        PlacePoiLink.target(placeId: placeId, poiId: poiId, linkedPlaceId: linkedPlaceId)
    }

    /// 코스에 담을 모양 — 촬영지는 촬영지로, 연결된 편의시설도 촬영지로, 그 밖의 편의시설은 편의시설로
    /// (`PlacePoiLink.entry`).
    var courseEntry: PlacePoiLink.Entry {
        PlacePoiLink.entry(
            placeId: placeId, poiId: poiId, linkedPlaceId: linkedPlaceId,
            name: name, category: category, address: address,
            latitude: latitude, longitude: longitude, pin: { asPlaceSummary }
        )
    }

    /// 코스에 담긴 이 줄(`stop`)과 같은 곳인가.
    func isSameSpot(as stop: RouteStop) -> Bool {
        PlacePoiLink.holds(
            stop: stop, placeId: placeId, poiId: poiId, linkedPlaceId: linkedPlaceId,
            pinKey: RouteDedupe.key(asPlaceSummary)
        )
    }
}

extension [RouteGuide.Place] {
    /// 촬영지 핀이 그려진 곳의 편의시설 점과, 코스에 편의시설로 담긴 곳의 점을 뺀다(`PlacePoiLink.drawsDot`).
    func withoutDotsUnderPins(
        _ drawnPlaceIds: Set<Int64>, takenPoiIds: Set<Int64> = []
    ) -> [RouteGuide.Place] {
        filter {
            PlacePoiLink.drawsDot(
                linkedPlaceId: $0.linkedPlaceId, drawnPlaceIds: drawnPlaceIds,
                poiId: $0.poiId, takenPoiIds: takenPoiIds
            )
        }
    }
}
