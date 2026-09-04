import Combine
import CoreLocation
import Foundation
import SceneApiClient
import SwiftUI

/// 지도 위의 한 자리. 튜플은 `Equatable` 이 아니라 `onChange` 에 못 넣는다.
struct TripSpot: Equatable {
    let latitude: Double
    let longitude: Double
}

/// 여행 중 안내 — **편집 화면 안에서 돈다** (계획 trip-mode.md §8, 2026-09-03).
///
/// 1단계는 별도 길찾기 창(`RouteNavView`)이 이 일을 했다. 이제 편집 화면의 지도가 곧
/// 여행 지도라, 상태를 화면 밖 객체로 뺐다 — 편집 화면은 이미 상태가 많고 타입 길이
/// 한도에 닿아 있다. 여기는 **목적지 하나**를 들고 경로를 받고, 머무름으로 도착을
/// 재고, 스탬프를 알린다.
///
/// ## 도착해도 다음으로 넘기지 않는다
///
/// 1단계는 스탬프 연출이 끝나면 곧장 다음 성지로 안내를 바꿨다. 그러면 1번은 **거쳐
/// 가는 경유지**가 된다 — 성지에 왔으면 둘러보고 즐기는 시간이 있고, 얼마나 머물지는
/// 앱이 모른다. 그래서 도착 뒤에는 `.arrived` 로 서서 기다리고, **사람이 「다음으로」를
/// 눌러야** `start(to:)` 가 다시 불린다.
@MainActor
final class TripSession: ObservableObject {
    enum Phase: Equatable {
        /// 안내 중이 아니다.
        case idle
        /// 목적지로 가는 중 — 경로가 지도에 있다.
        case guiding
        /// 도착했다(스탬프 찍힘). 다음은 사람이 고른다.
        case arrived
    }

    @Published private(set) var target: RouteStop?
    @Published private(set) var phase: Phase = .idle

    /// 카카오가 준 안내. 아직 안 왔으면 nil.
    @Published private(set) var result: RouteNavResult?
    @Published private(set) var routeError: String?
    @Published private(set) var asking = false

    /// 도착 스탬프 연출이 떠 있는가.
    @Published var stamped = false

    /// 지금 위치. 못 받았으면 nil — **지어내 찍지 않는다.**
    @Published private(set) var here: TripSpot?

    /// 5초 박자 — 머무름 재판정과 안내 띠의 「N분」 갱신용. 값 자체는 뜻이 없다.
    @Published private(set) var dwellTick = 0

    /// 카메라를 「나와 목적지」로 되돌리라는 신호. 값이 바뀔 때 지도가 움직인다.
    @Published private(set) var recenterTick = 0

    /// 목적지 번호(1부터). 데모 주행이 「N번까지」를 세는 데 쓴다.
    private(set) var targetNumber = 0

    /// 스탬프가 찍혔다 — 부르는 쪽이 코스 상태와 서버에 「다녀옴」을 남긴다.
    var onArrived: ((RouteStop) -> Void)?

    let locator = RouteLocator()
    private var tripArrival = TripArrival()
    private var subscription: AnyCancellable?
    private var tasks: [Task<Void, Never>] = []

    /// 데모 주행의 가상 위치와 경로선 위 진행 꼭짓점(`DemoDrive`).
    private var demoPosition: DemoDrive.Point?
    private var demoPathIndex = 0

    var isActive: Bool {
        target != nil
    }

    /// 이 성지로 안내를 시작한다(다시 시작해도 된다 — 도착 뒤 「다음으로」가 이것을 부른다).
    func start(to stop: RouteStop, number: Int) {
        target = stop
        targetNumber = number
        phase = .guiding
        result = nil
        routeError = nil
        stamped = false
        tripArrival = TripArrival()
        demoPathIndex = 0
        recenterTick += 1
        beginTracking()
        if let here {
            Task { await load(from: here) }
        }
    }

    /// 안내를 끝낸다. 위치 받기도 멈춘다 — 여행 종료·화면 닫기.
    func end() {
        target = nil
        phase = .idle
        result = nil
        routeError = nil
        stamped = false
        stopTracking()
    }

    /// 「여기 도착함」 — 머무름을 기다리지 않고 지금 찍는다. 머무를 시간이 없거나 GPS 가
    /// 튈 때의 탈출구다. 저절로 찍힐 때도 같은 길을 지난다.
    func arriveNow() {
        guard phase == .guiding, let target else { return }
        phase = .arrived
        result = nil // 안내가 끝났다 — 경로선을 지운다. 다음은 사람이 고른다.
        routeError = nil
        onArrived?(target)
        withAnimation { stamped = true }
    }

    /// 스탬프 연출이 끝났다. `.arrived` 그대로 선다.
    func stampDone() {
        stamped = false
    }

    /// 「현재위치로」 — 카메라를 나와 목적지로 되돌린다.
    func recenter() {
        recenterTick += 1
    }

    // MARK: 위치

    private func beginTracking() {
        guard subscription == nil else { return }
        subscription = locator.$state.sink { [weak self] state in
            guard case let .found(latitude, longitude) = state else { return }
            self?.observe(TripSpot(latitude: latitude, longitude: longitude))
        }
        if DemoDrive.isOn, let target {
            // 데모 주행 — 진짜 위치 대신 가상 위치. 첫 성지 남쪽 250 m 에서 걸어온다.
            // 명시해서 켰으면(영상) 남쪽 250 m 에서, 시뮬레이터 기본이면 마지막 자리에서 이어 걷는다.
            let start = demoPosition
                ?? (DemoDrive.isExplicit ? nil : DemoDrive.lastPosition)
                ?? DemoDrive.start(near: target)
            demoPosition = start
            locator.inject(latitude: start.latitude, longitude: start.longitude)
            tasks.append(Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(DemoDrive.tick))
                    self?.demoStep()
                }
            })
        } else {
            locator.track()
        }
        // **가만히 서 있으면 위치 업데이트가 안 온다**(25 m 이동 필터). 반경 안에 5분을
        // 있어도 판정이 다시 돌지 않아 스탬프가 영영 안 찍힌다 — 5초마다 마지막 위치로
        // 머무름을 다시 센다.
        tasks.append(Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(5))
                guard let self else { return }
                dwellTick += 1
                if let here {
                    autoStampIfArrived(here)
                }
            }
        })
    }

    private func stopTracking() {
        subscription = nil
        tasks.forEach { $0.cancel() }
        tasks = []
        locator.stop()
    }

    private func observe(_ spot: TripSpot) {
        here = spot
        FootprintStore.shared.record(latitude: spot.latitude, longitude: spot.longitude)
        if phase == .guiding, result == nil, !asking {
            Task { await load(from: spot) }
        }
        autoStampIfArrived(spot)
    }

    /// 목적지 반경 안에 **머무르면** 저절로 스탬프가 찍힌다. 지나가기만 한 것은 도착이 아니다.
    private func autoStampIfArrived(_ spot: TripSpot) {
        guard phase == .guiding, let target else { return }
        let hereSpot = PlaceSummary(id: 0, name: "여기", latitude: spot.latitude, longitude: spot.longitude)
        let meters = RouteGeometry.kilometers(hereSpot, target.place) * 1000
        if tripArrival.observe(distanceMeters: meters) {
            arriveNow()
        }
    }

    // MARK: 경로

    private func load(from spot: TripSpot) async {
        guard let target, result == nil, !asking else { return }
        asking = true
        defer { asking = false }
        do {
            result = try await KakaoTransit.leg(
                from: CLLocationCoordinate2D(latitude: spot.latitude, longitude: spot.longitude),
                to: CLLocationCoordinate2D(latitude: target.place.latitude, longitude: target.place.longitude),
                destinationName: target.place.name
            )
            routeError = nil
            recenterTick += 1 // 경로가 왔다 — 카메라를 경로 전체로
        } catch KakaoTransit.Failure.noKey {
            routeError = "길찾기 키가 없어 안내를 받을 수 없습니다"
        } catch KakaoTransit.Failure.noRoute {
            routeError = "대중교통으로 갈 수 있는 길을 찾지 못했습니다"
        } catch KakaoTransit.Failure.intercity {
            routeError = "시외 구간이에요 — 기차·시외버스 안내는 준비 중입니다"
        } catch {
            routeError = "길찾기 안내를 받지 못했습니다"
        }
    }

    /// 안내 띠의 힌트 — 반경 안이면 「머무르면 도착 · N분」, 아니면 규칙 한 줄.
    var dwellHint: String {
        _ = dwellTick // 5초마다 다시 계산되게 묶어 둔다.
        if let dwelt = tripArrival.dwelt() {
            let left = max(0, Int(((TripMode.dwell - dwelt) / 60).rounded(.up)))
            return left == 0 ? "도착 확인 중" : "머무르면 도착 · \(left)분"
        }
        return "반경 \(Int(TripMode.arriveRadiusMeters)) m 에 머무르면 스탬프"
    }

    // MARK: 데모 주행

    /// 한 걸음(0.4초마다). 경로선을 따라 움직이고 반경 30 m 안에 들면 서서 머무름을 기다린다.
    /// `-demoDrive N` 번 성지를 지나면 멈춘다. 도착 판정·스탬프는 실제 규칙이 한다.
    private func demoStep() {
        guard DemoDrive.isOn, phase == .guiding, !stamped, let target,
              targetNumber <= DemoDrive.untilStop
        else { return }
        let goal: DemoDrive.Point = (target.place.latitude, target.place.longitude)
        var position = demoPosition ?? here.map { ($0.latitude, $0.longitude) } ?? DemoDrive.start(near: target)
        if DemoDrive.meters(position, goal) > DemoDrive.stopWithinMeters {
            // 경로선을 구간별로 펴고, 지금 지나는 꼭짓점이 어느 구간인지로 속도를 정한다
            // (도보 24 m/s · 대중교통 네 배).
            var path: [DemoDrive.Point] = []
            var modes: [RouteLegMode] = []
            for leg in result?.legs ?? [] {
                for pair in leg.path where pair.count >= 2 {
                    path.append((pair[1], pair[0])) // [경도, 위도] 순으로 온다
                    modes.append(leg.mode)
                }
            }
            let mode = modes.indices.contains(demoPathIndex) ? modes[demoPathIndex] : .walk
            position = DemoDrive.step(
                from: position, along: path, index: &demoPathIndex,
                toward: goal, meters: DemoDrive.speed(for: mode) * DemoDrive.tick
            )
            demoPosition = position
            DemoDrive.remember(position)
        }
        // 서 있을 때도 같은 자리를 다시 넣는다 — 머무름 판정과 파문이 이어진다.
        locator.inject(latitude: position.latitude, longitude: position.longitude)
    }
}
