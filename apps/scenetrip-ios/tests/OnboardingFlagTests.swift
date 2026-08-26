@testable import SceneTrip
import XCTest

/// 사용법을 언제 보여 주는가를 고정한다.
///
/// 눈으로 못 보는 규칙이라 틀어져도 티가 안 난다 — 잘못되면 첫 실행에 안 보이거나
/// (사용법이 있으나 마나) 켤 때마다 보인다(성가시다). 둘 다 되돌리기 전까지
/// 아무도 모른다.
final class OnboardingFlagTests: XCTestCase {
    private let key = "scenetrip.onboarding.seenVersion"

    override func setUp() {
        super.setUp()
        UserDefaults.standard.removeObject(forKey: key)
    }

    override func tearDown() {
        UserDefaults.standard.removeObject(forKey: key)
        super.tearDown()
    }

    func testFirstLaunchShowsLessons() {
        XCTAssertFalse(OnboardingFlag.hasSeen)
    }

    func testSecondLaunchSkipsLessons() {
        OnboardingFlag.markSeen()
        XCTAssertTrue(OnboardingFlag.hasSeen)
    }

    /// 판이 올라가면 이미 본 사람에게도 한 번 더 보인다. `true`/`false` 로 뒀다면
    /// 튜토리얼을 통째로 갈아도 기존 사용자는 영영 못 본다.
    func testOlderVersionShowsAgain() {
        UserDefaults.standard.set(OnboardingFlag.version - 1, forKey: key)
        XCTAssertFalse(OnboardingFlag.hasSeen)
    }

    func testResetShowsAgain() {
        OnboardingFlag.markSeen()
        OnboardingFlag.reset()
        XCTAssertFalse(OnboardingFlag.hasSeen)
    }

    /// 저장 열쇠가 `InstallIdentity` 와 같은 접두를 쓴다. 접두가 갈리면 나중에
    /// 「우리가 남긴 값」을 한 번에 훑을 수 없다.
    func testKeyStaysNamespaced() {
        OnboardingFlag.markSeen()
        XCTAssertTrue(key.hasPrefix("scenetrip."))
        XCTAssertEqual(UserDefaults.standard.integer(forKey: key), OnboardingFlag.version)
    }
}
