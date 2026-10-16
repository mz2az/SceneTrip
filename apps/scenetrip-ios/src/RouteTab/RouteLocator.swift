import CoreLocation
import Foundation
import SceneApiClient

/// 지금 어디에 있나 — **한 번만** 물어보는 것.
///
/// 검색 탭의 「내 위치」 버튼(`NaverMapView.Coordinator`)이 같은 일을 하지만 그것은
/// 지도 코디네이터 안에 묶여 있어 화면에서 좌표를 꺼내 쓸 수가 없다. 길찾기는
/// **좌표 자체가 필요하다** — 「현재 위치 → 다음 목적지」를 서버에 물으려면 출발지를
/// 실어 보내야 하기 때문이다. 그래서 값으로 다루는 쪽을 따로 만든다.
///
/// 두 모드다. `start()` 는 **잠깐 받아 가장 나은 값을 고르고 끊는다** — 길찾기를 누르는 순간의 위치.
/// 처음 값을 바로 내놓고, 더 정확한 값이 오면 갈아 끼운다. 20 m 안쪽이 오거나 3초가 지나면 끊는다.
/// `track()` 은 여행 모드(2026-09-02)용으로 **계속 받는다** — 반경 안 머무름으로 도착을
/// 판정하고 발자취를 남기려면 위치가 이어져야 한다. 10 m 움직일 때마다 한 번이고,
/// 화면이 닫히면 `stop()` 으로 끊는다(앱 사용 중 권한이라 뒤로 가면 어차피 멈춘다).
///
/// 정확도는 **최고**로 잡는다. 앞서는 100 m 로 낮춰 한 번만 받았는데, 도심에서 첫 값이 몇백 m
/// 어긋나 서면역이 전포로 떴다(2026-10-16 사용자 실기 지적). 고르는 규칙은 `LocationFix`.
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
        manager.desiredAccuracy = kCLLocationAccuracyBest
    }

    /// 계속 받는 중인가. 권한 대답이 늦게 오면 그때 `track` 을 이어 가기 위한 표시.
    private var tracking = false
    /// 지금 내놓은 값의 원본(정확도·시각).
    private var shown: CLLocation?
    /// 한 번 받기를 끊을 때를 세는 차례 — 새로 `start` 하면 앞의 끊기 예약은 무시된다.
    private var oneShotTicket = 0

    /// 받은 자리를 장소 모양으로. 거리 계산(`RouteGeometry`)이 `PlaceSummary` 를 받아서다.
    var found: PlaceSummary? {
        guard case let .found(latitude, longitude) = state else { return nil }
        return PlaceSummary(id: 0, name: "여기", latitude: latitude, longitude: longitude)
    }

    /// 화면이 뜰 때 부른다. 권한이 없으면 물어보고, 대답은 델리게이트로 온다.
    func start() {
        tracking = false
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .restricted, .denied:
            state = .denied
        default:
            beginOneShot()
        }
    }

    /// 잠깐 받는다. 값이 하나라도 오면 3초 뒤 끊고, 그 전에 충분히 정확한 값이 오면 바로 끊는다.
    private func beginOneShot() {
        shown = nil
        oneShotTicket &+= 1
        let ticket = oneShotTicket
        LocationFix.requestFullAccuracyIfReduced(manager)
        manager.distanceFilter = kCLDistanceFilterNone
        manager.startUpdatingLocation()
        DispatchQueue.main.asyncAfter(deadline: .now() + LocationFix.oneShotWait) { [weak self] in
            guard let self, !self.tracking, oneShotTicket == ticket, shown != nil else { return }
            manager.stopUpdatingLocation()
        }
    }

    /// 여행 모드 — 위치를 계속 받는다. `stop()` 으로 끊는다.
    func track() {
        tracking = true
        shown = nil
        manager.distanceFilter = 10
        LocationFix.requestFullAccuracyIfReduced(manager)
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
        case .restricted, .denied:
            state = .denied
        default:
            manager.startUpdatingLocation()
        }
    }

    func stop() {
        tracking = false
        manager.stopUpdatingLocation()
    }

    /// 가상 위치(데모 주행)를 흘려 넣는다 — 화면은 진짜 위치와 똑같이 받는다.
    /// 실행 인자 없이는 불리지 않는다(`DemoDrive.isOn`).
    func inject(latitude: Double, longitude: Double) {
        state = .found(latitude: latitude, longitude: longitude)
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
                if self.tracking {
                    manager.startUpdatingLocation()
                } else {
                    self.beginOneShot()
                }
            }
        }
    }

    nonisolated func locationManager(
        _: CLLocationManager,
        didUpdateLocations locations: [CLLocation]
    ) {
        Task { @MainActor in
            for fix in locations where LocationFix.isUsable(fix) {
                if self.tracking {
                    // 여행 중엔 새 값이 곧 지금 자리다. 다만 이미 자리를 알면 확 흐린 값은 버린다.
                    guard LocationFix.acceptsTripFix(fix, hasFix: self.shown != nil) else { continue }
                } else {
                    guard LocationFix.shouldReplace(self.shown, with: fix) else { continue }
                }
                self.shown = fix
                self.state = .found(latitude: fix.coordinate.latitude, longitude: fix.coordinate.longitude)
            }
            if !self.tracking, let shown = self.shown, shown.horizontalAccuracy <= LocationFix.goodEnough {
                self.manager.stopUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_: CLLocationManager, didFailWithError error: Error) {
        let code = (error as? CLError)?.code
        Task { @MainActor in
            if code == .denied {
                self.state = .denied
                return
            }
            // 계속 받는 중의 「아직 모름」은 잠깐이다. 이미 자리를 알면 그 자리를 지킨다.
            guard code != .locationUnknown, self.shown == nil else { return }
            self.state = .failed
        }
    }
}
