import ImageIO
@testable import SceneTrip
import UIKit
import UniformTypeIdentifiers
import XCTest

/// 사진 줄이기 (MZ2AZ-363) — 크기 셈과, 다시 그린 사진에 위치(EXIF)가 남지 않는 것.
final class PhotoShrinkTests: XCTestCase {
    /// 긴 변을 맞추고 비율을 지킨다. 가로·세로 어느 쪽이 길든.
    func testSizeFitsTheLongestSide() {
        XCTAssertEqual(
            PhotoShrink.size(width: 4032, height: 3024, longest: 2048), CGSize(width: 2048, height: 1536)
        )
        XCTAssertEqual(
            PhotoShrink.size(width: 3024, height: 4032, longest: 2048), CGSize(width: 1536, height: 2048)
        )
        XCTAssertEqual(
            PhotoShrink.size(width: 5000, height: 5000, longest: 2048), CGSize(width: 2048, height: 2048)
        )
    }

    /// 작은 사진은 키우지 않는다. 딱 상한인 사진도 그대로다.
    func testSizeNeverEnlarges() {
        XCTAssertEqual(PhotoShrink.size(width: 800, height: 600, longest: 2048), CGSize(width: 800, height: 600))
        XCTAssertEqual(
            PhotoShrink.size(width: 2048, height: 1000, longest: 2048), CGSize(width: 2048, height: 1000)
        )
        XCTAssertEqual(
            PhotoShrink.size(width: 2049, height: 1000, longest: 2048), CGSize(width: 2048, height: 1000)
        )
    }

    /// 아주 길쭉한 사진(파노라마)도 짧은 변이 0 이 되지 않고, 이상한 값에도 죽지 않는다.
    func testSizeSurvivesExtremeShapes() {
        XCTAssertEqual(PhotoShrink.size(width: 20000, height: 4, longest: 2048), CGSize(width: 2048, height: 1))
        XCTAssertEqual(PhotoShrink.size(width: 0, height: 0, longest: 2048), CGSize(width: 1, height: 1))
        XCTAssertEqual(PhotoShrink.size(width: 100, height: 100, longest: 0), CGSize(width: 1, height: 1))
    }

    /// 큰 사진은 긴 변 2,048 의 JPEG 가 되고, 10 MB 안에 든다.
    func testJpegIsShrunkToTheLongestSide() throws {
        let photo = Self.solid(width: 4000, height: 3000)
        let data = try XCTUnwrap(PhotoShrink.jpeg(photo, longest: 2048, maxBytes: 10 * 1024 * 1024))
        let made = try XCTUnwrap(UIImage(data: data))
        XCTAssertEqual(made.size.width * made.scale, 2048)
        XCTAssertEqual(made.size.height * made.scale, 1536)
        XCTAssertLessThanOrEqual(data.count, 10 * 1024 * 1024)
        // JPEG 이다 — 첫 두 바이트가 FF D8.
        XCTAssertEqual(Array(data.prefix(2)), [0xFF, 0xD8])
    }

    /// 배율이 붙은 그림(화면 캡처는 3 배)도 픽셀로 셈한다.
    func testJpegCountsPixelsNotPoints() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 3
        let photo = UIGraphicsImageRenderer(size: CGSize(width: 1000, height: 500), format: format).image { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1000, height: 500))
        }
        let data = try XCTUnwrap(PhotoShrink.jpeg(photo, longest: 2048))
        let made = try XCTUnwrap(UIImage(data: data))
        XCTAssertEqual(made.size.width * made.scale, 2048)
        XCTAssertEqual(made.size.height * made.scale, 1024)
    }

    /// 상한 안에 못 넣으면 nil — 부르는 쪽이 「너무 크다」 고 알린다.
    func testJpegOverTheByteLimitIsNil() {
        XCTAssertNil(PhotoShrink.jpeg(Self.solid(width: 400, height: 300), longest: 2048, maxBytes: 10))
    }

    /// **다시 그린 사진에는 촬영 위치·기기 정보가 없다.** 리뷰 사진은 남에게 보인다.
    func testRedrawnJpegCarriesNoLocationOrCameraMetadata() throws {
        let original = try Self.jpegWithLocation()
        // 시험이 뜻이 있으려면 원본에는 위치가 들어 있어야 한다.
        XCTAssertNotNil(Self.properties(of: original)[kCGImagePropertyGPSDictionary])

        let photo = try XCTUnwrap(UIImage(data: original))
        let data = try XCTUnwrap(PhotoShrink.jpeg(photo, longest: 2048, maxBytes: 10 * 1024 * 1024))
        let properties = Self.properties(of: data)
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary])
        let tiff = properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        XCTAssertNil(tiff?[kCGImagePropertyTIFFMake])
        XCTAssertNil(tiff?[kCGImagePropertyTIFFModel])
        let exif = properties[kCGImagePropertyExifDictionary] as? [CFString: Any]
        XCTAssertNil(exif?[kCGImagePropertyExifDateTimeOriginal])
    }

    // MARK: 파일에서 바로 (통째로 풀지 않는 길)

    /// 큰 파일은 긴 변까지만 풀린다. 작은 파일은 키우지 않는다.
    func testImageFromFileIsDecodedDownToTheLongestSide() throws {
        let big = try Self.encoded(Self.solid(width: 4000, height: 3000), as: .jpeg)
        let shrunk = try XCTUnwrap(PhotoShrink.image(from: big, longest: 2048))
        XCTAssertEqual(Self.pixels(shrunk), CGSize(width: 2048, height: 1536))

        let small = try Self.encoded(Self.solid(width: 800, height: 600), as: .jpeg)
        let kept = try XCTUnwrap(PhotoShrink.image(from: small, longest: 2048))
        XCTAssertEqual(Self.pixels(kept), CGSize(width: 800, height: 600))
    }

    /// 세로로 찍은 사진(방향 6 — 파일은 누워 있다)은 바로 선다. 화면이 그리는 모습(`UIImage(data:)`)과 같다.
    func testImageFromFileStandsUprightForARotatedPhoto() throws {
        let file = try Self.encoded(Self.halves(width: 400, height: 200), as: .jpeg, orientation: 6)
        let made = try XCTUnwrap(PhotoShrink.image(from: file, longest: 2048))
        XCTAssertEqual(Self.pixels(made), CGSize(width: 200, height: 400))
        XCTAssertEqual(made.imageOrientation, .up)

        // 위쪽과 아래쪽의 색이 UIKit 이 방향을 읽어 그린 것과 같다.
        let reference = try XCTUnwrap(PhotoShrink.jpeg(XCTUnwrap(UIImage(data: file)), longest: 2048))
        let expected = try XCTUnwrap(UIImage(data: reference))
        for point in [CGPoint(x: 100, y: 50), CGPoint(x: 100, y: 350)] {
            let got = try XCTUnwrap(Self.color(of: made, at: point))
            let want = try XCTUnwrap(Self.color(of: expected, at: point))
            XCTAssertEqual(got.red, want.red, accuracy: 0.1)
            XCTAssertEqual(got.blue, want.blue, accuracy: 0.1)
        }
        // 그리고 위와 아래는 서로 다른 색이다(반반 그림이 통째로 한 색이 되지 않았다).
        let top = try XCTUnwrap(Self.color(of: made, at: CGPoint(x: 100, y: 50)))
        let bottom = try XCTUnwrap(Self.color(of: made, at: CGPoint(x: 100, y: 350)))
        XCTAssertGreaterThan(abs(top.red - bottom.red), 0.5)
    }

    /// 파일에서 바로 만든 JPEG 에도 위치·기기 정보가 없다.
    func testJpegFromFileCarriesNoLocation() throws {
        let original = try Self.jpegWithLocation()
        let drawn = try XCTUnwrap(PhotoShrink.image(from: original, longest: 2048))
        let data = try XCTUnwrap(PhotoShrink.encode(drawn, maxBytes: 10 * 1024 * 1024))
        let properties = Self.properties(of: data)
        XCTAssertNil(properties[kCGImagePropertyGPSDictionary])
        XCTAssertNil((properties[kCGImagePropertyTIFFDictionary] as? [CFString: Any])?[kCGImagePropertyTIFFMake])
        XCTAssertEqual(Array(data.prefix(2)), [0xFF, 0xD8])
    }

    /// 투명한 PNG 는 흰 바탕이 된다 — 미리보기와 올라간 파일이 같은 모습이다(검게 나오지 않는다).
    func testTransparentPngGetsAWhiteBackground() throws {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let clear = UIGraphicsImageRenderer(size: CGSize(width: 120, height: 80), format: format).image { _ in }
        let file = try XCTUnwrap(clear.pngData())
        for made in [PhotoShrink.image(from: file, longest: 2048), PhotoShrink.preview(from: file, side: 40)] {
            let color = try XCTUnwrap(Self.color(of: XCTUnwrap(made), at: CGPoint(x: 10, y: 10)))
            XCTAssertEqual(color.red, 1, accuracy: 0.02)
            XCTAssertEqual(color.green, 1, accuracy: 0.02)
            XCTAssertEqual(color.blue, 1, accuracy: 0.02)
        }
    }

    /// 미리보기는 짧은 변이 칸 크기쯤이고, 길쭉한 사진은 네 배에서 끊는다.
    func testPreviewIsSmall() throws {
        XCTAssertEqual(PhotoShrink.previewLongest(width: 4000, height: 3000, side: 276), 368)
        XCTAssertEqual(PhotoShrink.previewLongest(width: 3000, height: 4000, side: 276), 368)
        XCTAssertEqual(PhotoShrink.previewLongest(width: 20000, height: 1000, side: 276), 1104)
        let file = try Self.encoded(Self.solid(width: 4000, height: 3000), as: .jpeg)
        let preview = try XCTUnwrap(PhotoShrink.preview(from: file, side: 276))
        XCTAssertEqual(Self.pixels(preview), CGSize(width: 368, height: 276))
    }

    /// 그림이 아닌 파일은 nil — 부르는 쪽이 「읽지 못했어요」 라고 알린다.
    func testImageFromGarbageIsNil() {
        XCTAssertNil(PhotoShrink.image(from: Data("not a picture".utf8), longest: 2048))
        XCTAssertNil(PhotoShrink.image(from: Data(), longest: 2048))
        XCTAssertNil(PhotoShrink.preview(from: Data(), side: 276))
    }

    // MARK: 시험용 그림

    private static func pixels(_ image: UIImage) -> CGSize {
        CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
    }

    /// 왼쪽 반은 빨강, 오른쪽 반은 파랑.
    private static func halves(width: CGFloat, height: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            UIColor.red.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width / 2, height: height))
            UIColor.blue.setFill()
            context.fill(CGRect(x: width / 2, y: 0, width: width / 2, height: height))
        }
    }

    private static func encoded(_ image: UIImage, as type: UTType, orientation: Int? = nil) throws -> Data {
        let data = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(data, type.identifier as CFString, 1, nil)
        )
        var metadata: [CFString: Any] = [:]
        if let orientation {
            metadata[kCGImagePropertyOrientation] = orientation
        }
        try CGImageDestinationAddImage(destination, XCTUnwrap(image.cgImage), metadata as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    /// 한 점의 색(0~1). 좌표는 픽셀, 위에서부터.
    private static func color(of image: UIImage, at point: CGPoint) -> (red: CGFloat, green: CGFloat, blue: CGFloat)? {
        var pixel = [UInt8](repeating: 0, count: 4)
        guard let context = CGContext(
            data: &pixel, width: 1, height: 1, bitsPerComponent: 8, bytesPerRow: 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return nil }
        UIGraphicsPushContext(context)
        defer { UIGraphicsPopContext() }
        // UIKit 좌표(위가 0)로 그리게 뒤집고, 보려는 점이 (0,0) 에 오게 민다.
        context.translateBy(x: 0, y: 1)
        context.scaleBy(x: 1, y: -1)
        image.draw(at: CGPoint(x: -point.x, y: -point.y))
        return (CGFloat(pixel[0]) / 255, CGFloat(pixel[1]) / 255, CGFloat(pixel[2]) / 255)
    }

    private static func solid(width: CGFloat, height: CGFloat) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: CGSize(width: width, height: height), format: format).image { context in
            UIColor.systemOrange.setFill()
            context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        }
    }

    /// 위치·기기·촬영 시각이 박힌 JPEG — 폰으로 찍은 사진의 흉내.
    private static func jpegWithLocation() throws -> Data {
        let image = try XCTUnwrap(solid(width: 640, height: 480).cgImage)
        let data = NSMutableData()
        let destination = try XCTUnwrap(
            CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil)
        )
        let metadata: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: [
                kCGImagePropertyGPSLatitude: 37.5796, kCGImagePropertyGPSLatitudeRef: "N",
                kCGImagePropertyGPSLongitude: 126.9770, kCGImagePropertyGPSLongitudeRef: "E",
            ],
            kCGImagePropertyTIFFDictionary: [
                kCGImagePropertyTIFFMake: "Apple", kCGImagePropertyTIFFModel: "iPhone 17",
            ],
            kCGImagePropertyExifDictionary: [
                kCGImagePropertyExifDateTimeOriginal: "2026:10:08 09:00:00",
            ],
        ]
        CGImageDestinationAddImage(destination, image, metadata as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination))
        return data as Data
    }

    private static func properties(of data: Data) -> [CFString: Any] {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let found = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        else { return [:] }
        return found
    }
}
