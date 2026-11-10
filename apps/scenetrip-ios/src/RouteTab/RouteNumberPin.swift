import NMapsMap
import UIKit

/// 계획 화면 지도의 코스 번호 핀 (MZ2AZ-547 E11, 웹 `proto/protoPoi.tsx` 의 `protoNumberedPinSvg`).
///
/// 보라 단색 동그라미(반지름 13, 흰 테두리 2)에 흰 굵은 번호, 아래 짧은 꼬리. 30×38, 꼬리 끝 (15, 37).
/// 예전 핀(`PinImage.numbered` — 하늘→보라 물방울 속 흰 원에 보라 번호)은 크고 겹이 많았다(웹 2026-11-08 사용자 지적).
///
/// 검색 탭의 보관함 핀(P9 `CoursePinImage`·`CoursePinShape`, PR #429)과 **같은 그림**이다. 그 PR 이 아직 이 갈래에 없어
/// 여기 따로 둔다 — 둘 다 main 에 들어가면 하나로 합친다(계획 ios-web-parity.md P4 줄).
enum RouteNumberPin {
    static let size = CGSize(width: 30, height: 38)
    /// 꼬리 끝 — 지도 좌표가 닿는 점.
    static let tip = CGPoint(x: 15, y: 37)
    /// 마커 기준점(0…1).
    static let anchor = CGPoint(x: tip.x / size.width, y: tip.y / size.height)
    /// 시그니처 보라(`#5c4ad6`) — 떠 있는 탭바의 고른 탭과 같은 색.
    static let purple = UIColor(red: 0x5C / 255, green: 0x4A / 255, blue: 0xD6 / 255, alpha: 1)

    /// 고른 핀의 배율 — 같은 그림을 크게(2026-11-10 사용자 결정: 해태·파란 고리 대신 「핀 크기만 키워 줘」).
    static let largeScale: CGFloat = 1.4

    private struct Key: Hashable {
        let number: Int?
        let large: Bool
    }

    private static var cache: [Key: NMFOverlayImage] = [:]

    /// 번호 글자 크기 — 한 자리 13, 두 자리 11.5, 세 자리 이상 9.
    static func fontSize(_ number: Int) -> CGFloat {
        number < 10 ? 13 : number < 100 ? 11.5 : 9
    }

    /// 번호 없는 같은 핀 — 코스 밖의 한 곳(담을까 보는 곳, 길 안내에서 갈아탄 목적지, 계획 §10-1).
    static func plain(large: Bool = false) -> NMFOverlayImage {
        render(nil, large: large)
    }

    /// `large` 면 같은 그림을 `largeScale` 배로. 기준점(`anchor`)은 비율이라 그대로 꼬리 끝이다.
    static func image(_ number: Int, large: Bool = false) -> NMFOverlayImage {
        render(number, large: large)
    }

    private static func render(_ number: Int?, large: Bool) -> NMFOverlayImage {
        let key = Key(number: number, large: large)
        if let cached = cache[key] {
            return cached
        }
        let scale = large ? largeScale : 1
        let canvas = CGSize(width: size.width * scale, height: size.height * scale)
        let image = UIGraphicsImageRenderer(size: canvas).image { ctx in
            let cg = ctx.cgContext
            cg.scaleBy(x: scale, y: scale)
            cg.setShadow(offset: CGSize(width: 0, height: 2), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
            cg.beginTransparencyLayer(auxiliaryInfo: nil)
            // 꼬리(흰 테두리) → 동그라미(흰 테두리) → 꼬리 안쪽을 다시 칠해 둘 사이의 흰 줄을 지운다(웹 SVG 와 같은 순서).
            let tail = UIBezierPath()
            tail.move(to: CGPoint(x: 10, y: 25))
            tail.addLine(to: CGPoint(x: 15, y: 37))
            tail.addLine(to: CGPoint(x: 20, y: 25))
            tail.close()
            tail.lineJoinStyle = .round
            tail.lineWidth = 2
            purple.setFill()
            tail.fill()
            UIColor.white.setStroke()
            tail.stroke()

            let head = UIBezierPath(arcCenter: CGPoint(x: 15, y: 14), radius: 13, startAngle: 0, endAngle: .pi * 2, clockwise: true)
            head.lineWidth = 2
            head.fill()
            head.stroke()

            let seam = UIBezierPath()
            seam.move(to: CGPoint(x: 11, y: 25.5))
            seam.addLine(to: CGPoint(x: 19, y: 25.5))
            seam.addLine(to: CGPoint(x: 15, y: 34))
            seam.close()
            seam.fill()
            cg.endTransparencyLayer()

            guard let number else { return }
            let text = "\(number)"
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: fontSize(number), weight: .heavy),
                .foregroundColor: UIColor.white,
            ]
            let textSize = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: 15 - textSize.width / 2, y: 14.5 - textSize.height / 2), withAttributes: attributes)
        }
        let overlay = NMFOverlayImage(image: image)
        cache[key] = overlay
        return overlay
    }
}
