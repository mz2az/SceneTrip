import SwiftUI
import UIKit

/// 해태 — SceneTrip 마스코트, **일러스트 판** (2026-08-28 진도에서 교체).
///
/// ## 코드 벡터를 접고 그림으로 갔다
///
/// 물방울 핀에 귀·꼬리를 붙이던 벡터 판은 「마스코트라 하기엔 안 귀엽다」로
/// 끝났다(사용자 판정). 진돗개 일러스트를 거쳐 지금 판은 **해태**(구름 갈기·
/// 뿔·파란 소용돌이 무늬 강아지)다 — 원본 시트는 드롭박스
/// `haetae.png`·`haetae_pin.png`, Vision 피사체 분리로 오려 넣었다.
///
/// ## 그림은 정지, 움직임은 코드
///
/// 래스터 그림은 관절을 못 움직인다. Lottie(리깅+의존성)를 들이는 대신 통그림에
/// 코드 모션을 얹는다 — 숨쉬기(scale)·살랑(rotation). 깜빡임은 눈 감은 짝 그림이
/// 생기면 크로스페이드로 더한다.
///
/// ## 포즈 → 그림 매핑
///
/// | 포즈 | 그림 | 쓰임 |
/// | --- | --- | --- |
/// | plain | haetae-sit (앉아 정면) | 스플래시·마이페이지 |
/// | magnifier | haetae-bow (엎드려 살피는 자세) | 튜토리얼 「찾는다」 |
/// | sparkle | haetae-joy (웃는 눈 앉기) + 반짝별 소품 | 튜토리얼 「짜 준다」 |
/// | paw | haetae-pinhold (지도 핀에서 빼꼼) | 튜토리얼 「데려간다」 |
/// | speech | haetae-sit + 말풍선 소품 | 튜토리얼 「거든다」 |
///
/// 소품(반짝별·말풍선)은 그림에 굽지 않고 **앱이 얹는다** — 장마다 자리가 달라서다.
enum Pino {
    /// 손에 든 것. 튜토리얼 넉 장이 한 포즈씩 쓴다.
    enum Pose {
        case plain
        case magnifier
        case sparkle
        case paw
        case speech
    }

    /// 벡터 판의 흔적 — 이미지 판에서는 그림이 제 색을 갖고 있어 몸 색을 바꾸지
    /// 않는다. `picked`(지도에서 고른 곳)만 `PinoPin` 이 빨간 테로 표현한다.
    enum Tone {
        case onDeep
        case onLight
        case picked
    }

    /// 그림의 세로/가로 비율 (해태 앉기 컷아웃 525×582 — 진도와 달리 세로가 길다).
    static let aspect: CGFloat = 582.0 / 525.0

    /// 프레임 바닥과 발끝 사이 — 그림 아래 여백만큼이다. 스플래시의 그림자가 쓴다.
    static func tipInset(width: CGFloat) -> CGFloat {
        width * 0.05
    }
}

struct PinoMascot: View {
    var pose: Pino.Pose = .plain
    var tone: Pino.Tone = .onLight
    /// 폭. 높이는 그림 비율로 따라온다.
    var width: CGFloat = 180

    /// 숨쉬기·살랑. 정지 그림이 필요하면 끈다.
    var isAlive = true

    @State private var breathing = false
    @State private var swaying = false

    static func tipInset(width: CGFloat) -> CGFloat {
        Pino.tipInset(width: width)
    }

    private var imageName: String {
        switch pose {
        case .plain, .speech: "haetae-sit"
        case .magnifier: "haetae-bow"
        case .sparkle: "haetae-joy"
        case .paw: "haetae-pinhold"
        }
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Image(imageName)
                .resizable()
                .scaledToFit()
                .frame(width: width)

            if pose == .sparkle {
                sparkles
            }
            if pose == .speech {
                bubble
            }
        }
        // 숨쉬기 — 발끝을 축으로 아주 살짝 부푼다.
        .scaleEffect(isAlive && breathing ? 1.02 : 1.0, anchor: .bottom)
        // 살랑 — 꼬리 흔드는 느낌을 몸 전체 기울임으로 낸다.
        .rotationEffect(.degrees(isAlive ? (swaying ? 1.4 : -1.4) : 0), anchor: .bottom)
        .frame(width: width, height: width * Pino.aspect)
        .task(id: isAlive) {
            guard isAlive else { return }
            withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) {
                breathing = true
            }
            withAnimation(.easeInOut(duration: 1.3).repeatForever(autoreverses: true)) {
                swaying = true
            }
        }
    }

    // MARK: 소품

    /// 반짝별 둘 — 「AI 가 짜 준다」의 표시. 별 모양은 벡터 판에서 남긴 것을 쓴다.
    private var sparkles: some View {
        ZStack(alignment: .topLeading) {
            PinoSparkle(arm: width * 0.07)
                .fill(Color(red: 1, green: 0.83, blue: 0.15))
                .offset(x: width * 0.94, y: width * 0.12)
            PinoSparkle(arm: width * 0.045)
                .fill(Color(red: 1, green: 0.83, blue: 0.15))
                .offset(x: width * 0.08, y: width * 0.5)
        }
    }

    /// 말풍선 — 「거든다」의 표시.
    private var bubble: some View {
        ZStack {
            RoundedRectangle(cornerRadius: width * 0.07)
                .fill(Color.accentColor)
                .frame(width: width * 0.3, height: width * 0.19)
            HStack(spacing: width * 0.035) {
                ForEach(0 ..< 3) { _ in
                    Circle().fill(.white).frame(width: width * 0.032)
                }
            }
        }
        .offset(x: width * 0.72, y: -width * 0.04)
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
