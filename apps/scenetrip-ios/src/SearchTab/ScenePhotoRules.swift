import CoreGraphics
import SceneApiClient

/// 촬영 장면은 실제 씬 주소만 사용한다. 없는 주소는 확대할 사진이 아니다 (MZ2AZ-420).
enum ScenePhotoRules {
    static let minimumScale: CGFloat = 1
    static let maximumScale: CGFloat = 4
    static let doubleTapScale: CGFloat = 2

    static func photo(_ scene: SceneItem) -> GalleryPhoto? {
        guard let url = WebAddress.url(scene.sceneImageUrl) else { return nil }
        // 공개·서명 주소 어느 쪽이 와도 디스크에 남기지 않는다.
        return GalleryPhoto(key: "s:\(scene.contentId):\(url.absoluteString)", url: url.absoluteString)
    }

    static func scale(_ value: CGFloat) -> CGFloat {
        guard value.isFinite else { return minimumScale }
        return min(max(value, minimumScale), maximumScale)
    }

    static func fittedSize(image: CGSize, viewport: CGSize) -> CGSize {
        guard image.width.isFinite, image.height.isFinite,
              viewport.width.isFinite, viewport.height.isFinite,
              image.width > 0, image.height > 0, viewport.width > 0, viewport.height > 0
        else { return .zero }
        let ratio = min(viewport.width / image.width, viewport.height / image.height)
        return CGSize(width: image.width * ratio, height: image.height * ratio)
    }
}
