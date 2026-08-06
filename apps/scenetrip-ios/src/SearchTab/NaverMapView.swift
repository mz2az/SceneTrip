import NMapsMap
import SwiftUI

/// 네이버 지도를 SwiftUI 에 얹는다. 핀은 `pins` 가 바뀔 때만 다시 그린다.
///
/// **카메라와 핀을 따로 다룬다.** 계획서 §3-5 가 그렇게 정했다 — 검색이 확정될 때만
/// 카메라를 결과 범위로 맞추고, 카테고리 칩은 핀만 갈고 카메라는 건드리지 않는다.
/// 칩을 껐다 켤 때 보던 위치를 잃지 않게 하는 것이 그 규칙의 목적이다.
struct NaverMapView: UIViewRepresentable {
    let pins: [SceneRow]
    /// 값이 바뀐 순간에만 카메라를 그 범위로 맞춘다. 칩 조작에서는 그대로 둔다.
    let fitToken: Int
    let onTapPin: (SceneRow) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTapPin: onTapPin)
    }

    func makeUIView(context: Context) -> NMFNaverMapView {
        let view = NMFNaverMapView()
        view.showZoomControls = false
        view.showLocationButton = false
        view.mapView.logoAlign = .leftBottom
        view.mapView.moveCamera(
            NMFCameraUpdate(scrollTo: NMGLatLng(lat: 37.5666, lng: 126.9784), zoomTo: 11)
        )
        context.coordinator.attach(to: view.mapView)
        return view
    }

    func updateUIView(_ view: NMFNaverMapView, context: Context) {
        context.coordinator.onTapPin = onTapPin
        context.coordinator.render(pins: pins, fitToken: fitToken, on: view.mapView)
    }

    final class Coordinator {
        var onTapPin: (SceneRow) -> Void
        private var markers: [NMFMarker] = []
        private var lastPinKey: String = ""
        /// 첫 렌더에서는 카메라를 맞추지 않는다 — 초기값이 fitToken 의 초기값과 같다.
        /// 첫 진입은 서울 중심으로 열려야 하고(MZ2AZ-162, 계획서 §3-1), 전체 데이터에
        /// 맞추면 전국이 잡혀 "서울 여행 앱" 으로 보이지 않는다.
        private var lastFitToken: Int = 0
        private weak var mapView: NMFMapView?

        init(onTapPin: @escaping (SceneRow) -> Void) {
            self.onTapPin = onTapPin
        }

        func attach(to mapView: NMFMapView) {
            self.mapView = mapView
        }

        func render(pins: [SceneRow], fitToken: Int, on mapView: NMFMapView) {
            let key = pins.map(\.id).joined(separator: ",")
            let pinsChanged = key != lastPinKey
            let shouldFit = fitToken != lastFitToken

            if pinsChanged {
                lastPinKey = key
                markers.forEach { $0.mapView = nil }
                markers = pins.map { row in
                    let marker = NMFMarker(position: NMGLatLng(lat: row.lat, lng: row.lng))
                    marker.captionText = row.placeName
                    marker.captionMinZoom = 13
                    marker.iconTintColor = .systemBlue
                    marker.touchHandler = { [weak self] _ in
                        self?.onTapPin(row)
                        return true
                    }
                    marker.mapView = mapView
                    return marker
                }
            }

            // 카메라는 fitToken 이 바뀐 순간에만 움직인다 — 칩은 이 값을 올리지 않는다.
            if shouldFit {
                lastFitToken = fitToken
                fit(pins, on: mapView)
            }
        }

        private func fit(_ pins: [SceneRow], on mapView: NMFMapView) {
            guard !pins.isEmpty else { return }
            if pins.count == 1, let only = pins.first {
                mapView.moveCamera(
                    NMFCameraUpdate(
                        scrollTo: NMGLatLng(lat: only.lat, lng: only.lng), zoomTo: 15
                    )
                )
                return
            }
            let lats = pins.map(\.lat), lngs = pins.map(\.lng)
            let bounds = NMGLatLngBounds(
                southWest: NMGLatLng(lat: lats.min()!, lng: lngs.min()!),
                northEast: NMGLatLng(lat: lats.max()!, lng: lngs.max()!)
            )
            let update = NMFCameraUpdate(fit: bounds, padding: 48)
            // 시트가 화면 아래쪽을 덮으므로 보이는 영역의 가운데로 맞춘다.
            update.pivot = CGPoint(x: 0.5, y: 0.32)
            mapView.moveCamera(update)
        }
    }
}
