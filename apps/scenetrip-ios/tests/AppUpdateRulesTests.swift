import Foundation
@testable import SceneTrip
import XCTest

/// 앱 업데이트 안내의 규칙 (MZ2AZ-546, 계약 1.27.0 `GET /app/versions`).
/// 계획서 docs/project/plans/app-update-prompt.md §2·§3·§5 와 `AppUpdateRules` 의 설명만 보고 썼다.
final class AppUpdateRulesTests: XCTestCase {
    private let serverStore = URL(string: "https://apps.apple.com/app/id1234567890")!
    private let fallbackStore = URL(string: "itms-apps://apps.apple.com/app/id0000000000")!

    private func rule(minimum: String, latest: String, store: URL?) -> AppUpdateRules.Rule {
        AppUpdateRules.Rule(minimum: minimum, latest: latest, storeURL: store)
    }

    // MARK: - compare

    func testCompareEqualVersionsIsOrderedSame() {
        XCTAssertEqual(AppUpdateRules.compare("1.2.3", "1.2.3"), .orderedSame)
    }

    func testCompareSmallerIsOrderedAscending() {
        XCTAssertEqual(AppUpdateRules.compare("1.2.3", "1.2.4"), .orderedAscending)
        XCTAssertEqual(AppUpdateRules.compare("1.2.9", "1.3.0"), .orderedAscending)
        XCTAssertEqual(AppUpdateRules.compare("0.9.9", "1.0.0"), .orderedAscending)
    }

    func testCompareGreaterIsOrderedDescending() {
        XCTAssertEqual(AppUpdateRules.compare("1.2.4", "1.2.3"), .orderedDescending)
        XCTAssertEqual(AppUpdateRules.compare("2.0.0", "1.9.9"), .orderedDescending)
    }

    /// 글자가 아니라 숫자로 견준다 — 글자로 보면 "1.10.0" < "1.9.9" 가 된다.
    func testCompareFieldsNumericallyNotLexically() {
        XCTAssertEqual(AppUpdateRules.compare("1.10.0", "1.9.9"), .orderedDescending)
        XCTAssertEqual(AppUpdateRules.compare("1.9.9", "1.10.0"), .orderedAscending)
    }

    /// 없는 칸은 0.
    func testCompareMissingFieldsCountAsZero() {
        XCTAssertEqual(AppUpdateRules.compare("1.2", "1.2.0"), .orderedSame)
        XCTAssertEqual(AppUpdateRules.compare("1.2.0", "1.2"), .orderedSame)
        XCTAssertEqual(AppUpdateRules.compare("2", "1.99.99"), .orderedDescending)
        XCTAssertEqual(AppUpdateRules.compare("1.99.99", "2"), .orderedAscending)
    }

    /// 빈 값·숫자 아닌 칸·칸 넷 이상은 nil — 어느 쪽에 있어도.
    func testCompareInvalidInputIsNil() {
        // 대조: 맞는 값끼리는 nil 이 아니어야 아래의 nil 이 의미가 있다.
        XCTAssertEqual(AppUpdateRules.compare("1.0.0", "1.0.0"), .orderedSame)
        for bad in ["", "1.x", "1.2.3.4", "-1", "1..2"] {
            XCTAssertNil(AppUpdateRules.compare(bad, "1.0.0"), "왼쪽 \"\(bad)\"")
            XCTAssertNil(AppUpdateRules.compare("1.0.0", bad), "오른쪽 \"\(bad)\"")
        }
    }

    // MARK: - decide

    /// 설치 < minimum → 닫을 수 없는 강제 업데이트 (latest 보다도 낮지만 강제가 이긴다).
    func testInstalledBelowMinimumIsRequired() {
        let decision = AppUpdateRules.decide(
            installed: "1.0.0",
            rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
            dismissedLatest: nil,
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(decision, .required(store: serverStore))
    }

    func testInstalledBelowMinimumEqualToLatestIsRequired() {
        let decision = AppUpdateRules.decide(
            installed: "1.1.9",
            rule: rule(minimum: "1.2.0", latest: "1.2.0", store: serverStore),
            dismissedLatest: nil,
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(decision, .required(store: serverStore))
    }

    /// minimum ≤ 설치 < latest → 권장.
    func testInstalledBetweenMinimumAndLatestIsRecommended() {
        for installed in ["1.2.0", "1.3.0", "1.4.9"] {
            let decision = AppUpdateRules.decide(
                installed: installed,
                rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
                dismissedLatest: nil,
                fallbackStore: fallbackStore
            )
            XCTAssertEqual(decision, .recommended(latest: "1.5.0", store: serverStore), installed)
        }
    }

    /// 설치 ≥ latest → 아무것도 띄우지 않는다.
    func testInstalledAtOrAboveLatestIsNone() {
        // 대조: 바로 아래 버전은 권장이 나와야 아래의 none 이 의미가 있다.
        XCTAssertEqual(
            AppUpdateRules.decide(installed: "1.4.9",
                                  rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
                                  dismissedLatest: nil, fallbackStore: fallbackStore),
            .recommended(latest: "1.5.0", store: serverStore)
        )
        for installed in ["1.5.0", "1.5", "1.5.1", "2.0.0"] {
            let decision = AppUpdateRules.decide(
                installed: installed,
                rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
                dismissedLatest: nil,
                fallbackStore: fallbackStore
            )
            XCTAssertEqual(decision, .none, installed)
        }
    }

    /// 「나중에」를 누른 그 latest 에는 다시 묻지 않는다.
    func testDismissedSameLatestSuppressesRecommendation() {
        // 대조: 「나중에」가 없으면 같은 값에서 권장이 나온다.
        XCTAssertEqual(
            AppUpdateRules.decide(installed: "1.3.0",
                                  rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
                                  dismissedLatest: nil, fallbackStore: fallbackStore),
            .recommended(latest: "1.5.0", store: serverStore)
        )
        let decision = AppUpdateRules.decide(
            installed: "1.3.0",
            rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
            dismissedLatest: "1.5.0",
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(decision, .none)
    }

    /// 「나중에」는 강제를 막지 못한다.
    func testDismissedLatestDoesNotSuppressRequired() {
        let decision = AppUpdateRules.decide(
            installed: "1.0.0",
            rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
            dismissedLatest: "1.5.0",
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(decision, .required(store: serverStore))
    }

    /// 더 새 latest 가 나오면 다시 권한다.
    func testOlderDismissedLatestRecommendsAgain() {
        let decision = AppUpdateRules.decide(
            installed: "1.3.0",
            rule: rule(minimum: "1.2.0", latest: "1.6.0", store: serverStore),
            dismissedLatest: "1.5.0",
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(decision, .recommended(latest: "1.6.0", store: serverStore))
    }

    /// 서버가 storeUrl 을 주지 않으면 앱의 주소를 쓴다 — 강제·권장 모두.
    func testMissingServerStoreFallsBackToAppStore() {
        let required = AppUpdateRules.decide(
            installed: "1.0.0",
            rule: rule(minimum: "1.2.0", latest: "1.5.0", store: nil),
            dismissedLatest: nil,
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(required, .required(store: fallbackStore))

        let recommended = AppUpdateRules.decide(
            installed: "1.3.0",
            rule: rule(minimum: "1.2.0", latest: "1.5.0", store: nil),
            dismissedLatest: nil,
            fallbackStore: fallbackStore
        )
        XCTAssertEqual(recommended, .recommended(latest: "1.5.0", store: fallbackStore))
    }

    /// 형식이 틀린 값이 하나라도 있으면 아무것도 띄우지 않는다 — 업데이트 확인 때문에 앱을 막지 않는다.
    /// 다른 값은 강제가 나올 조합으로 두어, 틀린 값을 무시하고 강제를 띄우는 구현도 잡는다.
    func testInvalidVersionAnywhereIsNone() {
        // 대조: 값이 모두 맞으면 이 조합은 강제다.
        XCTAssertEqual(
            AppUpdateRules.decide(installed: "1.0.0",
                                  rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
                                  dismissedLatest: nil, fallbackStore: fallbackStore),
            .required(store: serverStore)
        )
        for bad in ["", "1.x", "1.2.3.4", "-1", "1..2"] {
            XCTAssertEqual(
                AppUpdateRules.decide(installed: bad,
                                      rule: rule(minimum: "1.2.0", latest: "1.5.0", store: serverStore),
                                      dismissedLatest: nil, fallbackStore: fallbackStore),
                .none, "installed \"\(bad)\""
            )
            XCTAssertEqual(
                AppUpdateRules.decide(installed: "1.0.0",
                                      rule: rule(minimum: bad, latest: "1.5.0", store: serverStore),
                                      dismissedLatest: nil, fallbackStore: fallbackStore),
                .none, "minimum \"\(bad)\""
            )
            XCTAssertEqual(
                AppUpdateRules.decide(installed: "1.0.0",
                                      rule: rule(minimum: "1.2.0", latest: bad, store: serverStore),
                                      dismissedLatest: nil, fallbackStore: fallbackStore),
                .none, "latest \"\(bad)\""
            )
        }
    }
}
