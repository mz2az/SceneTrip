import SceneApiClient
@testable import SceneTrip
import XCTest

/// 「오늘의 성지」 카드 윗단은 96pt 그림 자리다. 사진 없는 곳을 고르면 그 자리가
/// 그라데이션만 남은 빈 띠가 된다 — 그래서 **사진 있는 곳에서 고른다**.
final class HomeTodayPhotoTests: XCTestCase {
    private func place(_ image: String?) -> PlaceSummary {
        PlaceSummary(id: 1, name: "성지", latitude: 37.5, longitude: 127.0, imageUrl: image)
    }

    /// 주소가 있다고 그림이 나오는 것은 아니다 — 시드에 호스트 없는 상대 경로가
    /// 섞여 있고, 그것을 사진으로 치면 고르나 마나가 된다.
    func testRelativeOrMissingIsNotAPhoto() {
        XCTAssertFalse(HomeTabModel.hasPhoto(place(nil)))
        XCTAssertFalse(HomeTabModel.hasPhoto(place("")))
        XCTAssertFalse(HomeTabModel.hasPhoto(place("/images/jongno.jpg")))
        XCTAssertFalse(HomeTabModel.hasPhoto(place("images/jongno.jpg")))
    }

    func testAbsoluteHttpUrlIsAPhoto() {
        XCTAssertTrue(HomeTabModel.hasPhoto(place("https://example.com/a.jpg")))
        XCTAssertTrue(HomeTabModel.hasPhoto(place("http://example.com/b.jpg")))
    }
}
