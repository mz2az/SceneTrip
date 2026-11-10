import Foundation

/// 앱 업데이트 안내의 규칙 — 값만 보고 정한다 (MZ2AZ-546, 계약 1.27.0 `GET /app/versions`).
///
/// 서버가 주는 iOS 규칙(`minimum`·`latest`·`storeUrl`)과 설치된 버전을 견준다.
/// - 설치 < minimum → 닫을 수 없는 강제 업데이트
/// - 설치 < latest → 권장 업데이트. 「나중에」를 누른 그 latest 에는 다시 묻지 않는다
/// - 그 밖·형식이 틀린 값 → 아무것도 띄우지 않는다(업데이트 확인 때문에 앱을 막지 않는다)
enum AppUpdateRules {
    /// 서버가 준 한 플랫폼의 규칙.
    struct Rule: Equatable {
        var minimum: String
        var latest: String
        var storeURL: URL?
    }

    enum Decision: Equatable {
        case none
        /// 권장 — [나중에][업데이트].
        case recommended(latest: String, store: URL)
        /// 강제 — [업데이트] 만.
        case required(store: URL)
    }

    /// 점으로 나눈 칸을 앞에서부터 숫자로 견준다. 없는 칸은 0 (`1.2` = `1.2.0`, `1.10.0` > `1.9.9`).
    /// 형식이 틀리면(숫자 아닌 칸, 칸이 넷 이상, 빈 값) nil.
    static func compare(_ lhs: String, _ rhs: String) -> ComparisonResult? {
        guard let left = parts(lhs), let right = parts(rhs) else { return nil }
        for index in 0 ..< max(left.count, right.count) {
            let mine = index < left.count ? left[index] : 0
            let theirs = index < right.count ? right[index] : 0
            if mine != theirs {
                return mine < theirs ? .orderedAscending : .orderedDescending
            }
        }
        return .orderedSame
    }

    /// `1`~`1.2.3` 의 칸들. 숫자만, 한 칸~세 칸이 아니면 nil.
    private static func parts(_ version: String) -> [Int]? {
        let fields = version.split(separator: ".", omittingEmptySubsequences: false)
        guard (1 ... 3).contains(fields.count) else { return nil }
        var out: [Int] = []
        for field in fields {
            guard !field.isEmpty, field.allSatisfy(\.isASCII), field.allSatisfy(\.isNumber), let number = Int(field) else {
                return nil
            }
            out.append(number)
        }
        return out
    }

    /// 무엇을 띄울지. `dismissedLatest` 는 「나중에」를 누른 latest, `fallbackStore` 는 서버가 스토어 주소를 주지 않을 때 쓸 주소.
    static func decide(installed: String, rule: Rule, dismissedLatest: String?, fallbackStore: URL) -> Decision {
        guard let toMinimum = compare(installed, rule.minimum), let toLatest = compare(installed, rule.latest) else { return .none }
        let store = rule.storeURL ?? fallbackStore
        if toMinimum == .orderedAscending {
            return .required(store: store)
        }
        if toLatest == .orderedAscending, dismissedLatest.flatMap({ compare($0, rule.latest) }) != .orderedSame {
            return .recommended(latest: rule.latest, store: store)
        }
        return .none
    }
}
