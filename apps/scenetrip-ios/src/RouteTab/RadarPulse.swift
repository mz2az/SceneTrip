import NMapsMap
import QuartzCore
import UIKit

/// 현재 위치를 알리는 **레이더 파문** (2026-08-24).
///
/// 가운데 점에서 원이 퍼져 나가며 옅어진다. 심장 박동처럼 주기적으로 반복한다.
///
/// ## 왜 정지된 점으로는 부족한가
///
/// 지도에는 점이 많다 — 촬영지 핀, 편의시설 점, 목적지. 그중에서 **지금 내가 선
/// 자리**만은 한눈에 찾아야 한다. 움직이는 것은 눈이 먼저 잡는다.
///
/// ## 왜 마커 이미지를 바꿔 끼우지 않나
///
/// 네이버 지도의 마커(`NMFMarker`)는 그림 한 장이라 스스로 움직이지 못한다. 여러
/// 장을 만들어 타이머로 갈아 끼울 수는 있지만, 그러면 **매 프레임 이미지를 굽고
/// 지도에 다시 올리게 된다** — 지도를 미는 동안에도 계속 그러므로 값이 비싸다.
///
/// 그래서 지도 **위에** 얹는다. `CALayer` 애니메이션은 GPU 가 돌리므로 지도가
/// 무엇을 하든 부담이 없다. 대신 지도가 움직일 때 화면 좌표가 달라지므로,
/// 자리를 다시 잡아 주는 쪽(`RouteNavMapView`)이 카메라가 바뀔 때마다 `place` 를
/// 부른다.
@MainActor
final class RadarPulse: UIView {
    /// 파문 한 겹이 퍼지는 데 걸리는 시간.
    private static let period: CFTimeInterval = 2.2

    /// 몇 겹이 동시에 퍼지는가. 하나면 사이가 비어 심장 박동으로 안 읽힌다.
    private static let waves = 3

    /// 가장 크게 퍼졌을 때의 지름(pt).
    private static let spread: CGFloat = 88

    private let core = CAShapeLayer()
    private var rings: [CAShapeLayer] = []

    init(tint: UIColor) {
        super.init(frame: CGRect(x: 0, y: 0, width: Self.spread, height: Self.spread))
        // **지도를 가로막지 않는다.** 이 뷰는 보여 주기만 하고 손짓은 전부 아래
        // 지도로 흘려보낸다 — 안 그러면 파문 위에서 지도를 못 민다.
        isUserInteractionEnabled = false

        let middle = CGPoint(x: Self.spread / 2, y: Self.spread / 2)

        for index in 0 ..< Self.waves {
            let ring = CAShapeLayer()
            ring.path = UIBezierPath(
                arcCenter: middle, radius: Self.spread / 2,
                startAngle: 0, endAngle: .pi * 2, clockwise: true
            ).cgPath
            ring.fillColor = tint.withAlphaComponent(0.22).cgColor
            ring.opacity = 0
            layer.addSublayer(ring)
            rings.append(ring)

            // 겹마다 시작을 어긋나게 해 끊이지 않고 이어지게 한다.
            let offset = Self.period / Double(Self.waves) * Double(index)
            animate(ring, delay: offset)
        }

        // 가운데 점. 파문이 사라져도 **내 자리는 늘 보여야 한다.**
        let dotSize: CGFloat = 18
        core.path = UIBezierPath(ovalIn: CGRect(
            x: middle.x - dotSize / 2, y: middle.y - dotSize / 2,
            width: dotSize, height: dotSize
        )).cgPath
        core.fillColor = tint.cgColor
        core.strokeColor = UIColor.white.cgColor
        core.lineWidth = 3.5
        core.shadowColor = UIColor.black.cgColor
        core.shadowOpacity = 0.28
        core.shadowRadius = 2
        core.shadowOffset = .zero
        layer.addSublayer(core)
    }

    @available(*, unavailable)
    required init?(coder _: NSCoder) {
        fatalError("스토리보드를 쓰지 않는다")
    }

    /// 작고 진하게 시작해 커지면서 옅어진다.
    private func animate(_ ring: CAShapeLayer, delay: CFTimeInterval) {
        let grow = CABasicAnimation(keyPath: "transform.scale")
        // 0 에서 시작하면 첫 프레임이 점 하나라 「퍼진다」로 안 보인다.
        grow.fromValue = 0.18
        grow.toValue = 1.0

        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 0.9
        fade.toValue = 0.0
        // 끝에서 빨리 옅어져야 「사라졌다」로 보인다. 선형으로 두면 계속 남아 있는
        // 것처럼 보인다.
        fade.timingFunction = CAMediaTimingFunction(name: .easeOut)

        let group = CAAnimationGroup()
        group.animations = [grow, fade]
        group.duration = Self.period
        group.repeatCount = .infinity
        group.beginTime = CACurrentMediaTime() + delay
        // 원의 가운데를 기준으로 커져야 한다. 기본값(좌상단)이면 오른쪽 아래로 번진다.
        ring.anchorPoint = CGPoint(x: 0.5, y: 0.5)
        ring.frame = bounds
        ring.add(group, forKey: "pulse")
    }

    /// 지도 위 좌표에 맞춰 자리를 잡는다. **카메라가 움직일 때마다 불러야 한다.**
    func place(at point: CGPoint) {
        center = point
    }

    /// 앱이 뒤로 갔다 오면 `CALayer` 애니메이션이 멎는다. 다시 걸어 준다.
    func restartIfNeeded() {
        guard rings.first?.animation(forKey: "pulse") == nil else { return }
        for (index, ring) in rings.enumerated() {
            animate(ring, delay: Self.period / Double(Self.waves) * Double(index))
        }
    }
}
