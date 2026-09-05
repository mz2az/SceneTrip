import NMapsMap
import SwiftUI

/// 편집 지도의 **주변 편의시설** — 화면이 멈출 때마다 그 범위의 가게·숙소·역·명소를
/// 갈래 색 점으로 깔아 준다(2026-08-28 사용자 요청, 네이버 지도의 그 동작).
///
/// `RouteMapView.swift` 에서 갈라 둔 파일이다(타입 길이 한도 — `RouteMapLocate` 와
/// 같은 이유). 챗봇 결과(`guidePlaces`)와 그리는 모양은 같지만 **카메라를 절대
/// 움직이지 않는다** — 지도를 미는 사람의 손을 이기려 들면 안 된다.
extension RouteMapView.Coordinator {
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
                : PinoPin.guideDot(for: place)
            marker.anchor = isPicked ? CGPoint(x: 0.5, y: 1) : CGPoint(x: 0.5, y: 0.5)
            // 챗봇 결과보다 늦게 이름이 나온다 — 배경은 배경답게 조용해야 한다.
            PinoPin.caption(marker, name: place.name, picked: isPicked, ambient: true)
            marker.zIndex = isPicked ? 30 : 3
            marker.touchHandler = { [weak self] _ in
                self?.onTapGuide(place)
                return true
            }
            marker.mapView = mapView
            return marker
        }
    }

    /// 카메라가 멈췄다. 화면 범위를 바깥(에디터)에 알린다 — 부를지 말지,
    /// 얼마나 자주 부를지는 **바깥이 정한다.** 지도는 위치만 안다.
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
