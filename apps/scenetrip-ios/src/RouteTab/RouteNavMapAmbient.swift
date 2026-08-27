import NMapsMap
import SwiftUI

/// 길찾기 지도의 **주변 편의시설** — 편집 지도(`RouteMapAmbient`)와 같은 배경 점.
/// 파일을 가른 이유도 같다(타입 길이 한도). 카메라를 절대 움직이지 않는다.
extension RouteNavMapView.Coordinator {
    /// 주변 점을 갈아 끼운다. 목록이 같으면 아무것도 안 한다.
    func renderAmbient(
        _ places: [RouteGuide.Place],
        picked: RouteGuide.Place?,
        on mapView: NMFMapView
    ) {
        let key = places.map(\.id).joined(separator: ",") + "|\(picked?.id ?? "-")"
        guard key != lastAmbientKey else { return }
        lastAmbientKey = key

        ambientMarkers.forEach { $0.mapView = nil }
        ambientMarkers = places.map { place in
            let marker = NMFMarker(
                position: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            let isPicked = place.id == picked?.id
            marker.iconImage = isPicked
                ? PinoPin.marker(.picked)
                : PinoPin.guideDot(place.poiGroup)
            marker.anchor = isPicked ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 0.5, y: 0.5)
            marker.captionText = place.name
            marker.captionMinZoom = 16
            marker.zIndex = isPicked ? 30 : 3
            marker.touchHandler = { [weak self] _ in
                self?.onTapPlace(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }
    }

    /// 카메라가 멈췄다. 화면 범위를 바깥에 알린다 — 부를지 말지는 바깥이 정한다.
    nonisolated func mapViewCameraIdle(_ mapView: NMFMapView) {
        Task { @MainActor in
            guard let onViewport else { return }
            let bounds = mapView.contentBounds
            let center = mapView.cameraPosition.target
            onViewport(
                bounds.southWestLat, bounds.southWestLng,
                bounds.northEastLat, bounds.northEastLng,
                center.lat, center.lng, mapView.zoomLevel
            )
        }
    }
}
