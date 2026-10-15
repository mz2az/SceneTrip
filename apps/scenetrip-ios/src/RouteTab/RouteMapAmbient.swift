import NMapsMap
import SwiftUI

/// 편의시설을 각각 실제 좌표에 표시한다. 자동 조회·필터 변경은 카메라를 옮기지 않는다.
extension RouteMapView.Coordinator {
    /// 같은 목록·언어·선택은 다시 그리지 않는다.
    func renderAmbient(
        _ places: [RouteGuide.Place],
        picked: RouteGuide.Place?,
        on mapView: NMFMapView
    ) {
        ambientRenderer.render(places, picked: picked, on: mapView) { [weak self] in self?.onTapGuide($0) }
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
                center.lat, center.lng, mapView.zoomLevel, region.source
            )
        }
    }
}
