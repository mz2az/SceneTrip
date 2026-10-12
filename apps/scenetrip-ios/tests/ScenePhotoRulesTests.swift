import SceneApiClient
@testable import SceneTrip
import XCTest

final class ScenePhotoRulesTests: XCTestCase {
    func testOnlySceneImageAppearsEvenWhenPosterIsPresent() throws {
        let scene = SceneItem(contentId: 7, contentTitle: "작품", posterUrl: "https://example.com/poster.jpg",
                              sceneImageUrl: "https://example.com/scene.jpg")
        let photo = ScenePhotoRules.photo(scene)
        XCTAssertEqual(photo?.url, "https://example.com/scene.jpg")
        XCTAssertFalse(photo?.isReview ?? true)
        XCTAssertTrue(try PhotoGalleryRules.signed(XCTUnwrap(photo)))
    }

    func testMissingAndLocalSceneImagesNeverUsePosterAsFallback() {
        for raw in [nil, "", "  ", "scenes/7.jpg", "file:///tmp/scene.jpg", "https:///scene.jpg",
                    "javascript:alert(1)", "https://user:pass@example.com/a.jpg"]
        {
            let scene = SceneItem(contentId: 7, contentTitle: "작품", posterUrl: "https://example.com/poster.jpg",
                                  sceneImageUrl: raw)
            XCTAssertNil(ScenePhotoRules.photo(scene), raw ?? "nil")
        }
    }

    func testDifferentSceneImagesOfSameWorkHaveDifferentCacheKeys() {
        let one = SceneItem(contentId: 7, contentTitle: "작품", sceneImageUrl: "https://example.com/one.jpg")
        let two = SceneItem(contentId: 7, contentTitle: "작품", sceneImageUrl: "https://example.com/two.jpg")
        XCTAssertNotEqual(ScenePhotoRules.photo(one)?.key, ScenePhotoRules.photo(two)?.key)
    }

    func testSceneDescriptionCanRemainWithoutPhoto() {
        let scene = SceneItem(contentId: 7, contentTitle: "작품", sceneDescription: "이곳의 장면")
        XCTAssertNil(ScenePhotoRules.photo(scene))
        XCTAssertEqual(scene.sceneDescription, "이곳의 장면")
    }

    func testWholeWideAndTallImagesFitWithoutCropping() {
        XCTAssertEqual(ScenePhotoRules.fittedSize(image: CGSize(width: 800, height: 400),
                                                  viewport: CGSize(width: 300, height: 600)),
                       CGSize(width: 300, height: 150))
        XCTAssertEqual(ScenePhotoRules.fittedSize(image: CGSize(width: 400, height: 800),
                                                  viewport: CGSize(width: 600, height: 300)),
                       CGSize(width: 150, height: 300))
    }

    func testInvalidGeometryDoesNotProduceInfiniteFrames() {
        for size in [CGSize.zero, CGSize(width: -1, height: 1), CGSize(width: CGFloat.infinity, height: 1)] {
            XCTAssertEqual(ScenePhotoRules.fittedSize(image: size, viewport: CGSize(width: 300, height: 600)), .zero)
            XCTAssertEqual(ScenePhotoRules.fittedSize(image: CGSize(width: 300, height: 600), viewport: size), .zero)
        }
    }

    func testZoomIsBoundedAndInvalidValuesReturnToFit() {
        XCTAssertEqual(ScenePhotoRules.scale(-1), 1)
        XCTAssertEqual(ScenePhotoRules.scale(2.5), 2.5)
        XCTAssertEqual(ScenePhotoRules.scale(8), 4)
        XCTAssertEqual(ScenePhotoRules.scale(.nan), 1)
        XCTAssertEqual(ScenePhotoRules.scale(.infinity), 1)
    }
}
