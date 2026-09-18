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

    /// 값이 바뀌면 **코스 전체**(번호 핀 전부)에 맞춘다 — 고른 줄·챗봇 핀·안내 중이어도.
    /// 동선 최적화가 쓴다: 순서가 통째로 바뀌었으니 봐야 할 것은 코스 전체다. `fitToken` 은
    /// 고른 줄이 있으면 그 곳으로 확대해 들어가, 최적화를 누를 때마다 한 곳으로 줌됐다
    /// (2026-09-17 사용자 지적).
    var courseFitToken = 0

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

    /// **카메라용** 가이드 장소 열쇠 — 갈래 칩으로 **거르기 전** 목록의 id.
    ///
    /// 카메라를 다시 맞출지는 「보여 줄 것이 바뀌었나」로 정하는데, 그 판단에 칩으로 거른
    /// 목록(`guidePlaces`)을 썼더니 **음식점 칩을 끄는 것만으로 지도가 코스 전체로 줌아웃**
    /// 됐다가 켜면 다시 줌인 됐다(2026-09-17 사용자 지적). 칩은 「무엇을 가릴까」이지
    /// 「어디를 볼까」가 아니다 — 카메라는 이 열쇠만 본다.
    var guideCameraKey = ""

    /// **화면 범위 안의 주변 편의시설.** 챗봇 결과(`guidePlaces`)와 달리 카메라를
    /// 움직이지 않는다 — 배경처럼 깔릴 뿐이다. 네이버 지도가 주변 가게를 늘
    /// 보여 주는 것과 같은 자리다(2026-08-28).
    var ambientPlaces: [RouteGuide.Place] = []

    /// 카메라가 멈췄다. (남, 서, 북, 동, 가운데위도, 가운데경도, 줌).
    var onViewport: ((Double, Double, Double, Double, Double, Double, Double) -> Void)?

    /// 가이드 핀을 눌렀다. 정보 카드를 띄우는 쪽이 받는다.
    var onTapGuide: (RouteGuide.Place) -> Void = { _ in }

    /// **미리보기 핀**(빨간 해태)을 눌렀다.
    ///
    /// 앞서 이 핀에만 손잡이가 없어서, 챗봇이 「주변 음식점」을 찍어 주면(`map.focus` 는
    /// 결과를 미리보기로 세운다) 눌러도 아무 일이 없었다(2026-09-16 사용자 지적).
    var onTapPreview: (PlaceSummary) -> Void = { _ in }

    /// 아래에서 일정 시트가 덮고 있는 높이(pt). **카메라가 이 위 영역에만 맞춘다** —
    /// 안 주면 「전체 보기」가 절반은 시트 뒤에 숨는다.
    var bottomInset: CGFloat = 0

    // 여행 안내(2026-09-03, 계획 trip-mode.md §8) — 별도 길찾기 창 대신 **이 지도**가
    // 경로를 그린다. 계획선(직선)은 그대로 두고 그 위에 실제 길을 얹는다.

    /// 안내 중의 내 자리 — 계속 받는 위치(또는 데모 주행). 있으면 한 번 받기 대신
    /// 이것으로 파문을 띄운다.
    var tripHere: TripSpot?

    /// 지금 안내 중인 목적지. 해태 핀과 헤일로가 여기 선다.
    var navTarget: RouteStop?

    /// 그 목적지로 **가는 중**인가(도착해 서 있는 게 아니라). 가는 중이면 그곳에서 나가는
    /// 계획선을 남긴다 — 도착하면 끊고 미리보기 점선이 그 자리를 잇는다.
    var navGuiding = false

    /// 안내 경로 — 구간별 **실제 길 좌표**. 비어 있으면 아무것도 안 긋는다(직선으로
    /// 대신 긋지 않는다 — 계획선은 이미 있고, 그것은 「길」이 아니다).
    var legs: [RouteLeg] = []

    /// 「나와 목적지」로 카메라를 되돌리라는 신호. 값이 바뀔 때만 움직인다.
    var recenterTick = 0

    /// **내 자리에서 이곳까지 직선을 미리 긋는다** — 길찾기 결과가 오기 전, 또는 도착해
    /// 다음 곳을 고르기 전. 실제 경로(`legs`)가 오면 부르는 쪽이 nil 로 바꿔 직선이
    /// 사라지고 세세한 길만 남는다(2026-09-03 사용자 결정).
    var previewTo: RouteStop?

    /// 번호 핀을 눌렀다. 성지 카드를 띄우는 쪽이 받는다.
    var onTapStop: (RouteStop) -> Void = { _ in }

    /// 발자취 — 지나온 자리. `footprintsOn` 이면 황금 발자국으로 그린다.
    var footprints: [FootprintPoint] = []
    var footprintsOn = false

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
        context.coordinator.onTapPreview = onTapPreview
        context.coordinator.onViewport = onViewport
        context.coordinator.onTapStop = onTapStop
        context.coordinator.renderAmbient(ambientPlaces, picked: pickedGuide, on: view.mapView)
        context.coordinator.pinning = pinning
        context.coordinator.apply(bottomInset: bottomInset, to: view.mapView)
        context.coordinator.applyTrip(here: tripHere, on: view.mapView)
        context.coordinator.renderPreview(to: previewTo, on: view.mapView)
        context.coordinator.renderFootprints(footprintsOn ? footprints : [], on: view.mapView)
        context.coordinator.render(
            stops: stops,
            pending: pending,
            fitToken: fitToken,
            courseFitToken: courseFitToken,
            showingMe: showingMe,
            focused: focused,
            previews: previews,
            guidePlaces: guidePlaces,
            pickedGuide: pickedGuide,
            guideCameraKey: guideCameraKey,
            navTarget: navTarget,
            navGuiding: navGuiding,
            legs: legs,
            recenterTick: recenterTick,
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
        /// 계획선(직선). 다녀온 곳에서 끊기므로 여러 토막일 수 있다.
        var planPaths: [NMFPath] = []
        /// 「내 자리 → 다음 곳」 직선 미리보기(`RouteMapTrip.swift`).
        var previewPath: NMFPath?
        var lastPreviewKey = ""
        /// 발자국 마커(`RouteMapTrip.swift`).
        var footMarkers: [NMFMarker] = []
        var lastFootKey = ""
        /// 마지막으로 받은 발자취 전체 — 카메라가 움직이면 줌에 맞춰 다시 솎는다.
        var lastFootPoints: [FootprintPoint] = []
        private var pendingMarker: NMFMarker?
        private var lastKey = ""
        private var lastFitToken = -1
        private var lastCourseFitToken = 0
        private var courseFitJustRan = false

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
        /// 진도 핀 뒤의 심장박동 헤일로. 고른 곳(빨강) > 눌러 둔 곳(파랑) 순.
        var halo: HaloPulse?
        var haloAt: NMGLatLng?
        /// 헤일로를 좌표에서 얼마나 위에 띄우는가(해태 핀 30, 발바닥 핀 0).
        var haloLift: CGFloat = 30
        var showingMe = false
        /// 이미 카메라를 옮긴 조합. 같은 것을 두 번 옮기지 않는다 — SwiftUI 가 뷰를
        /// 다시 그릴 때마다 지도가 튀면 손으로 옮긴 화면이 계속 되돌아간다.
        private var lastCameraKey = ""
        var onTapGuide: (RouteGuide.Place) -> Void = { _ in }
        var onTapPreview: (PlaceSummary) -> Void = { _ in }
        var onViewport: ((Double, Double, Double, Double, Double, Double, Double) -> Void)?
        /// 주변 편의시설 마커. 챗봇 결과와 살림을 따로 낸다 — 갱신 주기가 다르다.
        var ambientMarkers: [NMFMarker] = []
        var lastAmbientKey = ""
        private var lastInset: CGFloat = 0

        /// 번호 핀을 눌렀다.
        var onTapStop: (RouteStop) -> Void = { _ in }
        /// 여행 안내가 준 내 자리. 있는 동안은 한 번 받기(`didUpdateLocations`)를 무시한다.
        var tripHere: NMGLatLng?
        /// 안내 경로선(구간마다 하나 — 도보 점선·대중교통 실선). `RouteMapTrip.swift` 가 그린다.
        var legPaths: [NMFPath] = []
        var lastLegsKey = ""
        private var lastTripCameraKey = ""

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
            courseFitToken: Int = 0,
            showingMe: Bool = false,
            focused: RouteStop? = nil,
            previews: [PlaceSummary] = [],
            guidePlaces: [RouteGuide.Place] = [],
            pickedGuide: RouteGuide.Place? = nil,
            guideCameraKey: String = "",
            navTarget: RouteStop? = nil,
            navGuiding: Bool = false,
            legs: [RouteLeg] = [],
            recenterTick: Int = 0,
            on mapView: NMFMapView
        ) {
            renderPending(pending, on: mapView)
            // 지나온 길은 자른다. `legs` 는 안내 중이거나 도착 뒤 핀까지 걷는 동안에만 온다
            // (`TripSession.drawnLegs`) — 계획을 보는 중에는 비어 있어 자를 것이 없다.
            renderLegs(legs, to: navTarget, from: tripHere, on: mapView)

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
            let key = stops.map { "\($0.id)\($0.visited ? "v" : "")" }.joined(separator: ",")
                + "|\(focused?.id.uuidString ?? "-")"
                + "|" + previews.map { String($0.id) }.joined(separator: ",")
                + "|" + guidePlaces.map { "\($0.id)\($0.linked == true ? "n" : "")" }.joined(separator: ",")
                + "|\(pickedGuide?.id ?? "-")"
                + "|\(navTarget?.id.uuidString ?? "-")\(navGuiding ? "g" : "")"
            let contentChanged = key != lastKey
            if contentChanged {
                lastKey = key
                grown = nil // 마커를 새로 그리므로 키워 둔 참조도 버린다
                drawPins(stops, focused: focused, previews: previews,
                         guidePlaces: guidePlaces, pickedGuide: pickedGuide,
                         navTarget: navTarget, on: mapView)
                drawLine(stops, keepFrom: navGuiding ? navTarget : nil, on: mapView)
                positionPulse()
            }

            // 코스 전체를 보라는 신호 — 아래 규칙들(안내 중·고른 곳)보다 먼저다. 같은 차례에
            // 일반 규칙이 또 움직이지 않게 이번 한 번은 그쪽을 건너뛴다(`courseFitJustRan`).
            if courseFitToken != lastCourseFitToken {
                lastCourseFitToken = courseFitToken
                courseFitJustRan = true
                DispatchQueue.main.async { [weak mapView] in
                    guard let mapView else { return }
                    self.fit(stops, withMe: true, on: mapView)
                }
            }

            // **안내 중에는 카메라가 「나와 목적지와 길」을 본다.** 경로가 오거나 되돌리기
            // 신호가 올 때만 움직인다 — 걸을 때마다 따라가면 손으로 옮긴 화면이 계속
            // 되돌아간다(아래 일반 규칙과 같은 이유).
            if let navTarget {
                let tripKey = "\(navTarget.id)|\(legs.map { "\($0.mode)\($0.path.count)" }.joined())|\(recenterTick)"
                if tripKey != lastTripCameraKey {
                    lastTripCameraKey = tripKey
                    DispatchQueue.main.async { [weak mapView] in
                        guard let mapView else { return }
                        self.fitTrip(to: navTarget, legs: legs, on: mapView)
                    }
                }
                // 목적지 사본의 visited 는 낡을 수 있다(도착 직후) — 목록의 최신 값으로 본다.
                let targetVisited = stops.first { $0.id == navTarget.id }?.visited ?? navTarget.visited
                updateHalo(
                    style: .brand,
                    at: NMGLatLng(lat: navTarget.place.latitude, lng: navTarget.place.longitude),
                    lift: targetVisited ? 0 : 30, // 도착했으면 발바닥 핀 — 자리 위에
                    on: mapView
                )
                return
            }
            lastTripCameraKey = ""

            // **카메라는 화면에 있는 것을 늘 다 담는다.**
            //
            // 앞서 `fitToken` 을 올릴 때만 맞췄더니, 시트에서 고른 빨간 고양이가
            // 화면 밖이면 안 보였고 장소를 지워도 옛 범위가 그대로 남았다
            // (2026-08-25 사용자 지적). 보여 줄 것이 바뀌면 그때마다 맞추는 것이
            // 맞다 — 그것이 「지도가 지금 무엇을 보여 주는가」의 기본이다.
            //
            // 한 곳을 **콕 집어 골랐을 때만** 예외다. 그때는 그 곳(또는 나와 그 곳)만
            // 크게 본다.
            // 헤일로 대상: 고른 가게 > 목록에서 누른 코스 장소. 없으면 끈다.
            if let pickedGuide {
                updateHalo(
                    style: .picked,
                    at: NMGLatLng(lat: pickedGuide.latitude, lng: pickedGuide.longitude),
                    on: mapView
                )
            } else if let focused {
                updateHalo(
                    style: .brand,
                    at: NMGLatLng(lat: focused.place.latitude, lng: focused.place.longitude),
                    lift: (stops.first { $0.id == focused.id }?.visited ?? focused.visited) ? 0 : 30,
                    on: mapView
                )
            } else {
                updateHalo(style: .brand, at: nil, on: mapView)
            }

            // 카메라 열쇠는 **칩으로 거른 목록을 보지 않는다** — 핀을 다시 그리는 열쇠(`key`)와
            // 갈라 둔 이유다. 칩을 켜고 꺼도 화면은 그 자리에 있어야 한다.
            let cameraContent = stops.map { "\($0.id)" }.joined(separator: ",")
                + "|" + previews.map { String($0.id) }.joined(separator: ",")
                + "|" + guideCameraKey
            let cameraKey = "\(focused?.id.uuidString ?? "-")|\(showingMe)|\(cameraContent)"
            if courseFitJustRan {
                // 방금 코스 전체에 맞췄다 — 열쇠만 따라잡고 카메라는 그대로 둔다.
                courseFitJustRan = false
                lastCameraKey = cameraKey
                lastFitToken = fitToken
            } else if cameraKey != lastCameraKey || fitToken != lastFitToken {
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
            withMe: Bool = false,
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
            //
            // 동선 최적화(`withMe`)도 나를 담는다 — 「여기서 가까운 곳이 1번」인데 여기가 안 보이면
            // 왜 그 순서인지 알 수 없다. 단 **같은 지역일 때만**이다(최적화가 기준점으로 쓰는 조건과
            // 같다). 도쿄에서 서울 코스를 짜는데 나까지 담으면 동아시아 지도가 된다.
            let nearMe = here.flatMap { spot in
                RouteGeometry.usableAnchor(
                    PlaceSummary(id: 0, name: "여기", latitude: spot.lat, longitude: spot.lng), for: stops
                )
            } != nil
            if let here, showingMe || (withMe && nearMe) {
                lats.append(here.lat)
                lngs.append(here.lng)
            }
            // 한 점이거나 **한 건물에 몰린 점들**이면 범위 맞추기를 하지 않는다 — 범위가 0 에
            // 가까우면 SDK 가 끝까지 확대해 1 m 축척의 빈 화면이 된다(2026-09-17, 네이버에
            // 연결된 곳만 남기자 같은 건물의 카페 둘만 남았다). 약 60 m 아래면 한 점으로 본다.
            let span = max(lats.max()! - lats.min()!, lngs.max()! - lngs.min()!)
            if lats.count == 1 || span < 0.0006 {
                let update = NMFCameraUpdate(
                    scrollTo: NMGLatLng(
                        lat: (lats.max()! + lats.min()!) / 2, lng: (lngs.max()! + lngs.min()!) / 2
                    ),
                    zoomTo: guidePlaces.isEmpty ? 15 : 16
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
