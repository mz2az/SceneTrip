import SceneApiClient
@testable import SceneTrip
import XCTest

final class ScenePhotoRulesTests: XCTestCase {
    func testArrayPreservesServerOrderIncludingRepeatedImages() {
        let urls = ["https://example.com/two.jpg", "https://example.com/one.jpg", "https://example.com/two.jpg"]
        let scene = SceneItem(contentId: 7, contentTitle: "작품",
                              sceneImageUrl: "https://example.com/legacy.jpg", sceneImageUrls: urls)
        XCTAssertEqual(ScenePhotoRules.photos(scene).map(\.url), urls)
        XCTAssertEqual(ScenePhotoRules.photo(scene)?.url, urls[0])
    }

    func testEmptyArrayNeverFallsBackToLegacyImageOrPoster() {
        let scene = SceneItem(contentId: 7, contentTitle: "작품", posterUrl: "https://example.com/poster.jpg",
                              sceneImageUrl: "https://example.com/legacy.jpg", sceneImageUrls: [])
        XCTAssertTrue(ScenePhotoRules.photos(scene).isEmpty)
        XCTAssertNil(ScenePhotoRules.photo(scene))
    }

    func testInvalidArrayEntriesAreSkippedWithoutReorderingValidPhotos() {
        let scene = SceneItem(contentId: 7, contentTitle: "작품", sceneImageUrls: [
            "file:///tmp/local.jpg", "https://example.com/one.jpg", "", "https://example.com/two.jpg",
        ])
        XCTAssertEqual(ScenePhotoRules.photos(scene).map(\.url),
                       ["https://example.com/one.jpg", "https://example.com/two.jpg"])
    }

    func testOnlySceneImageAppearsEvenWhenPosterIsPresent() throws {
        let scene = SceneItem(contentId: 7, contentTitle: "작품", posterUrl: "https://example.com/poster.jpg",
                              sceneImageUrl: "https://example.com/scene.jpg",
                              sceneImageUrls: ["https://example.com/scene.jpg"])
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
                                  sceneImageUrl: raw, sceneImageUrls: raw.map { [$0] } ?? [])
            XCTAssertNil(ScenePhotoRules.photo(scene), raw ?? "nil")
        }
    }

    func testDifferentSceneImagesOfSameWorkHaveDifferentCacheKeys() {
        let one = SceneItem(contentId: 7, contentTitle: "작품", sceneImageUrl: "https://example.com/one.jpg",
                            sceneImageUrls: ["https://example.com/one.jpg"])
        let two = SceneItem(contentId: 7, contentTitle: "작품", sceneImageUrl: "https://example.com/two.jpg",
                            sceneImageUrls: ["https://example.com/two.jpg"])
        XCTAssertNotEqual(ScenePhotoRules.photo(one)?.key, ScenePhotoRules.photo(two)?.key)
    }

    func testSceneDescriptionCanRemainWithoutPhoto() {
        let scene = SceneItem(contentId: 7, contentTitle: "작품", sceneDescription: "이곳의 장면", sceneImageUrls: [])
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
