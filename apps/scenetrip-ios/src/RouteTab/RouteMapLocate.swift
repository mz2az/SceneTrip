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
        // 여행 안내가 자리를 주고 있으면 한 번 받기는 무시한다 — 두 출처가 파문을 서로
        // 끌어당기면 점이 튄다(`tripHere`, 2026-09-03).
        guard tripHere == nil else { return }
        here = NMGLatLng(lat: spot.coordinate.latitude, lng: spot.coordinate.longitude)
        guard showingMe, let mapView = mapForLocate else { return }

        showPulse(on: mapView)

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

    /// 파문을 띄운다(없으면 만들고) — 한 번 받기와 여행 안내(`tripHere`)가 같이 쓴다.
    func showPulse(on mapView: NMFMapView) {
        mapForLocate = mapView
        if pulse == nil {
            let view = RadarPulse(tint: UIColor(Color.accentColor))
            mapView.addSubview(view)
            pulse = view
        }
        pulse?.restartIfNeeded()
        positionPulse()
    }

    /// 파문을 내 자리의 화면 좌표에 놓고, 핀과 겹치는지 본다.
    func positionPulse() {
        guard let pulse, let here, let host = mapForLocate else { return }
        let point = host.projection.point(from: here)
        pulse.place(at: point)

        // **내 자리는 무엇에도 가려지지 않는다**(2026-09-04 사용자 지적 — 4번 핀과
        // 이름표 뒤로 점이 숨어 어디 있는지 안 보였다). 앞서는 핀과 겹치면 점을
        // 숨기고 그 핀을 키웠는데(2026-08-27), 촘촘한 코스에서는 늘 어느 핀과 겹쳐
        // 점이 영영 안 보이는 쪽이 됐다. 점은 늘 그리고, 뷰를 맨 위로 올린다 —
        // 헤일로·파문이 뒤에 추가돼도 내 자리가 위다.
        pulse.setCoreHidden(false)
        restoreGrown()
        host.bringSubviewToFront(pulse)
    }

    /// 키워 둔 핀을 원래 크기(그림 크기 그대로)로 되돌린다.
    private func restoreGrown() {
        grown?.width = CGFloat(NMF_MARKER_SIZE_AUTO)
        grown?.height = CGFloat(NMF_MARKER_SIZE_AUTO)
        grown = nil
    }

    // MARK: 헤일로

    /// 해태 핀 뒤의 심장박동. 자리·판이 그대로면 아무것도 안 한다.
    ///
    /// `lift` 는 좌표에서 얼마나 위에 띄우는가 — 해태 핀은 머리(얼굴)가 30pt 위에 있고,
    /// 발바닥 핀은 자리 위에 바로 얹히므로 0 이다(2026-09-03 사용자 지적: 발바닥보다
    /// 위에 떠 있었다).
    func updateHalo(style: HaloPulse.Style, at spot: NMGLatLng?, lift: CGFloat = 30, on mapView: NMFMapView) {
        haloLift = lift
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
        point.y -= haloLift
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
