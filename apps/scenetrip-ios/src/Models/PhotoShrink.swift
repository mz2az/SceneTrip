import ImageIO
import UIKit

/// 올리거나 저장하기 전에 사진을 줄인다. 여행후기(기기 저장)와 리뷰(서버 올리기)가 같이 쓴다.
///
/// **줄이는 것이 아니라 다시 그린다** — 원본 파일을 그대로 쓰지 않고 새 그림에 그려 JPEG 로 만든다.
/// 그래서 원본에 들어 있던 **촬영 위치(GPS)·기기·시각 같은 EXIF 가 따라오지 않는다**
/// (`PhotoShrinkTests` 가 지킨다). 리뷰 사진은 남에게 보이므로 이 성질이 개인정보 보호 장치다.
///
/// **고른 파일은 통째로 풀지 않는다.** 폰 사진 한 장은 풀면 수십 MB 다 — ImageIO 에게 「긴 변 얼마까지만」 을
/// 일러 처음부터 작게 푼다(`image(from:longest:)`). 열 장을 고르는 화면에서 메모리가 튀지 않는다.
enum PhotoShrink {
    /// JPEG 품질. 0.82 는 눈으로 차이가 안 보이면서 폰 사진이 수백 KB 로 떨어지는 값이다.
    static let quality: CGFloat = 0.82

    /// 상한을 넘을 때 차례로 낮춰 보는 품질. 긴 변 2,048 의 사진은 첫 값에서 끝난다 — 나머지는 안전망이다.
    static let qualityLadder: [CGFloat] = [quality, 0.6, 0.4]

    /// 긴 변을 `longest` 에 맞춘 크기(픽셀). **키우지 않는다** — 작은 사진은 그대로다.
    /// 비율은 지키고, 아주 길쭉한 사진도 짧은 변이 1 아래로 내려가지 않는다.
    static func size(width: CGFloat, height: CGFloat, longest: CGFloat) -> CGSize {
        guard width >= 1, height >= 1, longest >= 1 else { return CGSize(width: 1, height: 1) }
        let scale = min(1, longest / max(width, height))
        return CGSize(
            width: max(1, (width * scale).rounded()),
            height: max(1, (height * scale).rounded())
        )
    }

    /// 다시 그린 JPEG. 사진의 방향(세로로 찍은 사진)은 그림에 구워진다.
    static func jpeg(_ photo: UIImage, longest: CGFloat, quality: CGFloat = quality) -> Data? {
        redraw(photo, longest: longest).jpegData(compressionQuality: quality)
    }

    /// `maxBytes` 안에 들어오는 JPEG. 품질을 낮춰도 넘으면 nil — 부르는 쪽이 「너무 크다」 고 알린다.
    static func jpeg(_ photo: UIImage, longest: CGFloat, maxBytes: Int) -> Data? {
        encode(redraw(photo, longest: longest), maxBytes: maxBytes)
    }

    /// 이미 다 그려 둔 그림(`image(from:longest:)` 의 결과)을 `maxBytes` 안의 JPEG 로. 다시 그리지 않는다.
    static func encode(_ drawn: UIImage, maxBytes: Int) -> Data? {
        for step in qualityLadder {
            if let data = drawn.jpegData(compressionQuality: step), data.count <= maxBytes {
                return data
            }
        }
        return nil
    }

    // MARK: 파일에서 바로

    /// 고른 파일을 긴 변 `longest` 까지만 풀어 그린 그림. 그림이 아니면 nil.
    ///
    /// - 세로로 찍은 사진은 바로 선다(방향 값이 그림에 구워진다).
    /// - **키우지 않는다** — 상한보다 작은 사진은 제 크기다.
    /// - 투명한 곳은 흰 바탕이 된다 — 올라갈 JPEG 와 화면의 미리보기가 같은 모습이다.
    /// - 새로 그린 그림이라 원본의 EXIF(위치)가 없다.
    static func image(from file: Data, longest: CGFloat) -> UIImage? {
        // 여기서는 풀지 않는다 — 크기만 읽는다.
        let lazy = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(file as CFData, lazy),
              let pixels = pixelSize(of: source)
        else { return nil }
        // 상한이 원본보다 크면 원본 크기를 준다 — ImageIO 가 늘리지 않게 우리가 먼저 자른다.
        let target = min(longest, max(pixels.width, pixels.height))
        let options = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: max(1, Int(target.rounded())),
            kCGImageSourceShouldCacheImmediately: true,
        ] as CFDictionary
        // 여러 장이 든 파일(HEIC 연사·라이브 포토의 묶음)은 **대표 그림**을 푼다 — 0번이 대표가 아닐 수 있다.
        let primary = CGImageSourceGetPrimaryImageIndex(source)
        guard let small = CGImageSourceCreateThumbnailAtIndex(source, primary, options) else { return nil }
        return redraw(UIImage(cgImage: small), longest: longest)
    }

    /// 네모 칸을 채울 작은 미리보기 — 짧은 변이 `side` 쯤. 같은 길로 풀어, 큰 그림을 쥐고 있지 않는다.
    static func preview(from file: Data, side: CGFloat) -> UIImage? {
        guard let source = CGImageSourceCreateWithData(file as CFData, nil),
              let pixels = pixelSize(of: source)
        else { return nil }
        return image(from: file, longest: previewLongest(width: pixels.width, height: pixels.height, side: side))
    }

    /// 미리보기의 긴 변 — 짧은 변이 `side` 가 되게. 파노라마처럼 길쭉하면 네 배에서 끊는다(칸은 가운데만 보인다).
    static func previewLongest(width: CGFloat, height: CGFloat, side: CGFloat) -> CGFloat {
        let shortest = min(width, height)
        guard shortest >= 1, side >= 1 else { return max(1, side) }
        return min(side * max(width, height) / shortest, side * 4).rounded()
    }

    private static func pixelSize(of source: CGImageSource) -> CGSize? {
        let primary = CGImageSourceGetPrimaryImageIndex(source)
        guard let found = CGImageSourceCopyPropertiesAtIndex(source, primary, nil) as? [CFString: Any],
              let width = (found[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let height = (found[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
              width >= 1, height >= 1
        else { return nil }
        return CGSize(width: width, height: height)
    }

    private static func redraw(_ photo: UIImage, longest: CGFloat) -> UIImage {
        // `UIImage.size` 는 점(pt)이다 — 배율을 곱해 픽셀로 셈한다(화면 캡처는 3 배다).
        let size = size(
            width: photo.size.width * photo.scale, height: photo.size.height * photo.scale,
            longest: longest
        )
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        // JPEG 에는 투명이 없다 — 흰 바탕을 깔지 않으면 투명한 PNG 가 검게 나온다.
        format.opaque = true
        format.preferredRange = .standard
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.white.setFill()
            context.fill(CGRect(origin: .zero, size: size))
            photo.draw(in: CGRect(origin: .zero, size: size))
        }
    }
}
