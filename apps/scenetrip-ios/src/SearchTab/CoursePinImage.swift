import NMapsMap
import UIKit

/// 검색 탭 지도의 코스 번호 핀 그림 (MZ2AZ-547 G1·E11, 웹 `protoPoi.tsx` 의 `protoNumberedPinSvg`).
///
/// 보라 단색 동그라미(반지름 13, 흰 테두리 2)에 흰 굵은 번호, 아래 짧은 꼬리 — 크기·기준점·글자 크기는
/// `CoursePinShape` 가 정한다. 번호가 없으면(검색 결과에 없는 장소를 열어 덧붙인 핀) 번호 대신 흰 점.
/// 고른 핀은 그림을 통째로 `CoursePinShape.selectedScale` 배로 **한 번만** 굽는다 — 마커 쪽에서 다시 키우지 않는다.
/// 같은 그림은 캐시로 다시 쓴다(타이핑마다 수십 개를 다시 굽지 않게).
enum CoursePinImage {
    private static var cache: [String: NMFOverlayImage] = [:]

    /// 시그니처 보라(`#5c4ad6`) — 탭바의 고른 탭과 같은 색.
    static let purple = UIColor(red: 0x5C / 255, green: 0x4A / 255, blue: 0xD6 / 255, alpha: 1)

    static func image(number: Int?, selected: Bool) -> NMFOverlayImage {
        let key = "\(number ?? -1)" + (selected ? "s" : "")
        if let cached = cache[key] {
            return cached
        }
        let scale = selected ? CoursePinShape.selectedScale : 1
        let image = UIGraphicsImageRenderer(size: CoursePinShape.size(selected: selected)).image { ctx in
            let cg = ctx.cgContext
            // 30×38 좌표로 그리고 통째로 키운다 — 꼬리 끝의 비율 자리는 그대로다.
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
            purple.setFill()
            head.fill()
            head.stroke()

            let seam = UIBezierPath()
            seam.move(to: CGPoint(x: 11, y: 25.5))
            seam.addLine(to: CGPoint(x: 19, y: 25.5))
            seam.addLine(to: CGPoint(x: 15, y: 34))
            seam.close()
            purple.setFill()
            seam.fill()
            cg.endTransparencyLayer()

            guard let number else {
                UIColor.white.setFill()
                UIBezierPath(arcCenter: CGPoint(x: 15, y: 14), radius: 4.5, startAngle: 0, endAngle: .pi * 2, clockwise: true).fill()
                return
            }
            let text = "\(number)"
            let attributes: [NSAttributedString.Key: Any] = [
                .font: UIFont.systemFont(ofSize: CoursePinShape.fontSize(number), weight: .heavy),
                .foregroundColor: UIColor.white,
            ]
            let size = text.size(withAttributes: attributes)
            text.draw(at: CGPoint(x: 15 - size.width / 2, y: 14.5 - size.height / 2), withAttributes: attributes)
        }
        let overlay = NMFOverlayImage(image: image)
        cache[key] = overlay
        return overlay
    }
}
