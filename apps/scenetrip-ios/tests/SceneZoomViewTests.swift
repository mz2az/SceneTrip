@testable import SceneTrip
import UIKit
import XCTest

final class SceneZoomViewTests: XCTestCase {
    @MainActor func testNativeZoomUsesPhotoAndResetsForNewViewport() {
        let scroll = SceneZoomScrollView()
        scroll.frame = CGRect(x: 0, y: 0, width: 300, height: 600)
        let image = UIGraphicsImageRenderer(size: CGSize(width: 800, height: 400)).image { _ in }
        scroll.display(image)
        scroll.layoutIfNeeded()
        XCTAssertTrue(scroll.viewForZooming(in: scroll) === scroll.imageView)
        XCTAssertEqual(scroll.imageView.bounds.size, CGSize(width: 300, height: 150))
        XCTAssertEqual(scroll.imageView.frame.midY - scroll.contentOffset.y, 300, accuracy: 0.1)
        scroll.setZoomScale(3, animated: false)
        XCTAssertEqual(scroll.zoomScale, 3)
        XCTAssertGreaterThan(scroll.contentSize.width, scroll.bounds.width)
        scroll.frame.size = CGSize(width: 600, height: 300)
        scroll.layoutIfNeeded()
        XCTAssertEqual(scroll.zoomScale, 1)
        XCTAssertEqual(scroll.imageView.bounds.size, CGSize(width: 600, height: 300))
    }

    @MainActor func testNewImageResetsZoomWithoutResettingSameImage() {
        let scroll = SceneZoomScrollView()
        scroll.frame = CGRect(x: 0, y: 0, width: 300, height: 600)
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 800, height: 400))
        let first = renderer.image { _ in }
        let second = renderer.image { _ in }
        scroll.display(first)
        scroll.layoutIfNeeded()
        scroll.setZoomScale(2, animated: false)
        scroll.display(first)
        XCTAssertEqual(scroll.zoomScale, 2)
        scroll.display(second)
        scroll.layoutIfNeeded()
        XCTAssertEqual(scroll.zoomScale, 1)
    }
}
