import NMapsMap
import SwiftUI

/// 편집 지도의 **주변 편의시설** — 화면이 멈출 때마다 그 범위의 가게·숙소·역·명소를
/// 개수 묶음과 갈래 색 점으로 표시한다(MZ2AZ-403).
///
/// `RouteMapView.swift` 에서 갈라 둔 파일이다(타입 길이 한도 — `RouteMapLocate` 와
/// 같은 이유). 자동 갱신은 카메라를 옮기지 않는다. 사용자가 묶음을 누를 때만
/// 확대하거나, 같은 좌표·최대 줌의 구성원 목록을 연다.
extension RouteMapView.Coordinator {
    /// 주변 점을 묶음으로 갈아 끼운다. 같은 목록·축척·언어·선택은 다시 그리지 않는다.
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
