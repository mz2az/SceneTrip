import CoreLocation
import NMapsMap
import SwiftUI

/// 편집 지도의 **내 위치** — 좌표 받기, 레이더 파문, 핀과 겹칠 때의 처리.
///
/// `RouteMapView.swift` 에서 떼어 냈다(타입 길이 한도). 지도 그리기와 위치 추적은
/// 서로 만나는 곳이 `render` 한 군데뿐이라 자르는 선이 깨끗하다.
extension RouteMapView.Coordinator: CLLocationManagerDelegate, NMFMapViewCameraDelegate {
    // MARK: 내 위치

    /// 토글을 켰다. 좌표를 받기 시작하고, 자리가 오면 **레이더 파문**을 띄운다.
    ///
    /// SDK 의 `positionMode` 를 쓰지 않는다 — 그 파란 점은 SDK 의 자체 위치
    /// 추적에 묶여 있어 여기서는 아무것도 안 그렸다(2026-08-27 사용자 확인).
    /// 검색 탭·여행 중 화면과 같은 파문(`RadarPulse`)을 우리가 직접 얹는다.
    func startLocating(on mapView: NMFMapView) {
        mapForLocate = mapView

        switch locationManager.authorizationStatus {
        case .notDetermined:
            awaitingAuthorization = true
            locationManager.requestWhenInUseAuthorization()
        case .restricted, .denied:
            return // 앱 안에서 풀 수 없다.
        default:
            locationManager.requestLocation()
        }
    }

    /// 토글을 껐다. 파문을 지우고 **마지막 자리도 버린다** — 껐는데 「나와 그곳」이
    /// 계속 보이면 끈 것이 아니다.
    func stopLocating() {
        here = nil
        pulse?.removeFromSuperview()
        pulse = nil
        restoreGrown()
    }

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        guard awaitingAuthorization else { return }
        switch manager.authorizationStatus {
        case .notDetermined:
            return
        case .restricted, .denied:
            awaitingAuthorization = false
        default:
            awaitingAuthorization = false
            manager.requestLocation()
        }
    }

    func locationManager(_: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let spot = locations.last else { return }
        here = NMGLatLng(lat: spot.coordinate.latitude, lng: spot.coordinate.longitude)
        guard showingMe, let mapView = mapForLocate else { return }

        if pulse == nil {
            let view = RadarPulse(tint: UIColor(Color.accentColor))
            mapView.addSubview(view)
            pulse = view
        }
        pulse?.restartIfNeeded()
        positionPulse()

        // 자리가 화면 밖이면 **범위를 넓혀 담는다.** 토글을 켰는데 아무 일도
        // 없으면 안 되는 것으로 보인다(2026-08-27 사용자 지적 — 추천 핀에만
        // 맞춘 화면 밖에 내가 있었다).
        if let here, !mapView.contentBounds.hasPoint(here) {
            let bounds = mapView.contentBounds
            let update = NMFCameraUpdate(fit: NMGLatLngBounds(
                southWest: NMGLatLng(
                    lat: min(bounds.southWestLat, here.lat),
                    lng: min(bounds.southWestLng, here.lng)
                ),
                northEast: NMGLatLng(
                    lat: max(bounds.northEastLat, here.lat),
                    lng: max(bounds.northEastLng, here.lng)
                )
            ), padding: 48)
            update.animation = .easeIn
            mapView.moveCamera(update)
        }
    }

    func locationManager(_: CLLocationManager, didFailWithError _: Error) {
        // 실내·기내 모드에서 실제로 난다. 자리를 모르면 그곳만 보여 준다.
    }

    // MARK: 파문

    /// 파문을 내 자리의 화면 좌표에 놓고, 핀과 겹치는지 본다.
    func positionPulse() {
        guard let pulse, let here, let host = mapForLocate else { return }
        let point = host.projection.point(from: here)
        pulse.place(at: point)

        // **핀과 겹치면 점 대신 핀이 뛴다.** 가까운 핀(화면 28pt 안)을 찾아
        // 키우고 가운데 점을 숨긴다 — 파문은 계속 퍼지므로 그 핀이 고동치는
        // 것으로 읽힌다(2026-08-27 사용자 요청).
        let near = markers.min { lhs, rhs in
            gap(point, host.projection.point(from: lhs.position))
                < gap(point, host.projection.point(from: rhs.position))
        }
        let hit: NMFMarker? = near.flatMap {
            gap(point, host.projection.point(from: $0.position)) < 28 ? $0 : nil
        }
        if hit !== grown {
            restoreGrown()
            if let hit {
                let size = hit.iconImage.image.size
                hit.width = size.width * 1.3
                hit.height = size.height * 1.3
                grown = hit
            }
        }
        pulse.setCoreHidden(hit != nil)
    }

    /// 키워 둔 핀을 원래 크기(그림 크기 그대로)로 되돌린다.
    private func restoreGrown() {
        grown?.width = CGFloat(NMF_MARKER_SIZE_AUTO)
        grown?.height = CGFloat(NMF_MARKER_SIZE_AUTO)
        grown = nil
    }

    private func gap(_ lhs: CGPoint, _ rhs: CGPoint) -> CGFloat {
        hypot(lhs.x - rhs.x, lhs.y - rhs.y)
    }

    // MARK: 헤일로

    /// 해태 핀 뒤의 심장박동. 자리·판이 그대로면 아무것도 안 한다.
    func updateHalo(style: HaloPulse.Style, at spot: NMGLatLng?, on mapView: NMFMapView) {
        guard let spot else {
            halo?.removeFromSuperview()
            halo = nil
            haloAt = nil
            return
        }
        mapForLocate = mapView // 위치 토글과 무관하게 헤일로도 카메라를 따라야 한다
        if halo == nil || halo?.style != style {
            halo?.removeFromSuperview()
            let view = HaloPulse(style: style)
            // 지도 **위에** 얹는다 — 밑에 넣으면 타일에 가려 안 보인다(파문과 같은
            // 규칙). 핀은 고리의 투명한 가운데로 비켜 간다(`HaloPulse` 머리말).
            mapView.addSubview(view)
            halo = view
        }
        haloAt = spot
        halo?.restartIfNeeded()
        positionHalo()
    }

    /// 헤일로를 핀 **머리**(얼굴)에 맞춘다 — 좌표는 핀 끝이라 위로 올린다.
    func positionHalo() {
        guard let halo, let haloAt, let host = mapForLocate else { return }
        var point = host.projection.point(from: haloAt)
        point.y -= 30
        halo.place(at: point)
    }

    /// 지도가 움직이는 **동안 계속** 부른다 — 멈춘 뒤에만 옮기면 미는 사이에
    /// 파문이 제자리에 남는다(`RouteNavMapView` 와 같은 규칙).
    nonisolated func mapView(_: NMFMapView, cameraIsChangingByReason _: Int) {
        Task { @MainActor in
            self.positionPulse()
            self.positionHalo()
        }
    }

    nonisolated func mapView(_: NMFMapView, cameraDidChangeByReason _: Int, animated _: Bool) {
        Task { @MainActor in
            self.positionPulse()
            self.positionHalo()
        }
    }
}
