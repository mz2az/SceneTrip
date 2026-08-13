import CoreLocation
import NMapsMap
import SceneApiClient
import SwiftUI

/// 네이버 지도를 SwiftUI 에 얹는다. 핀은 `pins` 가 바뀔 때만 다시 그린다.
///
/// **카메라와 핀을 따로 다룬다.** 계획서 §3-5 가 그렇게 정했다 — 검색이 확정될 때만
/// 카메라를 결과 범위로 맞추고, 카테고리 칩은 핀만 갈고 카메라는 건드리지 않는다.
/// 칩을 껐다 켤 때 보던 위치를 잃지 않게 하는 것이 그 규칙의 목적이다.
///
/// 카메라를 움직이는 계기는 셋으로 나뉜다:
/// - `fitToken` — 검색 확정·드릴다운. 결과 **전체 범위**로 맞춘다.
/// - `focusToken` — 장소 선택(목록 행·핀). `focus` 한 곳으로 **확대**한다.
/// - `panToken` — 담기(+). `pan` 한 곳이 보이게 **이동만** 한다. 줌은 그대로다.
///
/// 어느 이동이든 검색바(위)와 바텀시트(아래)가 덮는 영역을 `contentInset` 으로
/// 빼고 남는 지도 영역의 가운데에 놓는다 — 이것 없이 fit 하면 시트 뒤에 핀이
/// 숨는다(실측). 인셋은 카메라를 움직이는 순간에만 갱신한다: 시트를 드래그할
/// 때마다 갱신하면 지도가 덩달아 출렁인다.
/// 지도가 지금 어디를 보고 있는지 **바깥에서 물어보는** 창구.
///
/// "현 지도 내 성지 검색" 은 누르는 순간의 중심 좌표만 있으면 된다. 카메라가 움직일
/// 때마다 상태를 갱신하면 화면이 매 프레임 다시 그려지므로, 값을 밀어 주지 않고
/// **필요할 때 꺼내 가게** 한다.
final class MapCamera {
    fileprivate weak var mapView: NMFMapView?

    /// 지금 보이는 범위가 남한을 **완전히 벗어났는가.**
    ///
    /// 걸치기만 해도 밖으로 치면 강릉·부산 근처에서 버튼이 깜빡인다. 그래서
    /// 겹치는 부분이 하나도 없을 때만 참이다.
    var isOutsideKorea: Bool {
        guard let bounds = mapView?.contentBounds else { return false }
        let korea = NaverMapView.Coordinator.korea
        let lngApart = bounds.northEastLng < korea.southWestLng
            || bounds.southWestLng > korea.northEastLng
        let latApart = bounds.northEastLat < korea.southWestLat
            || bounds.southWestLat > korea.northEastLat
        return lngApart || latApart
    }

    /// 지금 화면에 보이는 지도 범위를 계약의 `bbox` 문자열로.
    ///
    /// 형식은 `minLng,minLat,maxLng,maxLat` — GeoJSON 과 같은 **경도-위도** 순서다.
    /// 위도-경도로 착각하기 쉬워 여기서 한 번만 만든다.
    ///
    /// 지도가 아직 만들어지지 않았으면 nil.
    var boundingBox: String? {
        guard let bounds = mapView?.contentBounds else { return nil }
        return "\(bounds.southWestLng),\(bounds.southWestLat),\(bounds.northEastLng),\(bounds.northEastLat)"
    }
}

struct NaverMapView: UIViewRepresentable {
    let pins: [PlaceSummary]

    /// 바깥이 카메라를 읽어 가는 창구. 지도가 만들어질 때 자신을 꽂아 준다.
    let camera: MapCamera

    /// 핀에 번호를 찍을지. 첫 화면의 인기 목록에서는 끈다 — PinImage.numbered 주석 참고.
    let numbered: Bool

    /// 값이 바뀐 순간에만 **남한 전체**가 보이게 맞춘다.
    let koreaToken: Int

    /// 값이 바뀐 순간에만 **내 위치**로 이동한다. 권한이 없으면 물어본다.
    let locateToken: Int

    /// 값이 바뀐 순간에만 카메라를 결과 전체 범위로 맞춘다. 칩 조작에서는 그대로 둔다.
    let fitToken: Int

    /// 값이 바뀐 순간에만 `focus` 로 카메라를 확대한다.
    let focusToken: Int
    let focus: PlaceSummary?

    /// 값이 바뀐 순간에만 `pan` 이 가운데 오도록 이동한다. 줌은 바꾸지 않는다.
    let panToken: Int
    let pan: PlaceSummary?

    /// 시트가 지금 덮고 있는 **실제 높이(pt)**.
    ///
    /// 비율이 아니라 실제 값이어야 한다. 비율로 계산하면 시트의 진짜 높이와 어긋나
    /// **로고와 축척이 시트보다 한참 위에** 떠 버린다(실측).
    let sheetHeight: CGFloat

    /// 「내 위치」 가 실패했을 때만 불린다. 성공하면 지도가 움직이므로 알릴 것이 없다.
    ///
    /// `onTapPin` **앞에** 둔다 — 뒤에 두면 호출 쪽의 트레일링 클로저가 이쪽에
    /// 붙어 버린다.
    let onLocateFailure: (LocateOutcome) -> Void
    let onTapPin: (PlaceSummary) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onTapPin: onTapPin, onLocateFailure: onLocateFailure)
    }

    func makeUIView(context: Context) -> NMFNaverMapView {
        let view = NMFNaverMapView()
        view.showZoomControls = false
        view.showLocationButton = false
        view.mapView.logoAlign = .leftBottom
        // 첫 진입은 **남한 전체** 다. 촬영지가 서울에만 있는 것이 아니라
        // 강릉·포항·제주까지 흩어져 있어, 서울만 비추면 나머지가 없는 것처럼 보인다.
        view.mapView.moveCamera(NMFCameraUpdate(fit: Self.Coordinator.korea, padding: 24))
        context.coordinator.attach(to: view.mapView)
        camera.mapView = view.mapView
        return view
    }

    func updateUIView(_ view: NMFNaverMapView, context: Context) {
        context.coordinator.onTapPin = onTapPin
        context.coordinator.onLocateFailure = onLocateFailure
        context.coordinator.render(
            pins: pins,
            numbered: numbered,
            koreaToken: koreaToken,
            locateToken: locateToken,
            fitToken: fitToken,
            focusToken: focusToken,
            focus: focus,
            panToken: panToken,
            pan: pan,
            sheetHeight: sheetHeight,
            on: view.mapView
        )
    }

    /// `NSObject` 를 상속하는 이유는 `CLLocationManagerDelegate` 때문이다.
    /// 권한 응답과 좌표 도착이 **둘 다 비동기**라 델리게이트 없이는 「내 위치」 를
    /// 만들 수 없다 — 예전 구현이 동기 코드라 파란 점만 켜고 끝났다.
    final class Coordinator: NSObject, CLLocationManagerDelegate {
        var onTapPin: (PlaceSummary) -> Void
        var onLocateFailure: (LocateOutcome) -> Void
        private var markers: [NMFMarker] = []
        private var lastPinKey: String = ""

        /// 첫 렌더에서는 카메라를 맞추지 않는다 — 초기값이 fitToken 의 초기값과 같다.
        /// 첫 진입 카메라는 `makeUIView` 가 `korea` 로 이미 맞춰 놨다(97행).
        ///
        /// MZ2AZ-162 는 처음에 **서울 중심**으로 적혀 있었으나 뒤집혔다. 촬영지가
        /// 서울에만 있지 않아 지방 촬영지를 가진 작품이 첫 화면에서 통째로 사라졌다.
        private var lastFitToken: Int = 0
        private var lastFocusToken: Int = 0
        private var lastPanToken: Int = 0
        private var lastKoreaToken: Int = 0
        private var lastLocateToken: Int = 0
        private let locationManager = CLLocationManager()

        /// 권한을 물어보는 중이라 **대답을 기다리는** 상태.
        ///
        /// 이것이 없으면 앱이 포그라운드로 돌아올 때마다 오는 권한 콜백에 반응해
        /// 사용자가 누르지도 않았는데 지도가 내 위치로 튄다. 버튼을 눌러서 시작된
        /// 흐름일 때만 참이다.
        private var awaitingAuthorization = false

        /// 남한 전체가 들어오는 범위. 제주까지 담고 울릉도·독도는 뺐다 — 그것까지
        /// 넣으면 동해가 화면의 절반을 차지해 정작 촬영지가 몰린 서남부가 작아진다.
        static let korea = NMGLatLngBounds(
            southWest: NMGLatLng(lat: 33.0, lng: 125.8),
            northEast: NMGLatLng(lat: 38.7, lng: 129.8)
        )
        private var sheetHeight: CGFloat = 0
        private weak var mapView: NMFMapView?

        init(
            onTapPin: @escaping (PlaceSummary) -> Void,
            onLocateFailure: @escaping (LocateOutcome) -> Void
        ) {
            self.onTapPin = onTapPin
            self.onLocateFailure = onLocateFailure
            super.init()
            locationManager.delegate = self
            // 촬영지를 찾아 주는 앱이지 내비게이션이 아니다. 미터 단위 정확도는
            // 필요 없고, 낮은 정확도가 **훨씬 빨리·배터리를 덜 쓰고** 온다.
            locationManager.desiredAccuracy = kCLLocationAccuracyHundredMeters
        }

        func attach(to mapView: NMFMapView) {
            self.mapView = mapView
        }

        // swiftlint:disable:next function_parameter_count
        func render(
            pins: [PlaceSummary],
            numbered: Bool,
            koreaToken: Int,
            locateToken: Int,
            fitToken: Int,
            focusToken: Int,
            focus: PlaceSummary?,
            panToken: Int,
            pan: PlaceSummary?,
            sheetHeight: CGFloat,
            on mapView: NMFMapView
        ) {
            self.sheetHeight = sheetHeight
            // **매 갱신마다 부른다.** 전에는 카메라가 움직일 때만 불러서, 시트를
            // 끌어 올려도 로고·축척이 제자리에 있다가 탭을 눌러야 뒤늦게 따라왔다.
            applyInset(on: mapView)

            // 번호 표시 여부가 바뀌면 아이콘을 다시 그려야 하므로 키에 함께 넣는다.
            let key = pins.map { String($0.id) }.joined(separator: ",") + (numbered ? "#n" : "#p")
            if key != lastPinKey {
                lastPinKey = key
                markers.forEach { $0.mapView = nil }
                // 번호는 배열 순서다 — 목록도 같은 배열을 같은 순서로 그리므로
                // "목록의 3번 = 지도의 3번" 이 성립한다.
                let spread = Self.spread(pins)
                markers = pins.enumerated().map { index, place in
                    let marker = NMFMarker(position: spread[index])
                    marker.iconImage = PinImage.numbered(numbered ? index + 1 : nil)
                    marker.captionText = place.name
                    marker.captionMinZoom = 13
                    marker.touchHandler = { [weak self] _ in
                        self?.onTapPin(place)
                        return true
                    }
                    marker.mapView = mapView
                    return marker
                }
            }

            // 카메라는 토큰이 바뀐 순간에만 움직인다 — 칩은 어느 토큰도 올리지 않는다.
            if koreaToken != lastKoreaToken {
                lastKoreaToken = koreaToken
                fit(bounds: Self.korea, on: mapView)
            }
            if locateToken != lastLocateToken {
                lastLocateToken = locateToken
                locate(on: mapView)
            }
            if fitToken != lastFitToken {
                lastFitToken = fitToken
                fit(pins, on: mapView)
            }
            if focusToken != lastFocusToken {
                lastFocusToken = focusToken
                if let focus {
                    zoom(to: focus, on: mapView)
                }
            }
            if panToken != lastPanToken {
                lastPanToken = panToken
                if let pan {
                    center(on: pan, in: mapView)
                }
            }
        }

        /// 검색바·시트가 덮는 영역을 빼서, 카메라 이동이 **보이는 지도 영역** 기준이
        /// 되게 한다. 이동 직전에만 부른다.
        /// 시트가 덮는 만큼 지도의 "내용 영역" 을 줄인다. 카메라가 그 영역 기준으로
        /// 움직여야 핀이 시트 뒤에 숨지 않는다.
        ///
        /// **로고와 축척은 그 여백을 따라가지 않게 되민다.** 둘은 내용 영역의 바닥에
        /// 붙는데, 여백이 화면의 48% 면 로고가 지도 한가운데로 올라와 시야를 막는다
        /// (실측). 여백만큼 음수 마진을 주어 **뷰의 진짜 바닥**에 붙여 둔다.
        ///
        /// 로고는 지우지 않는다 — 네이버 지도 SDK 에 끄는 API 가 없고, 이용약관이
        /// 노출을 의무로 걸어 두었다. 위치와 여백만 우리가 정할 수 있다.
        private func applyInset(on mapView: NMFMapView) {
            // 카메라가 쓰는 여백은 화면의 절반까지만 잡는다. 시트를 최대로 올리면
            // 지도가 어차피 안 보이는데 그 높이로 계산하면 카메라가 튄다.
            let cameraBottom = min(sheetHeight, mapView.bounds.height * 0.48)
            let inset = UIEdgeInsets(top: 108, left: 0, bottom: cameraBottom, right: 0)
            if mapView.contentInset != inset {
                mapView.contentInset = inset
            }

            // 로고와 축척은 **시트 바로 위**에 붙인다.
            //
            // 둘은 contentInset 의 바닥을 기준으로 배치되는데, 그 값은 카메라용이라
            // 시트의 실제 높이와 다르다. 그대로 두면 시트를 내려도 로고가 지도
            // 한가운데 남아 시야를 막는다(실측).
            //
            // 로고는 지우지 않는다 — SDK 에 끄는 API 가 없고 이용약관이 노출을
            // 의무로 걸어 두었다. 우리가 정할 수 있는 것은 위치와 여백뿐이다.
            let margin = UIEdgeInsets(
                top: 0,
                left: 4,
                bottom: max(0, sheetHeight - cameraBottom) + 6,
                right: 4
            )
            if mapView.logoMargin != margin {
                mapView.logoMargin = margin
            }
        }

        /// 선택한 장소 하나로 확대한다.
        private func zoom(to place: PlaceSummary, on mapView: NMFMapView) {
            applyInset(on: mapView)
            let update = NMFCameraUpdate(
                scrollTo: NMGLatLng(lat: place.latitude, lng: place.longitude),
                zoomTo: 16
            )
            update.animation = .easeIn
            update.animationDuration = 0.5
            mapView.moveCamera(update)
        }

        /// 담은 장소가 가운데 오도록 **이동만** 한다 — 확대는 장소를 열 때만 한다.
        private func center(on place: PlaceSummary, in mapView: NMFMapView) {
            applyInset(on: mapView)
            let update = NMFCameraUpdate(
                scrollTo: NMGLatLng(lat: place.latitude, lng: place.longitude)
            )
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }

        /// 좌표가 완전히 같은 핀들을 **아주 조금씩 벌린다.**
        ///
        /// 한 건물 안에 서로 다른 장소가 있으면(더현대서울 안의 카페·음식점, IFC 의
        /// 호텔과 몰) 좌표가 같게 들어온다. 그대로 그리면 핀이 완전히 포개져
        /// **맨 위 하나만 눌리고 나머지는 있는 줄도 모른다**(실측 5쌍).
        ///
        /// 묶어서 개수를 표시하는 방법도 있으나 "목록 3번 = 지도 3번" 규칙이 깨진다.
        /// 겹치는 것이 155 곳 중 5쌍뿐이라 장치를 새로 만들 만한 양도 아니다.
        ///
        /// 벌리는 방향은 위쪽부터 시계 방향, 간격은 약 8m 다. 실제 위치를 크게
        /// 벗어나지 않으면서 확대했을 때 손가락으로 고를 수 있는 정도다.
        private static func spread(_ pins: [PlaceSummary]) -> [NMGLatLng] {
            var seen: [String: [Int]] = [:]
            for (index, place) in pins.enumerated() {
                let key = "\(place.latitude),\(place.longitude)"
                seen[key, default: []].append(index)
            }

            var result = pins.map { NMGLatLng(lat: $0.latitude, lng: $0.longitude) }
            for group in seen.values where group.count > 1 {
                // 위도 1도 ≈ 111km. 8m 를 도 단위로 바꾼 값이다.
                let radius = 8.0 / 111_000.0
                for (order, index) in group.enumerated() {
                    let angle = 2 * Double.pi * Double(order) / Double(group.count)
                    let place = pins[index]
                    // 경도는 위도가 높을수록 같은 각도의 실제 거리가 짧아진다 —
                    // 나누지 않으면 한국 위도에서 가로로 더 벌어져 보인다.
                    let lngScale = cos(place.latitude * Double.pi / 180)
                    result[index] = NMGLatLng(
                        lat: place.latitude + radius * cos(angle),
                        lng: place.longitude + radius * sin(angle) / lngScale
                    )
                }
            }
            return result
        }

        /// 내 위치로 옮기고 파란 점을 켠다.
        ///
        /// 권한을 아직 묻지 않았으면 먼저 묻는다. 거부한 상태면 아무 일도 하지
        /// 않는다 — 설정으로 보내는 안내는 이 버튼의 몫이 아니다.
        ///
        /// `NMFMyPositionMode.direction` 이 아니라 `.normal` 을 쓴다. 나침반을 따라
        /// 지도가 회전하면 핀 번호와 목록을 대조하기 어려워진다.
        /// 「내 위치」 버튼. 권한을 확인하고 **좌표를 한 번 받아 카메라를 옮긴다.**
        ///
        /// 예전에는 `positionMode = .normal` 만 세웠는데, SDK 문서가 그 모드를
        /// *"위치는 추적하지만 지도는 움직이지 않는 모드"* 로 정의한다 — 파란 점만
        /// 생기고 카메라는 제자리라, 남한 전체를 보고 있으면 누른 사람 눈에는
        /// **아무 일도 안 일어난 것**으로 보였다 (MZ2AZ-252).
        ///
        /// 그렇다고 `.direction` 으로 바꾸면 안 된다 — 그건 카메라가 계속 따라다녀
        /// 사용자가 지도를 못 민다. 지도 앱들이 하는 대로 **한 번만 날아가고**,
        /// 파란 점은 `.normal` 로 계속 따라다니게 둔다.
        private func locate(on mapView: NMFMapView) {
            // 파란 점은 권한 여부와 무관하게 미리 켜 둔다. 권한이 없으면 SDK 가
            // 알아서 아무것도 그리지 않는다.
            mapView.positionMode = .normal

            switch locationManager.authorizationStatus {
            case .notDetermined:
                // 물어보고 끝낸다. 대답은 델리게이트로 온다.
                awaitingAuthorization = true
                locationManager.requestWhenInUseAuthorization()
            case .restricted, .denied:
                onLocateFailure(.denied)
            default:
                locationManager.requestLocation()
            }
        }

        /// 권한 대답이 왔다. **버튼으로 시작된 흐름일 때만** 이어서 좌표를 받는다.
        func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
            guard awaitingAuthorization else { return }
            switch manager.authorizationStatus {
            case .notDetermined:
                return // 아직 사용자가 고르는 중이다.
            case .restricted, .denied:
                awaitingAuthorization = false
                onLocateFailure(.denied)
            default:
                awaitingAuthorization = false
                manager.requestLocation()
            }
        }

        func locationManager(_: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
            guard let coordinate = locations.last?.coordinate, let mapView else { return }
            applyInset(on: mapView)
            let update = NMFCameraUpdate(
                scrollTo: NMGLatLng(lat: coordinate.latitude, lng: coordinate.longitude),
                // 장소 하나를 열 때(16)보다 한 단계 넓게 잡는다. 「내 위치」 는 한 점을
                // 보려는 것이 아니라 **주변에 뭐가 있나**를 보려는 동작이다.
                zoomTo: 15
            )
            update.animation = .easeIn
            update.animationDuration = 0.5
            mapView.moveCamera(update)
        }

        func locationManager(_: CLLocationManager, didFailWithError error: Error) {
            // 권한 거부는 여기로도 온다. 이미 알린 것을 두 번 띄우지 않는다.
            if let clError = error as? CLError, clError.code == .denied {
                onLocateFailure(.denied)
                return
            }
            onLocateFailure(.failed)
        }

        /// 주어진 범위가 다 보이게 맞춘다. 시트가 덮는 만큼은 빼고 계산한다.
        private func fit(bounds: NMGLatLngBounds, on mapView: NMFMapView) {
            applyInset(on: mapView)
            let update = NMFCameraUpdate(fit: bounds, padding: 24)
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }

        private func fit(_ pins: [PlaceSummary], on mapView: NMFMapView) {
            guard !pins.isEmpty else { return }
            if pins.count == 1, let only = pins.first {
                zoom(to: only, on: mapView)
                return
            }
            applyInset(on: mapView)
            let lats = pins.map(\.latitude)
            let lngs = pins.map(\.longitude)
            let bounds = NMGLatLngBounds(
                southWest: NMGLatLng(lat: lats.min()!, lng: lngs.min()!),
                northEast: NMGLatLng(lat: lats.max()!, lng: lngs.max()!)
            )
            let update = NMFCameraUpdate(fit: bounds, padding: 32)
            update.animation = .easeIn
            update.animationDuration = 0.4
            mapView.moveCamera(update)
        }
    }
}

/// 번호가 박힌 핀 이미지.
///
/// SDK 기본 마커에 틴트를 입히면 밑그림(초록 핀)과 섞여 탁해진다 — 그래서 핀을
/// 직접 그린다. 단색 빨강은 "옛날 앱" 처럼 보인다는 피드백으로 파스텔
/// 하늘→보라 그라데이션에 그림자를 깔고, 번호는 머리의 흰 원 배지 안에 컬러로
/// 넣는다. 같은 번호는 캐시로 재사용한다: 계획서 §3-5 가 "타이핑마다 수십 개를
/// 다시 굽으면 버벅인다" 고 적은 그 비용을 피하는 장치다.
enum PinImage {
    /// 번호 없는 핀은 -1 로 담는다 — 번호가 유일한 차이라 캐시를 나눌 이유가 없다.
    private static var cache: [Int: NMFOverlayImage] = [:]

    /// 그라데이션 양끝 색. 목록의 번호 배지도 같은 색을 써서 핀과 짝이 맞는다.
    /// deep 은 흰 배지 위 번호에도 쓰이므로 파스텔이어도 이쪽은 살짝 진하게 둔다.
    static let deep = UIColor(red: 0.48, green: 0.41, blue: 0.93, alpha: 1) // 보라
    static let light = UIColor(red: 0.56, green: 0.80, blue: 0.97, alpha: 1) // 하늘

    /// `number` 가 nil 이면 흰 배지와 숫자 없이 **민 핀**을 그린다.
    ///
    /// 번호는 "목록의 N번 = 지도의 N번" 을 잇기 위한 것이다. 첫 화면의 인기 10곳은
    /// 그렇게 이어 볼 목록이 아니라 순위표라, 지도에까지 번호를 찍으면 무엇과
    /// 짝지으라는 것인지 알 수 없다.
    static func numbered(_ number: Int?) -> NMFOverlayImage {
        let key = number ?? -1
        if let cached = cache[key] {
            return cached
        }
        let size = CGSize(width: 38, height: 50)
        let head = CGPoint(x: 19, y: 17)
        let tip = CGPoint(x: 19, y: 45)
        let image = UIGraphicsImageRenderer(size: size).image { ctx in
            let cgContext = ctx.cgContext

            // 머리 원의 아래쪽 좌우(135°→45°, 시계방향으로 위를 지나)에서
            // 꼬리 끝점으로 이어 물방울 모양을 만든다.
            let path = UIBezierPath(
                arcCenter: head,
                radius: 14,
                startAngle: .pi * 0.75,
                endAngle: .pi * 0.25,
                clockwise: true
            )
            path.addLine(to: tip)
            path.close()

            // 바닥에 살짝 뜬 그림자 — 지도 위에 얹힌 입체감을 만든다.
            cgContext.saveGState()
            cgContext.setShadow(
                offset: CGSize(width: 0, height: 2),
                blur: 3.5,
                color: UIColor.black.withAlphaComponent(0.28).cgColor
            )
            deep.setFill()
            path.fill()
            cgContext.restoreGState()

            // 세로 그라데이션.
            cgContext.saveGState()
            path.addClip()
            let gradient = CGGradient(
                colorsSpace: CGColorSpaceCreateDeviceRGB(),
                colors: [light.cgColor, deep.cgColor] as CFArray,
                locations: [0, 1]
            )!
            cgContext.drawLinearGradient(
                gradient,
                start: CGPoint(x: head.x, y: 0),
                end: CGPoint(x: head.x, y: tip.y),
                options: []
            )
            cgContext.restoreGState()

            UIColor.white.setStroke()
            path.lineWidth = 1.5
            path.stroke()

            // 흰 원 배지에 컬러 번호 — 흰 바탕이라 작은 크기에서도 또렷하다.
            guard let number else { return }

            UIColor.white.setFill()
            UIBezierPath(
                arcCenter: head, radius: 10,
                startAngle: 0, endAngle: .pi * 2, clockwise: true
            ).fill()

            let text = "\(number)"
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: number < 100 ? 11.5 : 9, weight: .heavy),
                .foregroundColor: deep,
            ]
            let textSize = text.size(withAttributes: attributes)
            text.draw(
                at: CGPoint(x: head.x - textSize.width / 2, y: head.y - textSize.height / 2),
                withAttributes: attributes
            )
        }
        let overlay = NMFOverlayImage(image: image)
        cache[key] = overlay
        return overlay
    }
}
