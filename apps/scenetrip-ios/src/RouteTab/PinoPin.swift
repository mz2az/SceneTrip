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

    /// 화면에 그릴 크기(pt). 번호 핀(38×50)과 같은 급이다 — 마스코트라고 크면
    /// 핀들 사이에서 저 혼자 다른 물건이 된다(2026-08-28 사용자 지적).
    static let size = CGSize(width: 40, height: 52)

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
        // 굽지 못한 순간에만 옛 핀으로 떨어지되 **캐시에 넣지 않는다** — 첫
        // 렌더가 실패한 채 캐시되면 파란 민 핀이 영영 남는다(2026-08-28).
        guard let baked = renderer.uiImage else { return PinImage.numbered(nil) }
        let image = NMFOverlayImage(image: baked)
        cached[tint] = image
        return image
    }

    /// 가이드가 추천한 곳의 **작은 점.**
    ///
    /// 처음에는 추천 전부를 파란 고양이로 그렸는데, 열다섯 마리가 몰리면 서로
    /// 겹쳐 지도가 고양이밭이 됐다(2026-08-27 사용자 결정). 추천은 점으로 낮추고
    /// **고른 하나만** 고양이가 된다 — 점 사이에서 고양이 하나가 곧 「지금 보는 곳」이다.
    /// 갈래마다 색이 다르다(`RoutePoiTone`) — 음식점·카페=빨강, 숙소=초록,
    /// 교통=노랑, 명소=파랑(2026-08-27 사용자 확정).
    ///
    /// 모양은 **갈래 색 원 안의 흰 업종 아이콘**이다(2026-09-01, `RoutePoiGlyph`).
    /// 그 전엔 발바닥이었는데, 색만으로는 카페와 식당·지하철과 공항이 안 갈렸다
    /// (사용자 요청). 글리프가 붙으니 점이 조금 커졌다(20→26 pt) — 12 pt 글리프가
    /// 알아볼 수 있는 최소 크기다.
    static func guideDot(for place: RouteGuide.Place) -> NMFOverlayImage {
        guideDot(group: place.poiGroup, symbol: place.poiSymbol)
    }

    static func guideDot(group: RoutePoiGroup, symbol: String) -> NMFOverlayImage {
        let key = DotKey(group: group, symbol: symbol)
        if let found = cachedDots[key] {
            return found
        }
        let size: CGFloat = 26
        let image = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { context in
            let tone = UIColor(RoutePoiTone.of(group))

            // 원 + 흰 테두리. 그림자가 지도에서 경계를 세운다.
            context.cgContext.setShadow(
                offset: .zero, blur: 1.8, color: UIColor.black.withAlphaComponent(0.3).cgColor
            )
            let circle = UIBezierPath(ovalIn: CGRect(x: 2, y: 2, width: size - 4, height: size - 4))
            tone.setFill()
            circle.fill()
            UIColor.white.setStroke()
            circle.lineWidth = 1.5
            circle.stroke()
            context.cgContext.setShadow(offset: .zero, blur: 0, color: nil)

            // 흰 글리프를 가운데에. 심볼이 없는 이름이면(OS 가 낮거나 오타) 점만 남긴다.
            let config = UIImage.SymbolConfiguration(pointSize: 12, weight: .bold)
            guard let glyph = UIImage(systemName: symbol, withConfiguration: config)?
                .withTintColor(.white, renderingMode: .alwaysOriginal)
            else { return }
            let box: CGFloat = 14
            let scale = min(box / glyph.size.width, box / glyph.size.height)
            let drawn = CGSize(width: glyph.size.width * scale, height: glyph.size.height * scale)
            glyph.draw(in: CGRect(
                x: (size - drawn.width) / 2, y: (size - drawn.height) / 2,
                width: drawn.width, height: drawn.height
            ))
        }
        let overlay = NMFOverlayImage(image: image)
        cachedDots[key] = overlay
        return overlay
    }

    /// 방문한 성지의 **발바닥 배지** — 번호 핀 옆에 붙는다(2026-09-02, 여행 모드).
    /// 스탬프 연출(`PawStampOverlay`)이 끝난 뒤에도 지도에 「찍혔다」가 남아야 한다.
    static func pawBadge() -> NMFOverlayImage {
        if let cachedPawBadge {
            return cachedPawBadge
        }
        let renderer = ImageRenderer(content:
            ZStack {
                Circle().fill(LinearGradient(
                    colors: [Color(PinImage.light), Color(PinImage.deep)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                ))
                Circle().stroke(.white, lineWidth: 2)
                PawShape().fill(.white).frame(width: 17, height: 17).rotationEffect(.degrees(-12))
            }
            .frame(width: 28, height: 28)
            .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            .padding(3))
        renderer.scale = UIScreen.main.scale
        guard let baked = renderer.uiImage else { return PinImage.numbered(nil) }
        let image = NMFOverlayImage(image: baked)
        cachedPawBadge = image
        return image
    }

    private static var cachedPawBadge: NMFOverlayImage?

    /// 다녀온 성지의 **발바닥 핀** — 번호 핀을 **통째로 갈음한다**(2026-09-03, 계획 trip-mode.md
    /// §8). 배지는 번호 옆에 붙는 표시였는데, 도착한 자리는 「N번」이 아니라 「밟고 간 자리」다.
    /// 자리 위에 얹는다(anchor 가운데).
    static func pawPin() -> NMFOverlayImage {
        if let cachedPawPin {
            return cachedPawPin
        }
        let renderer = ImageRenderer(content:
            ZStack {
                Circle().fill(LinearGradient(
                    colors: [Color(PinImage.light), Color(PinImage.deep)],
                    startPoint: .topLeading, endPoint: .bottomTrailing
                ))
                Circle().stroke(.white, lineWidth: 2.5)
                PawShape().fill(.white).frame(width: 24, height: 24).rotationEffect(.degrees(-12))
            }
            .frame(width: 40, height: 40)
            .shadow(color: .black.opacity(0.28), radius: 3, y: 1.5)
            .padding(4))
        renderer.scale = UIScreen.main.scale
        guard let baked = renderer.uiImage else { return PinImage.numbered(nil) }
        let image = NMFOverlayImage(image: baked)
        cachedPawPin = image
        return image
    }

    private static var cachedPawPin: NMFOverlayImage?

    /// 지나온 자리의 **발자국** — 황금색 반투명 신발 자국(2026-09-04 사용자 요청: 해태 발바닥이
    /// 아니라 사람 발자국, 흐리게). 진행 방향으로 돌려 찍는다(`NMFMarker.angle`).
    static func footprint() -> NMFOverlayImage {
        if let cachedFootprint {
            return cachedFootprint
        }
        let size: CGFloat = 17
        let image = UIGraphicsImageRenderer(size: CGSize(width: size, height: size)).image { _ in
            let config = UIImage.SymbolConfiguration(pointSize: 13, weight: .semibold)
            guard let glyph = UIImage(systemName: "shoeprints.fill", withConfiguration: config)?
                .withTintColor(UIColor(red: 0.85, green: 0.65, blue: 0.13, alpha: 0.5), renderingMode: .alwaysOriginal)
            else { return }
            let scale = min(size / glyph.size.width, size / glyph.size.height)
            let drawn = CGSize(width: glyph.size.width * scale, height: glyph.size.height * scale)
            glyph.draw(in: CGRect(x: (size - drawn.width) / 2, y: (size - drawn.height) / 2, width: drawn.width, height: drawn.height))
        }
        let overlay = NMFOverlayImage(image: image)
        cachedFootprint = overlay
        return overlay
    }

    private static var cachedFootprint: NMFOverlayImage?

    /// 추천·배경 점의 **이름표 규칙** (2026-09-02).
    ///
    /// 이름은 **크게 확대했을 때만** 단다 — 추천은 줌 17, 배경은 18 부터. 앞서 14·16
    /// 에서 열다섯 이름을 한꺼번에 띄웠더니 글자가 겹쳐 지도가 글자밭이 됐다(사용자
    /// 지적). 확대해도 겹치는 이름표는 SDK 가 하나만 남긴다(`isHideCollidedCaptions`).
    /// **고른 점은 줌과 무관하게** 이름을 단다 — 눌렀으면 그게 어딘지는 봐야 한다.
    static func caption(_ marker: NMFMarker, name: String, picked: Bool, ambient: Bool) {
        marker.captionText = name
        marker.captionMinZoom = picked ? 0 : (ambient ? 18 : 17)
        marker.isHideCollidedCaptions = !picked
    }

    private struct DotKey: Hashable {
        let group: RoutePoiGroup
        let symbol: String
    }

    private static var cachedDots: [DotKey: NMFOverlayImage] = [:]

    /// 지도 위에서는 흰 테두리만으로는 배경과 안 갈린다. **그림자를 깔아 띄운다.**
    private static func body(_ tint: Tint) -> some View {
        // 지도 핀 = 핀 테두리에 앞발을 얹고 **빼꼼 내다보는 해태**
        // (2026-08-28 사용자 선택 — 얼굴만 든 밋밋한 판 대신). 튜토리얼의
        // 「데려간다」 장과 같은 그림이라 핀과 마스코트가 한 몸으로 읽힌다.
        // 고른 것은 색을 못 바꾸는 대신(그림이라) 빨간 글로우를 두른다.
        // 헤일로는 마커에 굽지 않는다 — 심장박동처럼 **움직여야** 해서 지도 위에
        // 얹는 `HaloPulse` 가 맡는다(2026-08-28). 여기는 몸 가장자리 글로우만.
        Image("haetae-pinhold")
            .resizable()
            .scaledToFit()
            .frame(width: size.width * scale(tint))
            .shadow(
                color: (tint == .picked
                    ? Color(red: 0.89, green: 0.16, blue: 0.20)
                    : Color(PinImage.deep)).opacity(0.7),
                radius: 3
            )
            .shadow(color: .black.opacity(0.25), radius: 3, y: 2)
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
