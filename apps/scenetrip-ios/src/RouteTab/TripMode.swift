import Foundation

/// 여행 모드의 상수와 판정 (계획 `docs/project/plans/trip-mode.md` §2).
///
/// 「코스 시작」 뒤 앱이 흐름을 잇는다 — 다음 성지로 길찾기, 반경 안에 **머무르면** 도착,
/// 스탬프, 그다음 성지. 지나가기만 한 것과 들른 것을 가르는 것이 머무름이다.
enum TripMode {
    /// 도착으로 보는 반경(m). 촬영지 좌표가 건물 한가운데가 아닐 수 있어 넉넉하다.
    static let arriveRadiusMeters = 100.0

    /// 반경 안에 이만큼 머무르면 도착이다. 기본 5분(사용자 제안).
    ///
    /// 확인용 뒷문 — `simctl launch … -tripDwellSeconds 10` 으로 줄인다. 시뮬레이터에서
    /// 5분을 기다릴 수 없어서다. 인자가 없으면 5분.
    static var dwell: TimeInterval {
        let raw = UserDefaults.standard.double(forKey: "tripDwellSeconds")
        return raw > 0 ? raw : 300
    }
}

/// 한국 안인가 — 네모로 판단한다. 국경선을 정확히 그릴 이유가 없다. 코스 플래너의
/// 「가까운 덩어리 고르기」와 발자취의 「한국 안에서만 기록」이 같이 쓴다.
enum KoreaBounds {
    static func contains(latitude: Double, longitude: Double) -> Bool {
        (32.5 ... 39.5).contains(latitude) && (124.0 ... 132.5).contains(longitude)
    }
}

/// 머무름 판정 — 반경 안에 **연속으로** 있었던 시간을 센다.
///
/// 반경 밖으로 나가면 처음부터 다시 센다. GPS 가 한 번 튀어 밖으로 나갔다 들어오면
/// 다시 세는 것이 맞다 — 그것이 「머무름」의 뜻이다. 상태는 값 타입이라 목적지가
/// 바뀔 때 새로 만들면 된다.
struct TripArrival {
    private(set) var enteredAt: Date?

    /// 새 위치를 넣는다. 머무름을 채웠으면 true — 한 번 true 를 낸 뒤 어떻게 할지는
    /// 부르는 쪽이 정한다(스탬프를 찍고 다음 목적지로).
    mutating func observe(distanceMeters: Double, now: Date = Date()) -> Bool {
        guard distanceMeters <= TripMode.arriveRadiusMeters else {
            enteredAt = nil
            return false
        }
        let since = enteredAt ?? now
        enteredAt = since
        return now.timeIntervalSince(since) >= TripMode.dwell
    }

    /// 반경 안에 있은 시간(초). 화면이 「도착까지 N분」을 보여 주는 데 쓴다.
    func dwelt(now: Date = Date()) -> TimeInterval? {
        enteredAt.map { now.timeIntervalSince($0) }
    }
}
