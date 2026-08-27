import CoreLocation
import NMapsMap
import SceneApiClient
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

    /// 「내 위치」가 켜져 있는가. **토글이다** — 한 번 누르면 계속 켜져 있다.
    ///
    /// 처음에는 「누르면 그 자리로 날아가는」 버튼이었는데 쓸모가 없었다(2026-08-24
    /// 사용자 지적) — 촬영지에서 멀리 있으면 날아간 자리에 **성지가 하나도 안 보인다.**
    /// 켜 두면 아래 `focused` 와 짝을 이뤄 「나와 그곳이 같이 보이는 크기」로 맞춘다.
    var showingMe = false

    /// 목록에서 고른 장소. 그 장소로 카메라를 옮긴다.
    ///
    /// - `showingMe` 가 꺼져 있으면 **그 장소만** 확대한다.
    /// - 켜져 있으면 **나와 그 장소가 함께** 들어오는 크기로 맞춘다.
    var focused: RouteStop?

    /// **아직 코스에 없는데 지금 고르는 중인 곳.** 검색·장바구니 시트에서 체크한
    /// 순간 지도에 **빨간 고양이**로 뜬다.
    ///
    /// 시트가 반쯤 올라오는 높이(`.medium`)라 그 위로 지도가 보인다 — 담기 전에
    /// 「거기가 어디쯤인지」를 지도에서 바로 확인할 수 있어야 한다(2026-08-25 사용자
    /// 요청). 이미 코스에 든 곳(`focused`)과 **색으로 갈린다**: 파랑은 이미 담긴 것,
    /// 빨강은 담을까 말까 하는 것.
    var previews: [PlaceSummary] = []

    /// 가이드가 찾아 준 곳. **파란 고양이**로 그리고, 고른 하나만 빨갛고 크게.
    ///
    /// 미리보기(`previews`)와 갈라 두는 이유는 **뜻이 달라서**다 — 미리보기는
    /// 「담을까 말까」이고 이쪽은 「추천받은 것들 중 지금 보는 것」이다. 앞서
    /// 가이드 결과를 미리보기에 섞었더니 15곳이 전부 빨개졌다(사용자 지적).
    var guidePlaces: [RouteGuide.Place] = []

    /// 그중 고른 것.
    var pickedGuide: RouteGuide.Place?

    /// **화면 범위 안의 주변 편의시설.** 챗봇 결과(`guidePlaces`)와 달리 카메라를
    /// 움직이지 않는다 — 배경처럼 깔릴 뿐이다. 네이버 지도가 주변 가게를 늘
    /// 보여 주는 것과 같은 자리다(2026-08-28).
    var ambientPlaces: [RouteGuide.Place] = []

    /// 카메라가 멈췄다. (남, 서, 북, 동, 가운데위도, 가운데경도, 줌).
    var onViewport: ((Double, Double, Double, Double, Double, Double, Double) -> Void)?

    /// 가이드 핀을 눌렀다. 정보 카드를 띄우는 쪽이 받는다.
    var onTapGuide: (RouteGuide.Place) -> Void = { _ in }

    /// 아래에서 일정 시트가 덮고 있는 높이(pt). **카메라가 이 위 영역에만 맞춘다** —
    /// 안 주면 「전체 보기」가 절반은 시트 뒤에 숨는다.
    var bottomInset: CGFloat = 0

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
        // 파문(내 위치)이 카메라를 따라다니려면 움직임을 들어야 한다.
        view.mapView.addCameraDelegate(delegate: context.coordinator)
        context.coordinator.render(stops: stops, pending: pending, on: view.mapView)
        return view
    }

    func updateUIView(_ view: NMFNaverMapView, context: Context) {
        context.coordinator.onTapMap = onTapMap
        context.coordinator.onTapGuide = onTapGuide
        context.coordinator.onViewport = onViewport
        context.coordinator.renderAmbient(ambientPlaces, picked: pickedGuide, on: view.mapView)
        context.coordinator.pinning = pinning
        context.coordinator.apply(bottomInset: bottomInset, to: view.mapView)
        context.coordinator.render(
            stops: stops,
            pending: pending,
            fitToken: fitToken,
            showingMe: showingMe,
            focused: focused,
            previews: previews,
            guidePlaces: guidePlaces,
            pickedGuide: pickedGuide,
            on: view.mapView
        )
    }

    /// 지도를 그리는 일은 전부 메인 스레드에서 일어난다(UIKit 규칙). 명시해 두면
    /// 피노 핀처럼 SwiftUI 를 굽는 것도 여기서 그대로 부를 수 있다.
    @MainActor
    final class Coordinator: NSObject, NMFMapViewTouchDelegate {
        var onTapMap: (RoutePin) -> Void
        var pinning = false

        var markers: [NMFMarker] = []
        private var path: NMFPath?
        private var pendingMarker: NMFMarker?
        private var lastKey = ""
        private var lastFitToken = -1

        let locationManager = CLLocationManager()
        weak var mapForLocate: NMFMapView?
        /// 권한을 물어보고 대답을 기다리는 중인가.
        var awaitingAuthorization = false

        /// 마지막으로 받은 내 자리. 「나와 그곳을 같이 보여 준다」에 쓴다.
        var here: NMGLatLng?
        /// 내 위치의 레이더 파문. 여행 중 화면과 같은 방식 — `RadarPulse` 머리말 참고.
        var pulse: RadarPulse?
        /// 내 자리와 겹쳐서 키워 둔 핀. 겹침이 풀리면 원래 크기로 되돌린다.
        var grown: NMFMarker?
        var showingMe = false
        /// 이미 카메라를 옮긴 조합. 같은 것을 두 번 옮기지 않는다 — SwiftUI 가 뷰를
        /// 다시 그릴 때마다 지도가 튀면 손으로 옮긴 화면이 계속 되돌아간다.
        private var lastCameraKey = ""
        var onTapGuide: (RouteGuide.Place) -> Void = { _ in }
        var onViewport: ((Double, Double, Double, Double, Double, Double, Double) -> Void)?
        /// 주변 편의시설 마커. 챗봇 결과와 살림을 따로 낸다 — 갱신 주기가 다르다.
        var ambientMarkers: [NMFMarker] = []
        var lastAmbientKey = ""
        private var lastInset: CGFloat = 0

        /// 시트가 덮는 만큼 지도의 「보이는 영역」을 줄인다. 카메라 맞추기가 이 값을
        /// 그대로 따른다. **크게 바뀔 때만 다시 맞춘다** — 시트를 끄는 동안 매 픽셀
        /// 카메라가 따라 움직이면 멀미가 난다.
        func apply(bottomInset: CGFloat, to mapView: NMFMapView) {
            guard abs(bottomInset - lastInset) > 40 else { return }
            lastInset = bottomInset
            mapView.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: bottomInset, right: 0)
            lastCameraKey = "" // 다음 render 가 새 영역으로 다시 맞추게 한다.
        }

        init(onTapMap: @escaping (RoutePin) -> Void) {
            self.onTapMap = onTapMap
            super.init()
            locationManager.delegate = self
            locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        }

        func mapView(_: NMFMapView, didTapMap latlng: NMGLatLng, point _: CGPoint) {
            guard pinning else { return }
            onTapMap(RoutePin(latitude: latlng.lat, longitude: latlng.lng))
        }

        func render(
            stops: [RouteStop],
            pending: RoutePin?,
            fitToken: Int = -1,
            showingMe: Bool = false,
            focused: RouteStop? = nil,
            previews: [PlaceSummary] = [],
            guidePlaces: [RouteGuide.Place] = [],
            pickedGuide: RouteGuide.Place? = nil,
            on mapView: NMFMapView
        ) {
            renderPending(pending, on: mapView)

            if showingMe != self.showingMe {
                self.showingMe = showingMe
                if showingMe {
                    startLocating(on: mapView)
                } else {
                    stopLocating()
                }
            }

            // 장소와 순서가 그대로면 다시 그리지 않는다. 체류 시간만 바꿔도 지도가
            // 깜빡이면 편집 중에 눈이 아프다.
            // 고른 장소·담을까 보는 곳이 바뀌어도 다시 그린다 — 고양이 색이 달라진다.
            let key = stops.map { "\($0.id)" }.joined(separator: ",")
                + "|\(focused?.id.uuidString ?? "-")"
                + "|" + previews.map { String($0.id) }.joined(separator: ",")
                + "|" + guidePlaces.map(\.id).joined(separator: ",")
                + "|\(pickedGuide?.id ?? "-")"
            let contentChanged = key != lastKey
            if contentChanged {
                lastKey = key
                grown = nil // 마커를 새로 그리므로 키워 둔 참조도 버린다
                drawPins(stops, focused: focused, previews: previews,
                         guidePlaces: guidePlaces, pickedGuide: pickedGuide, on: mapView)
                drawLine(stops, on: mapView)
                positionPulse()
            }

            // **카메라는 화면에 있는 것을 늘 다 담는다.**
            //
            // 앞서 `fitToken` 을 올릴 때만 맞췄더니, 시트에서 고른 빨간 고양이가
            // 화면 밖이면 안 보였고 장소를 지워도 옛 범위가 그대로 남았다
            // (2026-08-25 사용자 지적). 보여 줄 것이 바뀌면 그때마다 맞추는 것이
            // 맞다 — 그것이 「지도가 지금 무엇을 보여 주는가」의 기본이다.
            //
            // 한 곳을 **콕 집어 골랐을 때만** 예외다. 그때는 그 곳(또는 나와 그 곳)만
            // 크게 본다.
            let cameraKey = "\(focused?.id.uuidString ?? "-")|\(showingMe)|\(key)"
            if cameraKey != lastCameraKey || fitToken != lastFitToken {
                lastCameraKey = cameraKey
                lastFitToken = fitToken
                // **다음 차례로 미룬다.** 지금 맞추면 첫 화면에서 지도가 아직 제 크기를
                // 못 받은 상태라 엉뚱한 범위로 맞고, 핀 절반이 화면 밖에 남는다(실측).
                DispatchQueue.main.async { [weak mapView] in
                    guard let mapView else { return }
                    if let pickedGuide {
                        // 펼친 가게로 확대해 들어간다. 골랐다는 것이 지도에서 보여야 한다.
                        let update = NMFCameraUpdate(
                            scrollTo: NMGLatLng(
                                lat: pickedGuide.latitude, lng: pickedGuide.longitude
                            ),
                            zoomTo: 16
                        )
                        update.animation = .easeIn
                        mapView.moveCamera(update)
                    } else if let focused {
                        self.move(to: focused, on: mapView)
                    } else {
                        self.fit(stops, previews: previews, guidePlaces: guidePlaces, on: mapView)
                    }
                }
            }
        }

        // MARK: 카메라

        /// 고른 장소로 옮긴다.
        ///
        /// **토글이 켜져 있고 내 자리를 알면 둘이 같이 보이는 크기**로, 아니면 그곳만
        /// 확대한다. 이것이 토글을 만든 이유다 — 그냥 내 자리로 날아가면 촬영지가
        /// 화면 밖으로 나가 무엇을 보러 온 화면인지 알 수 없다.
        private func move(to stop: RouteStop, on mapView: NMFMapView) {
            let there = NMGLatLng(lat: stop.place.latitude, lng: stop.place.longitude)

            guard showingMe, let here else {
                let update = NMFCameraUpdate(scrollTo: there, zoomTo: 15)
                update.animation = .easeIn
                mapView.moveCamera(update)
                return
            }

            let bounds = NMGLatLngBounds(
                southWest: NMGLatLng(lat: min(here.lat, there.lat), lng: min(here.lng, there.lng)),
                northEast: NMGLatLng(lat: max(here.lat, there.lat), lng: max(here.lng, there.lng))
            )
            // 여백을 넉넉히 준다 — 파란 점과 핀이 화면 가장자리에 딱 붙으면 잘린
            // 것처럼 보인다.
            let update = NMFCameraUpdate(fit: bounds, padding: 56)
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }

        /// 핀을 세 갈래로 그린다.
        ///
        /// | 무엇 | 그림 |
        /// | --- | --- |
        /// | 코스의 장소 | 번호 핀 ①②③ — 목록과 지도를 잇는 끈이라 그대로 둔다 |
        /// | 그중 **지금 고른 것** | **파란 고양이** |
        /// | 시트에서 **담을까 보는 것** | **빨간 고양이** |
        ///
        /// 색이 갈리는 것이 요점이다 — 파랑은 이미 내 코스에 있는 곳, 빨강은 아직
        /// 아닌 곳이다. 전부 얼굴로 바꾸면 무엇이 몇 번인지 알 수 없다.
        private func drawPins(
            _ stops: [RouteStop],
            focused: RouteStop?,
            previews: [PlaceSummary],
            guidePlaces: [RouteGuide.Place],
            pickedGuide: RouteGuide.Place?,
            on mapView: NMFMapView
        ) {
            markers.forEach { $0.mapView = nil }
            markers = stops.enumerated().map { index, stop in
                let marker = NMFMarker(
                    position: NMGLatLng(lat: stop.place.latitude, lng: stop.place.longitude)
                )
                if stop.id == focused?.id {
                    marker.iconImage = PinoPin.marker(.normal)
                    // 다른 핀에 가리지 않게 위로 올린다.
                    marker.zIndex = 10
                } else {
                    marker.iconImage = PinImage.numbered(index + 1)
                }
                marker.captionText = stop.place.name
                marker.captionMinZoom = 12
                marker.mapView = mapView
                return marker
            }

            // 담을까 보는 곳(검색·장바구니에서 체크한 것) — 빨간 고양이.
            // 코스 핀보다 위에 올려 가리지 않게 한다.
            markers += previews.map { place in
                let marker = NMFMarker(
                    position: NMGLatLng(lat: place.latitude, lng: place.longitude)
                )
                marker.iconImage = PinoPin.marker(.picked)
                marker.captionText = place.name
                marker.captionMinZoom = 10
                marker.zIndex = 20
                marker.mapView = mapView
                return marker
            }

            // 가이드가 찾아 준 곳 — **빨간 점, 고른 하나만 빨간 고양이.**
            // 고양이 열다섯이 몰리면 서로 겹쳐 지도가 고양이밭이 된다.
            markers += guidePlaces.map { place in
                let marker = NMFMarker(
                    position: NMGLatLng(lat: place.latitude, lng: place.longitude)
                )
                let isPicked = place.id == pickedGuide?.id
                marker.iconImage = isPicked ? PinoPin.marker(.picked) : PinoPin.guideDot(place.poiGroup)
                if isPicked {
                    marker.anchor = CGPoint(x: 0.5, y: 1)
                } else {
                    marker.anchor = CGPoint(x: 0.5, y: 0.5) // 점은 자리 위에 얹는다
                }
                marker.captionText = place.name
                marker.captionMinZoom = 14
                marker.zIndex = isPicked ? 30 : 15
                marker.touchHandler = { [weak self] _ in
                    self?.onTapGuide(place)
                    return true
                }
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
            // 옛 파란 민 핀 대신 진도 핀 — 마스코트 교체 후 파란 물방울은
            // 지도에서 은퇴했다(2026-08-28).
            marker.iconImage = PinoPin.marker()
            marker.mapView = mapView
            pendingMarker = marker
        }

        /// **화면에 있는 것 전부**가 한 화면에 들어오게 맞춘다.
        ///
        /// 코스의 장소뿐 아니라 **담을까 보는 곳(빨간 고양이)까지** 센다 — 시트에서
        /// 고른 곳이 화면 밖이면 고른 보람이 없다.
        ///
        /// 한 점뿐이면 확대한다.
        private func fit(
            _ stops: [RouteStop],
            previews: [PlaceSummary] = [],
            guidePlaces: [RouteGuide.Place] = [],
            on mapView: NMFMapView
        ) {
            // **추천이 와 있으면 추천에만 맞춘다.** 코스 전체(수십 km)까지 섞어
            // 맞추면 반경 300 m 짜리 추천 열다섯이 한 점으로 보인다(2026-08-27
            // 사용자 지적). 지금 이 사람의 눈은 추천에 가 있다 — 빨간 점이 전부
            // 보이는 **가장 확대된** 화면이 맞다.
            let spots: [PlaceSummary] = if guidePlaces.isEmpty {
                stops.map(\.place) + previews
            } else {
                guidePlaces.map {
                    PlaceSummary(
                        id: 0, name: $0.name,
                        latitude: $0.latitude, longitude: $0.longitude
                    )
                }
            }
            guard !spots.isEmpty else { return }
            var lats = spots.map(\.latitude)
            var lngs = spots.map(\.longitude)
            // 토글이 켜져 있으면 **나도 화면 안에** 있어야 한다 — 그러자고 켠 것이다.
            if showingMe, let here {
                lats.append(here.lat)
                lngs.append(here.lng)
            }
            if lats.count == 1 {
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
