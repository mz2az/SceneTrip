import SwiftUI
import UIKit

/// 진도 — SceneTrip 마스코트. **진돗개**다 (2026-08-28, 고양이 피노에서 교체 —
/// 한국형 마스코트로 가자는 결정. 코드 이름 Pino 는 혈통이라 그대로 둔다).
///
/// 「집까지 데려다주는 충직한 길잡이」 — 진돗개의 귀소 본능이 길찾기 앱의 서사와
/// 맞물린다. 표식은 둘: **쫑긋 선 세모 귀**와 **등 위로 도르르 말린 꼬리**.
///
/// ## 새로 그린 그림이 아니다
///
/// 몸통은 **지도에 이미 꽂혀 있는 핀**이다. `PinImage.numbered` 의 물방울(머리 원
/// r14 @ (19,17), 꼬리 (19,45))을 세 배로 키우고 귀·얼굴·꼬리만 붙였다. 얼굴이 앉는
/// 흰 원은 지도에서 번호 ①②③ 가 앉던 그 배지와 같은 자리·같은 비율이다.
///
/// 그래서 검색 탭을 켜면 마스코트가 155개 흩뿌려져 있는 셈이 된다. 마스코트 따로,
/// 지도 따로가 아니다.
///
/// ## GIF 가 아니라 벡터인 이유
///
/// SwiftUI 에는 GIF 재생이 없다. `CGAnimateImageAtURLWithBlock`(ImageIO)으로 틀 수는
/// 있지만 **GIF 는 256색**이라 하늘→보라 그러데이션에 띠가 진다 — 우리 핀이 바로 그
/// 그러데이션이다. Lottie 는 `MODULE.bazel` 에 새 의존성이 붙는다.
///
/// 벡터로 그리면 셋 다 없다: 의존성 0, 용량 0, 어느 화면 배율에서도 선명하다.
/// 발표자료·스토어용 GIF 는 같은 그림을 웹 캔버스(`docs/product/canvas/brand/`)에서 뽑는다.
enum Pino {
    /// 모든 좌표가 이 안의 값이다. 웹 캔버스의 `viewBox="0 0 120 160"` 과 같다 —
    /// 두 벌이 어긋나면 캔버스에서 본 것과 앱이 달라진다.
    static let design = CGSize(width: 120, height: 160)

    /// 진돗개의 색 — 크림 흰 몸에 따뜻한 갈색 계열. 눈·코가 진해야 흰 얼굴에서
    /// 표정이 산다.
    static let blush = Color(red: 0.961, green: 0.788, blue: 0.659) // #F5C9A8 귀 속·볼
    static let eye = Color(red: 0.353, green: 0.275, blue: 0.204) // #5A4634 진갈색
    static let nose = Color(red: 0.227, green: 0.180, blue: 0.133) // #3A2E22 까만 코
    static let whisker = Color(red: 0.851, green: 0.769, blue: 0.659) // #D9C4A8 옅은 수염

    /// 몸통 색. 바탕에 따라 갈린다.
    ///
    /// **한 벌로는 안 된다.** 보라색 스플래시 위에서는 옅은 몸이 떠오르고, 흰 배경의
    /// 튜토리얼 위에서는 같은 옅은 몸이 묻힌다. 흰 바탕에서는 지도 핀이 실제로 쓰는
    /// 진한 그러데이션을 그대로 쓴다 — 「이 고양이가 그 핀이다」가 거기서 가장 잘 읽힌다.
    enum Tone {
        /// 진한 바탕 위(스플래시). 밝은 크림.
        case onDeep
        /// 흰 바탕 위. 같은 크림이되 **테두리가 황갈색**이다 — 흰 몸에 흰 테두리면
        /// 흰 바탕에서 통째로 사라진다(진돗개 교체 때 확인).
        case onLight
        /// 지금 고른 곳. 빨강 — 크림·파랑 사이에서 가장 잘 튄다.
        case picked

        var gradient: LinearGradient {
            let colors: [Color] = switch self {
            case .onDeep: [
                    Color(red: 1.0, green: 0.976, blue: 0.937), // #FFF9EF
                    Color(red: 0.953, green: 0.890, blue: 0.784), // #F3E3C8
                ]
            case .onLight: [
                    Color(red: 1.0, green: 0.965, blue: 0.910), // #FFF6E8
                    Color(red: 0.941, green: 0.851, blue: 0.722), // #F0D9B8
                ]
            case .picked: [
                    Color(red: 1.00, green: 0.45, blue: 0.42),
                    Color(red: 0.89, green: 0.16, blue: 0.20),
                ]
            }
            return LinearGradient(colors: colors, startPoint: .top, endPoint: .bottom)
        }

        /// 몸·귀·팔의 테두리 색. 흰 바탕에서만 황갈색으로 갈린다.
        var stroke: Color {
            switch self {
            case .onLight: Color(red: 0.784, green: 0.608, blue: 0.424) // #C89B6C
            case .onDeep, .picked: .white
            }
        }
    }

    /// 손에 든 것. 튜토리얼 넉 장이 한 포즈씩 쓴다.
    enum Pose {
        /// 아무것도 안 들었다. 스플래시.
        case plain
        /// 돋보기 — 찾는다.
        case magnifier
        /// 반짝임 + 웃는 눈 — 짜 준다.
        case sparkle
        /// 앞발로 가리킨다 — 데려간다.
        case paw
        /// 말풍선 + 다문 미소 — 거든다.
        case speech
    }
}

// MARK: - 마스코트

struct PinoMascot: View {
    var pose: Pino.Pose = .plain
    var tone: Pino.Tone = .onLight
    /// 폭. 높이는 4:3 비율로 따라온다 (120 × 160).
    var width: CGFloat = 180

    /// 프레임 바닥과 **핀 끝** 사이의 거리. 그림자를 발밑에 놓으려면 이만큼 올려야 한다.
    static func tipInset(width: CGFloat) -> CGFloat {
        (Pino.design.height - 141) * (width / Pino.design.width)
    }

    /// 숨쉬기 — 귀 씰룩, 꼬리 살랑, 눈 깜빡. 정지 그림이 필요하면 끈다.
    var isAlive = true

    @State private var blink = false
    @State private var wag = false
    @State private var earTwitch = false

    private var scale: CGFloat {
        width / Pino.design.width
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            tail
            ear(.left)
            ear(.right)
            torso
            face
            prop
        }
        .frame(width: Pino.design.width, height: Pino.design.height, alignment: .topLeading)
        // 소품이 몸 밖으로 나간다 (돋보기·말풍선). 잘리면 안 된다.
        .fixedSize()
        .scaleEffect(scale, anchor: .topLeading)
        // **`alignment: .topLeading` 을 빠뜨리면 안 된다.** `scaleEffect` 는 레이아웃
        // 크기를 바꾸지 않으므로 속은 여전히 120×160 이고, 기본값인 가운데 정렬은
        // 그것을 넓어진 프레임 한가운데에 놓는다. 그러면 좌상단 기준으로 커진 그림이
        // 프레임 밖 오른쪽 아래로 (width-120)/2 만큼 밀려 나간다 — 스플래시에서
        // 마스코트가 화면 중앙에서 48pt 벗어나 있던 것이 이 때문이었다(실측).
        .frame(
            width: width,
            height: width * Pino.design.height / Pino.design.width,
            alignment: .topLeading
        )
        .task(id: isAlive) { await breathe() }
    }

    // MARK: 부위

    private var torso: some View {
        PinoTeardrop()
            .fill(tone.gradient)
            .overlay(PinoTeardrop().stroke(tone.stroke, lineWidth: 3.5))
    }

    private enum Side { case left, right }

    /// 귀는 몸통보다 **먼저** 그린다 — 밑동이 몸에 가려야 붙어 보인다.
    @ViewBuilder
    private func ear(_ side: Side) -> some View {
        // 진돗개의 귀는 고양이보다 **크고 곧게 선다** — 실루엣의 절반이 귀다.
        let outer: [CGPoint] = side == .left
            ? [.init(x: 26, y: 41), .init(x: 24, y: 0), .init(x: 56, y: 19)]
            : [.init(x: 94, y: 41), .init(x: 96, y: 0), .init(x: 64, y: 19)]
        let inner: [CGPoint] = side == .left
            ? [.init(x: 32, y: 34), .init(x: 31, y: 11), .init(x: 48, y: 22)]
            : [.init(x: 88, y: 34), .init(x: 89, y: 11), .init(x: 72, y: 22)]
        let pivot = side == .left
            ? UnitPoint(x: 34 / 120, y: 34 / 160)
            : UnitPoint(x: 86 / 120, y: 34 / 160)

        ZStack(alignment: .topLeading) {
            PinoTriangle(points: outer)
                .fill(tone.gradient)
                .overlay(PinoTriangle(points: outer).stroke(tone.stroke, style: .init(lineWidth: 3, lineJoin: .round)))
            PinoTriangle(points: inner).fill(Pino.blush)
        }
        .rotationEffect(.degrees(earTwitch ? (side == .left ? -7 : 5) : 0), anchor: pivot)
    }

    /// 꼬리는 **소품 반대편**으로 간다.
    ///
    /// 처음에는 늘 오른쪽에 두었는데, 돋보기와 앞발이 같은 쪽이라 셋이 겹쳐 무엇이
    /// 무엇인지 알 수 없었다(시뮬레이터 실측). 꼬리 자리는 마스코트의 정체성이 아니고
    /// 소품 자리는 그 장이 말하려는 것이라, 양보하는 쪽은 꼬리다.
    private var tailOnLeft: Bool {
        pose == .magnifier || pose == .paw
    }

    private var tail: some View {
        // 등 위로 **도르르 말린 꼬리** — 진돗개의 표식이라 곡선이 하나 더 붙는다.
        let flip: (CGFloat) -> CGFloat = { tailOnLeft ? 120 - $0 : $0 }
        return ZStack(alignment: .topLeading) {
            PinoCurve(
                from: .init(x: flip(78), y: 104),
                control1: .init(x: flip(104), y: 108),
                control2: .init(x: flip(110), y: 80),
                end: .init(x: flip(93), y: 73)
            )
            .stroke(tone.gradient, style: .init(lineWidth: 10, lineCap: .round))
            PinoCurve(
                from: .init(x: flip(93), y: 73),
                control1: .init(x: flip(83), y: 69),
                control2: .init(x: flip(81), y: 79),
                end: .init(x: flip(89), y: 81)
            )
            .stroke(tone.gradient, style: .init(lineWidth: 8, lineCap: .round))
        }
        .rotationEffect(
            .degrees((wag ? 8 : -5) * (tailOnLeft ? -1 : 1)),
            anchor: UnitPoint(x: flip(80) / 120, y: 104 / 160)
        )
    }

    private var face: some View {
        ZStack(alignment: .topLeading) {
            // 얼굴 배지 = 핀의 번호 배지. 같은 중심, 같은 반지름 비율.
            PinoDot(center: .init(x: 60, y: 52), radius: 31).fill(.white)

            eyes
            noseAndMouth
            whiskers

            PinoDot(center: .init(x: 43.5, y: 58), radius: 4).fill(Pino.blush.opacity(0.55))
            PinoDot(center: .init(x: 76.5, y: 58), radius: 4).fill(Pino.blush.opacity(0.55))
        }
    }

    /// 「데려간다」 포즈에서는 가리키는 쪽을 본다 — 눈만 3 옮긴다.
    private var eyeShift: CGFloat {
        pose == .paw ? 3 : 0
    }

    @ViewBuilder
    private var eyes: some View {
        if pose == .sparkle {
            // 웃는 눈. 감은 눈이라 깜빡이지 않는다.
            PinoCurve(from: .init(x: 43, y: 47), control1: .init(x: 46, y: 42),
                      control2: .init(x: 52, y: 42), end: .init(x: 55, y: 47))
                .stroke(Pino.eye, style: .init(lineWidth: 2.4, lineCap: .round))
            PinoCurve(from: .init(x: 65, y: 47), control1: .init(x: 68, y: 42),
                      control2: .init(x: 74, y: 42), end: .init(x: 77, y: 47))
                .stroke(Pino.eye, style: .init(lineWidth: 2.4, lineCap: .round))
        } else {
            ZStack(alignment: .topLeading) {
                PinoOval(center: .init(x: 49 + eyeShift, y: 47), radii: .init(width: 4.2, height: 5.6)).fill(Pino.eye)
                PinoOval(center: .init(x: 71 + eyeShift, y: 47), radii: .init(width: 4.2, height: 5.6)).fill(Pino.eye)
                PinoDot(center: .init(x: 50.4 + eyeShift, y: 45), radius: 1.5).fill(.white)
                PinoDot(center: .init(x: 72.4 + eyeShift, y: 45), radius: 1.5).fill(.white)
            }
            // 눈높이를 축으로 눌러야 한다. 기본 anchor(.center)로 누르면 눈이
            // 화면 한가운데(y=80)로 끌려 내려간다.
            .scaleEffect(x: 1, y: blink ? 0.08 : 1, anchor: UnitPoint(x: 0.5, y: 47 / 160))
        }
    }

    @ViewBuilder
    private var noseAndMouth: some View {
        let nudge: CGFloat = pose == .paw ? 1.5 : 0
        // 까맣고 둥근 개 코. 세모(고양이)에서 바뀐 자리다.
        PinoOval(
            center: .init(x: 60 + nudge, y: 56.5),
            radii: .init(width: 5, height: 3.8)
        ).fill(Pino.nose)

        if pose == .speech {
            // 다문 미소. 말하는 중이라 입이 벌어져 있지 않다.
            PinoCurve(from: .init(x: 53, y: 62), control1: .init(x: 56, y: 68),
                      control2: .init(x: 64, y: 68), end: .init(x: 67, y: 62))
                .stroke(Pino.eye, style: .init(lineWidth: 2, lineCap: .round))
        } else {
            PinoCurve(from: .init(x: 60 + nudge, y: 60.3), control1: .init(x: 60 + nudge, y: 64.5),
                      control2: .init(x: 56 + nudge, y: 66), end: .init(x: 53.6 + nudge, y: 63.2))
                .stroke(Pino.eye, style: .init(lineWidth: 1.8, lineCap: .round))
            PinoCurve(from: .init(x: 60 + nudge, y: 60.3), control1: .init(x: 60 + nudge, y: 64.5),
                      control2: .init(x: 64 + nudge, y: 66), end: .init(x: 66.4 + nudge, y: 63.2))
                .stroke(Pino.eye, style: .init(lineWidth: 1.8, lineCap: .round))
        }
    }

    private var whiskers: some View {
        PinoSegments(pairs: [
            (.init(x: 42, y: 53), .init(x: 33, y: 51)),
            (.init(x: 42, y: 57), .init(x: 33.5, y: 58)),
            (.init(x: 78, y: 53), .init(x: 87, y: 51)),
            (.init(x: 78, y: 57), .init(x: 86.5, y: 58)),
        ])
        .stroke(Pino.whisker, style: .init(lineWidth: 1.5, lineCap: .round))
    }

    // MARK: 소품

    @ViewBuilder
    private var prop: some View {
        switch pose {
        case .plain:
            EmptyView()

        case .magnifier:
            ZStack(alignment: .topLeading) {
                PinoDot(center: .zero, radius: 17).fill(.white.opacity(0.6))
                PinoDot(center: .zero, radius: 17).stroke(Color(PinImage.deep), lineWidth: 4.5)
                PinoSegments(pairs: [(.init(x: 12, y: 12), .init(x: 24, y: 24))])
                    .stroke(Color(PinImage.deep), style: .init(lineWidth: 5.5, lineCap: .round))
            }
            .rotationEffect(.degrees(20))
            .offset(x: 90, y: 88)

        case .sparkle:
            PinoSparkle(arm: 8.4).fill(Color(red: 1, green: 0.83, blue: 0.15)).offset(x: 104, y: 16)
            PinoSparkle(arm: 5.6).fill(Color(red: 1, green: 0.83, blue: 0.15)).offset(x: 14, y: 70)

        case .paw:
            // 팔. **이것이 없으면 앞발이 몸에서 떨어져 둥둥 뜬다**(실측) — 처음에는
            // 앞발만 두었다가 「파란 원이 옆에 떠 있는」 그림이 됐다. 흰 테두리를 먼저
            // 굵게 깔고 그 위에 몸 색을 얹어야 몸통과 겹쳐도 팔로 읽힌다.
            PinoCurve(from: .init(x: 84, y: 76), control1: .init(x: 98, y: 84),
                      control2: .init(x: 105, y: 90), end: .init(x: 111, y: 99))
                .stroke(tone.stroke, style: .init(lineWidth: 16, lineCap: .round))
            PinoCurve(from: .init(x: 84, y: 76), control1: .init(x: 98, y: 84),
                      control2: .init(x: 105, y: 90), end: .init(x: 111, y: 99))
                .stroke(tone.gradient, style: .init(lineWidth: 10, lineCap: .round))

            // 몸통에 붙여 두었더니 「떠 있는 파란 원」으로 보였다(실측). 밖으로 내밀고
            // 키워야 비로소 가리키는 앞발로 읽힌다.
            ZStack(alignment: .topLeading) {
                PinoOval(center: .zero, radii: .init(width: 17, height: 13)).fill(tone.gradient)
                PinoOval(center: .zero, radii: .init(width: 17, height: 13)).stroke(tone.stroke, lineWidth: 3.5)
                PinoDot(center: .init(x: -6.5, y: -7.5), radius: 3.8).fill(Pino.blush)
                PinoDot(center: .init(x: 2.5, y: -10), radius: 3.8).fill(Pino.blush)
                PinoDot(center: .init(x: 10.5, y: -5.5), radius: 3.8).fill(Pino.blush)
            }
            .offset(x: 114, y: 104)

        case .speech:
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 13)
                    .fill(Color.accentColor)
                    .frame(width: 50, height: 32)
                    .offset(x: -24, y: -16)
                PinoTriangle(points: [.init(x: -13, y: 14), .init(x: -18, y: 24), .init(x: -4, y: 16)])
                    .fill(Color.accentColor)
                PinoDot(center: .init(x: -12, y: 0), radius: 2.8).fill(.white)
                PinoDot(center: .init(x: 1, y: 0), radius: 2.8).fill(.white)
                PinoDot(center: .init(x: 14, y: 0), radius: 2.8).fill(.white)
            }
            .offset(x: 124, y: 10)
        }
    }

    // MARK: 숨쉬기

    /// 깜빡임과 귀 씰룩은 **불규칙해야** 살아 있어 보인다. 같은 주기로 반복하면
    /// 기계처럼 보이므로 사이를 조금씩 벌려 둔다.
    private func breathe() async {
        guard isAlive else { return }

        withAnimation(.easeInOut(duration: 1.2).repeatForever(autoreverses: true)) {
            wag = true
        }

        var beat = 0
        while !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(beat.isMultiple(of: 2) ? 3400 : 2600))
            guard !Task.isCancelled else { return }

            withAnimation(.easeInOut(duration: 0.08)) { blink = true }
            try? await Task.sleep(for: .milliseconds(110))
            withAnimation(.easeInOut(duration: 0.08)) { blink = false }

            if beat.isMultiple(of: 3) {
                withAnimation(.easeInOut(duration: 0.12)) { earTwitch = true }
                try? await Task.sleep(for: .milliseconds(160))
                withAnimation(.easeInOut(duration: 0.16)) { earTwitch = false }
            }
            beat += 1
        }
    }
}

#Preview("진도 · 포즈") {
    VStack(spacing: 24) {
        HStack(spacing: 20) {
            PinoMascot(pose: .plain, tone: .onDeep, width: 120)
            PinoMascot(pose: .magnifier, width: 120)
        }
        HStack(spacing: 20) {
            PinoMascot(pose: .sparkle, width: 120)
            PinoMascot(pose: .paw, width: 120)
            PinoMascot(pose: .speech, width: 120)
        }
    }
    .padding(40)
    .background(Color(.systemGroupedBackground))
}
