import CoreLocation
import Foundation

/// 지금 어디에 있나 — **한 번만** 물어보는 것.
///
/// 검색 탭의 「내 위치」 버튼(`NaverMapView.Coordinator`)이 같은 일을 하지만 그것은
/// 지도 코디네이터 안에 묶여 있어 화면에서 좌표를 꺼내 쓸 수가 없다. 길찾기는
/// **좌표 자체가 필요하다** — 「현재 위치 → 다음 목적지」를 서버에 물으려면 출발지를
/// 실어 보내야 하기 때문이다. 그래서 값으로 다루는 쪽을 따로 만든다.
///
/// 계속 추적하지 않고 **한 건 받고 끊는다.** 길찾기는 누르는 순간의 위치만 있으면
/// 되고, 켜 둔 채로 두면 배터리를 먹는다.
///
/// 정확도는 100 m 로 잡는다. 촬영지를 이어 주는 앱이지 내비게이션이 아니라 미터
/// 단위가 필요 없고, 낮은 정확도가 훨씬 빨리 온다 — 사용자가 「길찾기」를 누르고
/// 기다리는 시간이 그만큼 짧아진다.
@MainActor
final class RouteLocator: NSObject, ObservableObject, CLLocationManagerDelegate {
    enum State: Equatable {
        /// 아직 물어보지 않았거나 대답을 기다리는 중.
        case asking
        /// 좌표를 받았다.
        case found(latitude: Double, longitude: Double)
        /// 권한이 거부·제한됐다. 앱 안에서는 풀 수 없다.
        case denied
        /// 권한은 있는데 좌표를 못 얻었다. 실내·기내 모드에서 실제로 난다.
        case failed
    }

    @Published private(set) var state: State = .asking

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// 화면이 뜰 때 부른다. 권한이 없으면 물어보고, 대답은 델리게이트로 온다.
    func start() {
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .restricted, .denied:
            state = .denied
        default:
            manager.requestLocation()
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            switch status {
            case .notDetermined:
                return // 아직 사용자가 고르는 중이다.
            case .restricted, .denied:
                self.state = .denied
            default:
                manager.requestLocation()
            }
        }
    }

    nonisolated func locationManager(
        _: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        guard let point = locations.last?.coordinate else { return }
        Task { @MainActor in
            self.state = .found(latitude: point.latitude, longitude: point.longitude)
        }
    }

    nonisolated func locationManager(_: CLLocationManager, didFailWithError error: Error) {
        let denied = (error as? CLError)?.code == .denied
        Task { @MainActor in
            self.state = denied ? .denied : .failed
        }
    }
}
