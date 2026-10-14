import NMapsMap
import SwiftUI

/// 코스 편집·여행 지도에서 편의시설을 각각 실제 위치에 그린다(MZ2AZ-403).
@MainActor
final class PoiMarkerRenderer {
    private var markers: [NMFMarker] = []
    private var places: [RouteGuide.Place] = []
    private var pickedId: String?
    private var language: String?
    private var onSelect: (RouteGuide.Place) -> Void = { _ in }

    func render(
        _ places: [RouteGuide.Place], picked: RouteGuide.Place?,
        on mapView: NMFMapView, onSelect: @escaping (RouteGuide.Place) -> Void
    ) {
        self.onSelect = onSelect
        let next = PoiMarkerRules.validPlaces(places)
        let currentLanguage = AppLanguage.current.rawValue
        guard next != self.places || pickedId != picked?.id || language != currentLanguage else { return }
        self.places = next
        pickedId = picked?.id
        language = currentLanguage
        markers.forEach { $0.mapView = nil }
        markers = next.map { place in
            let marker = NMFMarker(position: NMGLatLng(lat: place.latitude, lng: place.longitude))
            let isPicked = place.id == pickedId
            marker.iconImage = isPicked ? PinoPin.marker(.picked) : PinoPin.guideDot(for: place)
            marker.anchor = isPicked ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 0.5, y: 0.5)
            PinoPin.caption(marker, name: place.label.caption, picked: isPicked, ambient: true)
            marker.zIndex = isPicked ? 30 : 3
            marker.touchHandler = { [weak self] _ in
                self?.onSelect(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }
    }
}
