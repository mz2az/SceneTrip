import NMapsMap
import SwiftUI

/// 길찾기 지도의 **주변 편의시설** — 편집 지도(`RouteMapAmbient`)와 같은 묶음·점.
/// 파일을 가른 이유도 같다(타입 길이 한도). 자동 갱신은 카메라를 옮기지 않고,
/// 사용자가 묶음을 누를 때만 확대하거나 구성원 목록을 연다.
extension RouteNavMapView.Coordinator {
    /// 편집 지도와 같은 구성원·확대·목록 선택 규칙으로 묶는다.
    func renderAmbient(
        _ places: [RouteGuide.Place],
        picked: RouteGuide.Place?,
        on mapView: NMFMapView
    ) {
        ambientRenderer.render(places, picked: picked, on: mapView) { [weak self] in self?.onTapPlace($0) }
    }

    /// 카메라가 멈췄다. 화면 범위를 바깥에 알린다 — 부를지 말지는 바깥이 정한다.
    nonisolated func mapViewCameraIdle(_ mapView: NMFMapView) {
        Task { @MainActor in
            ambientRenderer.refresh(on: mapView)
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
