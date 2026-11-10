import CoreLocation
import NMapsMap

extension NMFCameraUpdate {
    /// 지금 화면에서 `target` 까지의 거리로 움직임을 고른다(§10-3, `MapCameraMotionRules`) — 가까우면 0.6초 감속, 멀면 한 번에.
    func applyMotion(toward target: NMGLatLng, on mapView: NMFMapView) {
        let center = mapView.cameraPosition.target
        let visible = mapView.contentBounds
        let width = Self.meters(
            NMGLatLng(lat: center.lat, lng: visible.southWestLng), NMGLatLng(lat: center.lat, lng: visible.northEastLng)
        )
        switch MapCameraMotionRules.motion(distanceMeters: Self.meters(center, target), visibleWidthMeters: width) {
        case let .smooth(duration):
            animation = .easeOut
            animationDuration = duration
        case .jump:
            animation = .none
        }
    }

    private static func meters(_ start: NMGLatLng, _ end: NMGLatLng) -> Double {
        let here = CLLocation(latitude: start.lat, longitude: start.lng)
        return here.distance(from: CLLocation(latitude: end.lat, longitude: end.lng))
    }
}
