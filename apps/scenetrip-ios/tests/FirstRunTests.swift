import AppTrackingTransparency
@testable import SceneTrip
import XCTest

/// 첫 실행은 덮개 없이 바로 앱이다 (MZ2AZ-526 — 웹 MZ2AZ-523 과 같다).
///
/// 예전 첫 실행은 두 갈림에 막혀 있었다 — 언어를 고른 적이 있는가(없으면 언어 고르기), 사용법을
/// 본 적이 있는가(없으면 사용법 넉 장, 그리고 그 전에는 ATT 도 묻지 않았다). 이제 둘 다 읽지 않으므로
/// 갓 설치한 상태(저장 언어 없음)에서 영어로 열리고 ATT 도 첫 활성 화면에서 묻는지를 고정한다.
final class FirstRunTests: XCTestCase {
    /// 한국어 기기에 갓 설치해도 영어로 열리고, 첫 활성 화면에서 0.6초 뒤 ATT 를 묻는다.
    @MainActor
    func testFreshInstallOpensInEnglishAndAsksTrackingOnFirstActiveScreen() async {
        XCTAssertEqual(AppLanguage.startingLanguage(saved: nil, device: ["ko-KR"]), .en)

        var requests = 0
        let tracking = TrackingAuthorization(
            status: { .notDetermined },
            request: { _ in requests += 1 },
            activateMeta: {}
        )
        await tracking.requestWhenSettled(isActive: { true }, sleep: { _ in })
        XCTAssertEqual(requests, 1)
    }
}
