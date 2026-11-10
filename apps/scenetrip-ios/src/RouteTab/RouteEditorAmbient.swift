import SceneApiClient
import SwiftUI

/// 편집 화면의 **주변 편의시설** — 칩을 켤 때 받은 것을 거르고 세는 쪽 논리(받기는 `RouteEditorMapPoi.swift`). `RouteEditorView.swift` 에서 갈라 뒀다(타입 길이 한도).
extension RouteEditorView {
    /// 갈래 필터를 통과한 주변 편의시설. 챗봇이 이미 보여 준 곳과 코스에 담긴
    /// 곳은 뺀다 — 같은 가게가 두 겹으로 찍히면 어느 쪽을 누른 것인지 모른다.
    ///
    /// **촬영지와 같은 곳인 편의시설은 그 촬영지 핀이 지도에 있으면 뺀다**(MZ2AZ-378). 핀이 없으면
    /// (코스에 안 담았거나 다른 일차에 담은 곳) 점은 남고, 누르면 촬영지 상세가 열린다.
    ///
    /// **코스에 편의시설로 담긴 곳**은 편의시설 id 로도 뺀다(MZ2AZ-380) — 이름+좌표가 같아 이미 빠지지만,
    /// 그것은 편의시설 자료의 글자·좌표가 양쪽에서 똑같이 올 때의 이야기다.
    var visibleAmbientPois: [RouteGuide.Place] {
        let shown = Set(guide.places.map { RouteDedupe.key($0.asPlaceSummary) })
        let taken = takenSpotKeys
        return ambientPois.filter { place in
            let key = RouteDedupe.key(place.asPlaceSummary)
            return mapChips.isOn(place.poiGroup)
                && !shown.contains(key) && !taken.contains(key)
        }
        .withoutDotsUnderPins(drawnPlaceIds, takenPoiIds: takenPoiIds)
    }

    /// 지도에 지금 핀으로 그려진 촬영지 — 보고 있는 일차의 번호 핀, 검색·장바구니의 미리보기 핀,
    /// 챗봇이 찾아 준 촬영지(`RouteMapView` 가 그리는 것 그대로).
    var drawnPlaceIds: Set<Int64> {
        PlacePoiLink.drawnPlaceIds(
            stops: stops.map(\.place), previews: previewPlaces,
            guidePlaceIds: visibleGuidePlaces.compactMap(\.placeId)
        )
    }

    /// 가이드가 찾아 준 곳·주변 편의시설을 코스에 담는다 — **촬영지는 촬영지로, 촬영지와 같은 곳인 편의시설도
    /// 촬영지로, 그 밖의 편의시설은 편의시설로**(`RouteGuide.Place.courseEntry`, MZ2AZ-378 · 380). 지도 카드의
    /// 「경로에 추가」 와 가이드 창의 ⊕ 가 같은 길로 온다.
    func addGuidePlace(_ place: RouteGuide.Place) {
        let entry = place.courseEntry
        addStops([entry.stop], asNext: true)
        guard entry.kind == .place else { return }
        // 편의시설 이름(「몽테드」)이나 챗봇이 적은 이름으로 담긴 줄을 촬영지의 것(「수원 카페 몽테드」)으로
        // 바꾼다. 못 받아도 저장은 `placeId` 로 가므로 틀리지 않고, 다시 열면 서버가 촬영지 이름을 준다.
        Task {
            guard let detail = try? await PlacesAPI.getPlace(placeId: entry.place.id) else { return }
            for day in course.days.indices {
                for index in course.days[day].stops.indices where course.days[day].stops[index].kind == .place {
                    course.days[day].stops[index].place = PlacePoiLink.adopting(
                        detail, over: course.days[day].stops[index].place
                    )
                }
            }
        }
    }

    /// 「AI 장소 N」 칩. 챗봇이 찾아 준 곳이 있을 때만 나온다. 코스에 담은 곳은 번호 핀이 됐으니 안 센다.
    var aiChip: [RoutePoiChips.Extra] {
        let count = guidePlacesForMap.count
        guard count > 0 else { return [] }
        return [RoutePoiChips.Extra(
            id: "ai", label: String(format: tr("AI 장소 %d"), count), tone: .accentColor, isOn: aiPlacesOn,
            image: "haetae-face"
        ) {
            aiPlacesOn.toggle()
            // 감춘 핀을 계속 골라 두면 카드만 남는다.
            if !aiPlacesOn, let picked = guide.picked, guide.places.contains(where: { $0.id == picked.id }) {
                guide.picked = nil
            }
        }]
    }

    /// 카메라가 멈췄다 — 범위를 기억해 둔다. **받지는 않는다**(MZ2AZ-547 M2·M3): 칩을 켜거나 「다시 찾기」 를 누를 때만
    /// 이 범위로 받고(`fetchMapPois`), 지도를 옮기면 점은 그대로 두고 「↻ 이 지역에서 다시 찾기」 를 띄운다.
    /// 예전의 줌 13 제한도 없다 — 서버가 칸마다 골고루 주므로 넓게 봐도 먼지가 되지 않는다.
    func viewportChanged(
        south: Double, west: Double, north: Double, east: Double,
        centerLat: Double, centerLng: Double, zoom: Double, source: LocationCoordinateSource
    ) {
        guideMapCenter = TripSpot(latitude: centerLat, longitude: centerLng)
        guideMapSource = source
        mapViewport = MapViewport(
            south: south, west: west, north: north, east: east,
            centerLat: centerLat, centerLng: centerLng, zoom: zoom, source: source
        )
        if mapResults.isOpen {
            mapResults.state?.cameraIdle(at: Date())
        } else if mapChips.anyOn {
            poiFetch.cameraIdle()
        }
    }
}
