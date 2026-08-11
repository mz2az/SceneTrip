import NMapsMap
import SwiftUI

/// 코스용 지도. 방문 순서대로 핀을 찍고 **직선으로만** 잇는다.
///
/// ## 검색 탭의 `NaverMapView` 를 왜 다시 쓰지 않았나
///
/// 그 파일은 손대지 않기로 한 자리이기도 하지만, 고쳐 쓸 수 있었더라도 갈랐을 것이다.
/// 하는 일이 다르다.
///
/// - **선을 긋지 못한다.** 코스에서 지도가 하는 일의 절반은 "어디를 어떤 순서로 도는가"
///   를 보여 주는 것이고, 그것은 핀이 아니라 선이 한다. 검색 지도에는 그 개념이 없다.
/// - **지도를 누를 창구가 없다.** 핀 찍기(임의 지점 추가)는 지도 탭이 입력이다.
///   검색 지도는 핀 탭만 바깥으로 넘긴다.
/// - **카메라 규칙이 반대다.** 검색 지도는 토큰 여섯 개(fit·focus·pan·korea·locate…)로
///   "검색 동선에서 언제 카메라가 움직이는가" 를 아주 세밀하게 정해 뒀다. 코스에서
///   카메라가 움직일 계기는 **일차가 바뀔 때 하나**뿐이다. 그 규칙을 한쪽으로 합치면
///   양쪽 다 읽기 어려워진다.
///
/// 핀 그림(`PinImage`)은 검색 탭 것을 그대로 쓴다. 같은 앱에서 핀이 화면마다 다르게
/// 생기면 그게 같은 물건인지 사용자가 알 수 없다.
struct RouteMapView: UIViewRepresentable {
    /// 지금 보고 있는 일차의 장소들. 배열 순서가 곧 방문 순서이자 핀 번호다.
    let stops: [RouteStop]

    /// 값이 바뀐 순간에만 카메라를 전체 범위로 맞춘다.
    let fitToken: Int

    /// 핀 찍기 모드. 켜져 있을 때만 지도 탭을 바깥으로 넘긴다 — 항상 켜 두면
    /// 지도를 옮기려다 손끝이 미끄러진 것까지 새 장소가 된다.
    let pinning: Bool

    /// 아직 확정하지 않은 핀. 제목·카테고리를 묻는 동안 어디를 찍었는지 보여 준다.
    let pending: RoutePin?

    let onTapMap: (RoutePin) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTapMap: onTapMap)
    }

    func makeUIView(context: Context) -> NMFNaverMapView {
        let view = NMFNaverMapView()
        view.showZoomControls = false
        view.showLocationButton = false
        view.mapView.logoAlign = .leftBottom
        view.mapView.touchDelegate = context.coordinator
        context.coordinator.render(stops: stops, pending: pending, on: view.mapView)
        return view
    }

    func updateUIView(_ view: NMFNaverMapView, context: Context) {
        context.coordinator.onTapMap = onTapMap
        context.coordinator.pinning = pinning
        context.coordinator.render(
            stops: stops,
            pending: pending,
            fitToken: fitToken,
            on: view.mapView
        )
    }

    final class Coordinator: NSObject, NMFMapViewTouchDelegate {
        var onTapMap: (RoutePin) -> Void
        var pinning = false

        private var markers: [NMFMarker] = []
        private var path: NMFPath?
        private var pendingMarker: NMFMarker?
        private var lastKey = ""
        private var lastFitToken = -1

        init(onTapMap: @escaping (RoutePin) -> Void) {
            self.onTapMap = onTapMap
        }

        func mapView(_: NMFMapView, didTapMap latlng: NMGLatLng, point _: CGPoint) {
            guard pinning else { return }
            onTapMap(RoutePin(latitude: latlng.lat, longitude: latlng.lng))
        }

        func render(
            stops: [RouteStop],
            pending: RoutePin?,
            fitToken: Int = -1,
            on mapView: NMFMapView
        ) {
            renderPending(pending, on: mapView)

            // 장소와 순서가 그대로면 다시 그리지 않는다. 체류 시간만 바꿔도 지도가
            // 깜빡이면 편집 중에 눈이 아프다.
            let key = stops.map { "\($0.id)" }.joined(separator: ",")
            if key != lastKey {
                lastKey = key
                drawPins(stops, on: mapView)
                drawLine(stops, on: mapView)
            }

            if fitToken != lastFitToken {
                lastFitToken = fitToken
                // **다음 차례로 미룬다.** 지금 맞추면 첫 화면에서 지도가 아직 제 크기를
                // 못 받은 상태라 엉뚱한 범위로 맞고, 핀 절반이 화면 밖에 남는다(실측).
                DispatchQueue.main.async { [weak mapView] in
                    guard let mapView else { return }
                    self.fit(stops, on: mapView)
                }
            }
        }

        private func drawPins(_ stops: [RouteStop], on mapView: NMFMapView) {
            markers.forEach { $0.mapView = nil }
            markers = stops.enumerated().map { index, stop in
                let marker = NMFMarker(
                    position: NMGLatLng(lat: stop.place.latitude, lng: stop.place.longitude)
                )
                marker.iconImage = PinImage.numbered(index + 1)
                marker.captionText = stop.place.name
                marker.captionMinZoom = 12
                marker.mapView = mapView
                return marker
            }
        }

        /// **직선이다.** 8/11 회의 2부 확정 — 여행 전 계획에서는 길찾기 API 를 부르지
        /// 않으므로 실제 도로 궤적을 알 수 없다. 곡선으로 그리면 실제 경로처럼
        /// 읽히므로 오히려 거짓말이 된다.
        private func drawLine(_ stops: [RouteStop], on mapView: NMFMapView) {
            path?.mapView = nil
            path = nil
            guard stops.count > 1 else { return }
            let points = stops.map {
                NMGLatLng(lat: $0.place.latitude, lng: $0.place.longitude)
            }
            guard let line = NMFPath(points: points) else { return }
            line.color = PinImage.deep
            line.outlineColor = .white
            line.width = 4
            line.outlineWidth = 1
            line.mapView = mapView
            path = line
        }

        private func renderPending(_ pin: RoutePin?, on mapView: NMFMapView) {
            pendingMarker?.mapView = nil
            pendingMarker = nil
            guard let pin else { return }
            let marker = NMFMarker(position: NMGLatLng(lat: pin.latitude, lng: pin.longitude))
            marker.iconImage = PinImage.numbered(nil)
            marker.mapView = mapView
            pendingMarker = marker
        }

        /// 일차 전체가 한 화면에 들어오게 맞춘다. 한 곳뿐이면 확대한다.
        private func fit(_ stops: [RouteStop], on mapView: NMFMapView) {
            guard !stops.isEmpty else { return }
            let lats = stops.map(\.place.latitude)
            let lngs = stops.map(\.place.longitude)
            if stops.count == 1 {
                let update = NMFCameraUpdate(
                    scrollTo: NMGLatLng(lat: lats[0], lng: lngs[0]), zoomTo: 15
                )
                update.animation = .easeIn
                mapView.moveCamera(update)
                return
            }
            let bounds = NMGLatLngBounds(
                southWest: NMGLatLng(lat: lats.min()!, lng: lngs.min()!),
                northEast: NMGLatLng(lat: lats.max()!, lng: lngs.max()!)
            )
            let update = NMFCameraUpdate(fit: bounds, padding: 40)
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }
    }
}

/// 지도를 눌러 찍은 좌표. 아직 이름도 갈래도 없는 상태다.
struct RoutePin: Identifiable, Hashable {
    let id = UUID()
    let latitude: Double
    let longitude: Double
}
