import SwiftUI
import UIKit

// 피노를 이루는 도형. `PinoMascot` 이 좌표만 넘기면 되도록 여기 모아 둔다 —
// 그리는 일과 도형을 한 파일에 두었더니 400줄을 넘었다.

/// 핀의 물방울.
///
/// **`UIBezierPath` 로 만든다.** SwiftUI `Path.addArc` 와 `UIBezierPath` 는
/// `clockwise` 의 뜻이 서로 반대라, 옮겨 적으면 물방울이 뒤집힌 채로 나올 여지가 있다.
/// 이미 지도에서 돌고 있는 `PinImage.numbered` 와 **같은 식**을 쓰면 그 여지가 없다.
struct PinoTeardrop: Shape {
    func path(in _: CGRect) -> Path {
        let head = CGPoint(x: 60, y: 54)
        let bezier = UIBezierPath(
            arcCenter: head,
            radius: 42,
            startAngle: .pi * 0.75,
            endAngle: .pi * 0.25,
            clockwise: true
        )
        bezier.addLine(to: CGPoint(x: 60, y: 141))
        bezier.close()
        return Path(bezier.cgPath)
    }
}

struct PinoTriangle: Shape {
    let points: [CGPoint]

    func path(in _: CGRect) -> Path {
        var path = Path()
        guard let first = points.first else { return path }
        path.move(to: first)
        for point in points.dropFirst() {
            path.addLine(to: point)
        }
        path.closeSubpath()
        return path
    }
}

struct PinoDot: Shape {
    let center: CGPoint
    let radius: CGFloat

    func path(in _: CGRect) -> Path {
        Path(ellipseIn: CGRect(
            x: center.x - radius, y: center.y - radius,
            width: radius * 2, height: radius * 2
        ))
    }
}

struct PinoOval: Shape {
    let center: CGPoint
    let radii: CGSize

    func path(in _: CGRect) -> Path {
        Path(ellipseIn: CGRect(
            x: center.x - radii.width, y: center.y - radii.height,
            width: radii.width * 2, height: radii.height * 2
        ))
    }
}

struct PinoCurve: Shape {
    let from: CGPoint
    let control1: CGPoint
    let control2: CGPoint
    let end: CGPoint

    func path(in _: CGRect) -> Path {
        var path = Path()
        path.move(to: from)
        path.addCurve(to: end, control1: control1, control2: control2)
        return path
    }
}

struct PinoSegments: Shape {
    let pairs: [(CGPoint, CGPoint)]

    func path(in _: CGRect) -> Path {
        var path = Path()
        for (start, end) in pairs {
            path.move(to: start)
            path.addLine(to: end)
        }
        return path
    }
}

/// 네 갈래 별. 「AI 가 짜 준다」를 말하는 표시로 앱의 다른 자리에서도 쓰는 모양이다.
struct PinoSparkle: Shape {
    let arm: CGFloat

    func path(in _: CGRect) -> Path {
        let waist = arm * 0.42
        var path = Path()
        path.move(to: .init(x: 0, y: -arm))
        path.addLine(to: .init(x: waist, y: -waist))
        path.addLine(to: .init(x: arm, y: 0))
        path.addLine(to: .init(x: waist, y: waist))
        path.addLine(to: .init(x: 0, y: arm))
        path.addLine(to: .init(x: -waist, y: waist))
        path.addLine(to: .init(x: -arm, y: 0))
        path.addLine(to: .init(x: -waist, y: -waist))
        path.closeSubpath()
        return path
    }
}
