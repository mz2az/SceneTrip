import NMapsMap
import SwiftUI
import UIKit

/// 지도에 꽂는 **피노 핀** (2026-08-24).
///
/// ## 왜 만들었나
///
/// 마스코트를 만들어 놓고 정작 지도에는 옛 물방울 핀이 그대로 있었다 —
/// *"우리 고양이 마스코트 실컷 만들어놓고 왜 모양은 그대로야?"* (사용자 지적).
///
/// 피노는 애초에 이 핀에서 나왔다(`PinoMascot` 머리말 — 물방울에 귀·얼굴·꼬리를
/// 붙인 것). 그러니 **되돌아가는 것에 가깝다.**
///
/// ## 어디에 쓰고 어디에 안 쓰나
///
/// **목적지 하나에만 쓴다.** 코스 목록의 번호 핀(`PinImage.numbered`)은 그대로 둔다 —
/// 거기서는 ①②③ 이 목록과 지도를 잇는 유일한 끈이라 얼굴이 그 자리를 차지하면
/// 무엇과 무엇이 짝인지 알 수 없다. 목적지는 하나뿐이라 번호가 필요 없고, 그래서
/// 얼굴을 넣을 자리가 있다.
///
/// ## 크기
///
/// 번호 핀(38×50)보다 크다. 같은 크기로 줄이면 얼굴이 10 px 도 안 돼 뭉개진다 —
/// 고양이인지 알아볼 수 없으면 넣는 뜻이 없다.
@MainActor
enum PinoPin {
    /// 한 번 구워 두고 다시 쓴다. 지도가 다시 그릴 때마다 굽으면 편집 중에 버벅인다.
    /// 색깔별로 따로 담는다.
    private static var cached: [Tint: NMFOverlayImage] = [:]

    /// 화면에 그릴 크기(pt). 핀 끝이 좌표를 가리키도록 아래쪽 가운데를 기준으로 앉힌다.
    static let size = CGSize(width: 52, height: 68)

    /// 핀 색.
    enum Tint: Hashable {
        /// 평소 — 지도 핀과 같은 하늘→보라.
        case normal
        /// **지금 고른 곳.** 목록에서 누른 장소를 지도에서 바로 찾을 수 있어야 한다.
        /// 파랑·보라 계열 사이에서 빨강이 가장 잘 튄다.
        case picked
    }

    /// 고른 핀은 **더 크다.** 색만 바꾸면 핀이 빽빽할 때 어느 것이 골라진 것인지
    /// 한눈에 안 들어온다 — 프로토타입도 크기를 함께 키운다.
    private static func scale(_ tint: Tint) -> CGFloat {
        tint == .picked ? 1.35 : 1
    }

    static func marker(_ tint: Tint = .normal) -> NMFOverlayImage {
        if let found = cached[tint] {
            return found
        }
        let renderer = ImageRenderer(content: body(tint))
        renderer.scale = UIScreen.main.scale
        // 굽지 못하면 옛 핀으로 떨어진다. 마스코트가 없다고 지도가 비면 안 된다.
        let image = renderer.uiImage.map(NMFOverlayImage.init) ?? PinImage.numbered(nil)
        cached[tint] = image
        return image
    }

    /// 가이드가 추천한 곳의 **빨간 점.**
    ///
    /// 처음에는 추천 전부를 파란 고양이로 그렸는데, 열다섯 마리가 몰리면 서로
    /// 겹쳐 지도가 고양이밭이 됐다(2026-08-27 사용자 결정). 추천은 점으로 낮추고
    /// **고른 하나만** 고양이가 된다 — 점 사이에서 고양이 하나가 곧 「지금 보는 곳」이다.
    /// 갈래마다 색이 다르다(`RoutePoiTone`) — 음식점·카페=빨강, 숙소=초록,
    /// 교통=노랑, 명소=파랑(2026-08-27 사용자 확정).
    static func guideDot(_ group: RoutePoiGroup) -> NMFOverlayImage {
        if let found = cachedDots[group] {
            return found
        }
        let size: CGFloat = 16
        let image = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { context in
            let bounds = CGRect(x: 0, y: 0, width: size, height: size)
            context.cgContext.setShadow(
                offset: .zero, blur: 2, color: UIColor.black.withAlphaComponent(0.3).cgColor
            )
            UIColor.white.setFill()
            UIBezierPath(ovalIn: bounds.insetBy(dx: 0.5, dy: 0.5)).fill()
            context.cgContext.setShadow(offset: .zero, blur: 0, color: nil)
            UIColor(RoutePoiTone.of(group)).setFill()
            UIBezierPath(ovalIn: bounds.insetBy(dx: 3, dy: 3)).fill()
        }
        let overlay = NMFOverlayImage(image: image)
        cachedDots[group] = overlay
        return overlay
    }

    private static var cachedDots: [RoutePoiGroup: NMFOverlayImage] = [:]

    /// 지도 위에서는 흰 테두리만으로는 배경과 안 갈린다. **그림자를 깔아 띄운다.**
    private static func body(_ tint: Tint) -> some View {
        // 지도 핀 = 물방울 핀 안에 든 진도(jindo-pinbody). 고른 것은 색을 못
        // 바꾸는 대신(그림이라) **빨간 테두리 물방울**을 뒤에 깔고 키운다.
        ZStack {
            if tint == .picked {
                PinRing()
                    .stroke(
                        Color(red: 0.89, green: 0.16, blue: 0.20),
                        style: .init(lineWidth: 4.5, lineJoin: .round)
                    )
                    .frame(
                        width: size.width * scale(tint) * 0.72,
                        height: size.height * scale(tint) * 0.98
                    )
            }
            Image("jindo-pinbody")
                .resizable()
                .scaledToFit()
                .frame(width: size.width * scale(tint))
        }
        .shadow(color: .black.opacity(0.28), radius: 3, y: 2)
        // 그림자가 잘리지 않게 여백을 준다.
        .padding(4)
    }
}

/// 물방울(핀) 외곽 — 고른 핀의 빨간 테. 그림 크기에 맞춰 그려야 해서
/// `rect` 기준으로 만든다(옛 `PinoTeardrop` 은 좌표가 박혀 있어 못 쓴다).
private struct PinRing: Shape {
    func path(in rect: CGRect) -> Path {
        let radius = rect.width / 2
        let center = CGPoint(x: rect.midX, y: rect.minY + radius)
        let bezier = UIBezierPath(
            arcCenter: center, radius: radius,
            startAngle: .pi * 0.75, endAngle: .pi * 0.25, clockwise: true
        )
        bezier.addLine(to: CGPoint(x: rect.midX, y: rect.maxY))
        bezier.close()
        return Path(bezier.cgPath)
    }
}
