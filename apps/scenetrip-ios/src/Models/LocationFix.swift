import CoreLocation

/// 받은 위치 중 **무엇을 내 자리로 쓸지** 정하는 규칙.
///
/// 앞서는 정확도를 100 m 로 낮춰 **한 번만** 받았다(`requestLocation`). 도심에서 처음 오는 값은
/// 와이파이·기지국으로 잡은 대략값이라 몇백 m 씩 어긋나고, 그 값에 그대로 멈춰 있었다 — 서면역에서
/// 전포로 떴다(2026-10-16 사용자 실기 지적). 네이버 지도처럼 **최고 정확도로 계속 받고**, 더 나은
/// 값이 오면 갈아탄다. 화면 쪽은 이 규칙만 부르고 판단을 따로 들지 않는다.
enum LocationFix {
    /// 이보다 오래된 값은 버린다 — 앱을 켜자마자 오는 캐시 값이 지난 자리일 수 있다.
    static let maxAge: TimeInterval = 10
    /// 이 정도면 더 기다릴 것 없이 쓴다(한 번 받기의 조기 종료).
    static let goodEnough: CLLocationAccuracy = 20
    /// 「내 위치」를 누른 뒤 더 나은 값이 오면 한 번 다시 맞추는 시간.
    static let settleWindow: TimeInterval = 5
    /// 한 번 받기가 기다리는 최대 시간. 그 안에 `goodEnough` 가 안 오면 가장 나은 값을 쓴다.
    static let oneShotWait: TimeInterval = 3
    /// 여행 중 이보다 흐린 값은 버린다 — 이미 자리를 알면 튀는 점만 만든다.
    static let tripMaxAccuracy: CLLocationAccuracy = 100

    /// 쓸 수 있는 값인가 — 정확도가 음수면 무효, 너무 오래됐으면 지난 자리다.
    static func isUsable(_ location: CLLocation, now: Date = Date()) -> Bool {
        location.horizontalAccuracy >= 0 && now.timeIntervalSince(location.timestamp) <= maxAge
    }

    /// 지금 보여 주는 값을 새 값으로 바꿀까.
    ///
    /// 더 정확하면 바꾼다. 조금 덜 정확해도 **더 새것**이면 바꾼다 — 사람은 움직이고, 정확도가
    /// 조금 흔들릴 때마다 옛 자리에 붙어 있으면 점이 안 따라온다. 다만 확 흐려진 값(실내로 들어가
    /// 기지국 값으로 떨어진 것)은 버틴다.
    static func shouldReplace(_ current: CLLocation?, with new: CLLocation) -> Bool {
        guard let current else { return true }
        if new.horizontalAccuracy <= current.horizontalAccuracy {
            return true
        }
        guard new.timestamp > current.timestamp else { return false }
        return new.horizontalAccuracy <= max(current.horizontalAccuracy * 1.5, goodEnough)
    }

    /// 「내 위치」로 옮긴 카메라를 다시 맞출 만한가 — 더 정확해졌고 화면에서 티가 날 만큼(20 m) 옮겨졌다.
    static func worthRecentering(from shown: CLLocation, to new: CLLocation) -> Bool {
        new.horizontalAccuracy < shown.horizontalAccuracy && new.distance(from: shown) > 20
    }

    /// 여행 중 받은 값을 쓸까 — 처음 값은 무엇이든 쓰고, 그다음부터는 너무 흐린 값을 버린다.
    static func acceptsTripFix(_ new: CLLocation, hasFix: Bool) -> Bool {
        !hasFix || new.horizontalAccuracy <= tripMaxAccuracy
    }

    /// 「정확한 위치」가 꺼져 있으면 잠깐 켜 달라고 묻는 문구의 열쇠(Info.plist
    /// `NSLocationTemporaryUsageDescriptionDictionary`, 번역은 InfoPlist.strings).
    static let purposeKey = "PreciseLocation"

    /// 「정확한 위치」가 꺼져 있으면 이번만 켜 달라고 묻는다. 거절해도 받은 정확도로 이어 간다.
    static func requestFullAccuracyIfReduced(_ manager: CLLocationManager) {
        guard manager.accuracyAuthorization == .reducedAccuracy else { return }
        manager.requestTemporaryFullAccuracyAuthorization(withPurposeKey: purposeKey)
    }
}
